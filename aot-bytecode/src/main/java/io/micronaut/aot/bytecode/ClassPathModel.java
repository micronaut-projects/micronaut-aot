/*
 * Copyright 2017-2026 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.aot.bytecode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A read-only, header-only model of every class on a runtime class path: for every binary name, the access flags
 * and the superclass of the copy that the class path resolves first. It is the class hierarchy that the gate of
 * {@link ClassTransformPipeline} verifies rewritten classes against, and it records nothing else.
 *
 * <p>The copy it records is the first in class-path order. Within a jar whose manifest says
 * {@code Multi-Release: true}, the {@code META-INF/versions/N/} variants that the JDK running the transform would
 * load come first, in descending version, then the base entry; a jar without that attribute, and a directory, have
 * only their base entries. Which copy of a duplicated name the model picks does not change what a step writes,
 * because the gate verifies a class's original and its rewrite against the same model.</p>
 *
 * <p>The model is a {@link ClassHierarchyResolver} for the classes it holds and falls back to the JDK's own classes
 * only, parsed from the platform class loader. It deliberately does not use
 * {@link ClassHierarchyResolver#defaultResolver()}, which also sees the class path of the build tool running the
 * transform, such as a Gradle daemon's, and the application never does.</p>
 *
 * <p>It is built by one {@link LayerScan} per class path entry, which may run on any thread, merged on the calling
 * thread in class-path order, and is immutable afterwards.</p>
 */
final class ClassPathModel implements ClassHierarchyResolver {

    /** The largest class the scan parses; a larger one is left out of the model. */
    static final int MAX_CLASS_SIZE = ClassTransformPipeline.MAX_CLASS_SIZE;

    /** The feature version of the JDK that runs the transform, which decides the multi-release variants. */
    private static final int RUNTIME_FEATURE = Runtime.version().feature();

    /** The lowest version a {@code META-INF/versions/N/} directory can select, as {@link JarFile} reads them. */
    private static final int MIN_VERSION = 9;

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private static final String META_INF = "META-INF/";

    private static final String CLASS_SUFFIX = ".class";

    /**
     * The JDK's own classes only, parsed from the platform class loader. The cache lives as long as the JVM that
     * runs the transform, because its JDK does not change between two builds, and a Gradle daemon runs many.
     */
    private static final ClassHierarchyResolver JDK =
            ClassHierarchyResolver.ofResourceParsing(ClassLoader.getPlatformClassLoader()).cached(ConcurrentHashMap::new);

    private final Map<String, ClassHierarchyInfo> classes;

    private final Watched watched;

    private ClassPathModel(Map<String, ClassHierarchyInfo> classes, Watched watched) {
        this.classes = classes;
        this.watched = watched;
    }

    /**
     * Starts the scan of one class path entry, for a caller that hands it the entry's classes itself.
     *
     * @param name         what messages call the entry
     * @param multiRelease whether the entry's versioned directories count
     * @param watch        the class entries worth reporting, such as a library that reads what a step drops
     * @return the scan
     */
    static LayerScan scan(String name, boolean multiRelease, Predicate<String> watch) {
        return new LayerScan(name, multiRelease, watch);
    }

    /**
     * Scans one class path entry: a jar, or a directory of classes. An entry that does not exist, and a file that
     * is no zip archive, hold no class, as the JDK's application class loader treats them.
     *
     * @param entry the entry
     * @param name  what messages call it
     * @param watch the class entries worth reporting
     * @return the scan
     * @throws IOException if an entry of the jar, or a file of the directory, cannot be read
     */
    static LayerScan scan(Path entry, String name, Predicate<String> watch) throws IOException {
        if (Files.isDirectory(entry)) {
            LayerScan scan = scan(name, false, watch);
            List<Path> files;
            try (Stream<Path> walk = Files.walk(entry)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName = entry.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
                if (scan.wants(entryName, Files.size(file))) {
                    scan.accept(entryName, Files.readAllBytes(file));
                }
            }
            return scan;
        }
        if (!Files.isRegularFile(entry)) {
            return scan(name, false, watch);
        }
        ZipFile zip;
        try {
            zip = new ZipFile(entry.toFile());
        } catch (ZipException e) {
            return scan(name, false, watch);
        }
        try (zip) {
            LayerScan scan = scan(name, isMultiRelease(zip), watch);
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry zipEntry = entries.nextElement();
                if (!zipEntry.isDirectory() && scan.wants(zipEntry.getName(), zipEntry.getSize())) {
                    try (InputStream in = zip.getInputStream(zipEntry)) {
                        scan.accept(zipEntry.getName(), in.readAllBytes());
                    }
                }
            }
            return scan;
        }
    }

    /**
     * Merges the scans of every entry, in class-path order.
     *
     * @param scans one finished scan per class path entry
     * @return the model
     */
    static ClassPathModel merge(List<LayerScan> scans) {
        Map<String, ClassHierarchyInfo> classes = new HashMap<>();
        Watched watched = null;
        for (LayerScan scan : scans) {
            if (watched == null && scan.watched != null) {
                watched = new Watched(scan.name, scan.watched);
            }
            // Within one entry, variants in descending version before the base entry; the sort is stable, so a
            // name an entry carries twice resolves to its first copy.
            Map<String, List<Copy>> byName = new LinkedHashMap<>();
            for (Copy copy : scan.copies) {
                byName.computeIfAbsent(copy.name, key -> new ArrayList<>(1)).add(copy);
            }
            for (Map.Entry<String, List<Copy>> chain : byName.entrySet()) {
                if (classes.containsKey(chain.getKey())) {
                    continue;
                }
                List<Copy> copies = chain.getValue();
                copies.sort(Comparator.comparingInt((Copy copy) -> -copy.version));
                classes.put(chain.getKey(), copies.get(0).info);
            }
        }
        return new ClassPathModel(classes, watched);
    }

    /**
     * The first watched class the scans found, in class-path order.
     *
     * @return the entry and the class, or empty when no entry holds one
     */
    Optional<Watched> watched() {
        return Optional.ofNullable(watched);
    }

    /**
     * The number of distinct class names the model holds.
     *
     * @return the class count
     */
    int size() {
        return classes.size();
    }

    @Override
    public ClassHierarchyInfo getClassInfo(ClassDesc classDesc) {
        if (classDesc.isClassOrInterface()) {
            String descriptor = classDesc.descriptorString();
            ClassHierarchyInfo info = classes.get(descriptor.substring(1, descriptor.length() - 1));
            if (info != null) {
                return info;
            }
        }
        return JDK.getClassInfo(classDesc);
    }

    private static boolean isMultiRelease(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(JarFile.MANIFEST_NAME);
        if (entry == null) {
            return false;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            Attributes main = new Manifest(in).getMainAttributes();
            return "true".equalsIgnoreCase(main.getValue(Attributes.Name.MULTI_RELEASE));
        } catch (IOException | RuntimeException e) {
            // The JDK reads a manifest it cannot parse as no manifest at all.
            return false;
        }
    }

    /**
     * The version a {@code META-INF/versions/N/} entry is selected for.
     *
     * @param entryName the entry name
     * @return {@code N}, or {@code 0} when the entry is not in a version directory that a JDK selects
     */
    private static int versionOf(String entryName) {
        int slash = entryName.indexOf('/', VERSIONS_PREFIX.length());
        if (slash < 0 || slash == VERSIONS_PREFIX.length() || slash - VERSIONS_PREFIX.length() > 3) {
            return 0;
        }
        char first = entryName.charAt(VERSIONS_PREFIX.length());
        if (first < '1' || first > '9') {
            // A leading zero is not a version: the JDK treats META-INF/versions/09/x as an ordinary entry.
            return 0;
        }
        int version = 0;
        for (int i = VERSIONS_PREFIX.length(); i < slash; i++) {
            char c = entryName.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
            version = version * 10 + (c - '0');
        }
        return version < MIN_VERSION ? 0 : version;
    }

    /**
     * The class entry a scan found that a caller asked to be told about.
     *
     * @param layer the name of the class path entry that holds it
     * @param entry the class's entry name, relative to its class path entry
     */
    record Watched(String layer, String entry) {
    }

    /**
     * One copy of a class, as one class path entry holds it.
     */
    private static final class Copy {

        private final String name;
        private final int version;
        private final ClassHierarchyInfo info;

        private Copy(String name, int version, ClassHierarchyInfo info) {
            this.name = name;
            this.version = version;
            this.info = info;
        }
    }

    /**
     * The scan of one class path entry. It is confined to the thread that runs it until it is handed to
     * {@link #merge(List)}, and holds nothing but what it records.
     */
    static final class LayerScan {

        private static final ClassFile PARSER = ClassFile.of();

        private final String name;
        private final boolean multiRelease;
        private final Predicate<String> watch;
        private final List<Copy> copies = new ArrayList<>();
        private String watched;

        private LayerScan(String name, boolean multiRelease, Predicate<String> watch) {
            this.name = Objects.requireNonNull(name, "name");
            this.multiRelease = multiRelease;
            this.watch = Objects.requireNonNull(watch, "watch");
        }

        /**
         * Whether an entry is a class this scan records, so its bytes are worth reading. It also notes the first
         * watched class.
         *
         * @param entryName the entry name
         * @param size      its uncompressed size, or {@code -1} when it is not known
         * @return whether {@link #accept(String, byte[])} should be called with its content
         */
        boolean wants(String entryName, long size) {
            String path = classPath(entryName);
            if (path == null) {
                return false;
            }
            if (watched == null && watch.test(path)) {
                watched = path;
            }
            return size >= 0 && size <= MAX_CLASS_SIZE && !LocalVariableStripper.isModuleInfo(path);
        }

        /**
         * Records one class. A class whose header cannot be parsed, or that does not declare the name its entry
         * implies, is left out: no class loader could define it under that name. The scan does not fail on it:
         * whether the class is rewritten is the pipeline's decision.
         *
         * @param entryName the entry name
         * @param bytes     its content
         */
        void accept(String entryName, byte[] bytes) {
            String path = classPath(entryName);
            if (path == null) {
                return;
            }
            String internalName = path.substring(0, path.length() - CLASS_SUFFIX.length());
            int version = entryName.equals(path) ? 0 : versionOf(entryName);
            try {
                ClassModel model = PARSER.parse(bytes);
                if (!model.thisClass().asInternalName().equals(internalName)) {
                    return;
                }
                ClassHierarchyInfo info;
                if ((model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0) {
                    info = ClassHierarchyInfo.ofInterface();
                } else {
                    Optional<ClassEntry> superclass = model.superclass();
                    info = ClassHierarchyInfo.ofClass(superclass.isPresent()
                            ? ClassDesc.ofInternalName(superclass.get().asInternalName()) : null);
                }
                copies.add(new Copy(internalName, version, info));
            } catch (IllegalArgumentException e) {
                // The ClassFile API reads lazily: a truncated class, or a header index that points at the wrong kind
                // of constant, only fails when the name, the flags or the superclass is asked for, so every read of
                // the parsed class sits inside this try.
            }
        }

        /**
         * The class-path name of an entry: the entry itself for a base class, the path after the version directory
         * for a variant that counts, or {@code null} for anything that is not a class on the class path.
         */
        private String classPath(String entryName) {
            if (!ClassTransformPipeline.isClass(entryName)) {
                return null;
            }
            if (!entryName.startsWith(META_INF)) {
                return entryName;
            }
            if (!multiRelease || !entryName.startsWith(VERSIONS_PREFIX)) {
                return null;
            }
            int version = versionOf(entryName);
            if (version == 0 || version > RUNTIME_FEATURE) {
                return null;
            }
            String path = entryName.substring(entryName.indexOf('/', VERSIONS_PREFIX.length()) + 1);
            return path.isEmpty() || path.startsWith(META_INF) ? null : path;
        }
    }
}
