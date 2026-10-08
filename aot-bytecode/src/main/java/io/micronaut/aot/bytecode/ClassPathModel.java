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
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.jar.Attributes.Name;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * A read-only model of every class on a runtime class path. For every binary name it records the copy that the
 * class path resolves first, with its access flags and superclass: that is the class hierarchy the gate of
 * {@link ClassTransformPipeline} verifies rewritten classes against. Built with member tables, for
 * {@link LambdaDesugarer}, it also records that copy's interfaces, nest host and the name, descriptor and flags of
 * every field and method, and the facts that decide whether a name is certain: how many entries hold a copy of it,
 * whether any entry holds a {@code META-INF/versions/} copy of it, which packages the class path holds, and which
 * classes of each entry may hold a lambda call site.
 *
 * <p>The copy it records is the first in class-path order. Within a jar whose manifest says
 * {@code Multi-Release: true}, the {@code META-INF/versions/N/} variants that the JDK running the transform would
 * load come first, in descending version, then the base entry; a jar without that attribute, and a directory, have
 * only their base entries. Which copy of a duplicated name the model picks does not change what a step writes:
 * the gate verifies a class's original and its rewrite against the same model, and desugaring leaves every name
 * that two entries hold, or that has a versioned copy, as it is.</p>
 *
 * <p>The model is a {@link ClassHierarchyResolver} for the classes it holds and falls back to the JDK's own classes
 * only, read from the modules of the boot layer ({@link JdkClasses#open(String)}). It deliberately does not use
 * {@link ClassHierarchyResolver#defaultResolver()}, which also sees the class path of the build tool running the
 * transform, such as a Gradle daemon's, and the application never does.</p>
 *
 * <p>It is built by one {@link LayerScan} per class path entry, which may run on any thread, merged on the calling
 * thread in class-path order, and is immutable afterwards.</p>
 */
final class ClassPathModel implements ClassHierarchyResolver {

    /** The largest class the scan parses; a larger one is left out of the model, but its name is known. */
    static final int MAX_CLASS_SIZE = ClassTransformPipeline.MAX_CLASS_SIZE;

    /** The feature version of the JDK that runs the transform, which decides the multi-release variants. */
    static final int RUNTIME_FEATURE = Runtime.version().feature();

    /** The lowest version a {@code META-INF/versions/N/} directory can select, as {@link JarFile} reads them. */
    private static final int MIN_VERSION = 9;

    private static final String VERSIONS_PREFIX = "META-INF/versions/";

    private static final String META_INF = "META-INF/";

    private static final String CLASS_SUFFIX = ".class";

    /**
     * The JDK's own classes only, read from the modules of the boot layer: those of the boot and platform loaders,
     * and those the application loader defines, such as {@code jdk.compiler}, which the platform loader cannot see.
     * The cache lives as long as the JVM that runs the transform, because its JDK does not change between two
     * builds, and a Gradle daemon runs many.
     */
    private static final ClassHierarchyResolver JDK = ClassHierarchyResolver.ofResourceParsing(
            (ClassDesc type) -> type.isClassOrInterface() ? JdkClasses.open(internalName(type)) : null)
            .cached(ConcurrentHashMap::new);

    private final Map<String, Resolution> classes;

    private final Set<String> packages;

    private final List<Set<String>> lambdaEntries;

    private final Watched watched;

    private final boolean members;

    private ClassPathModel(Map<String, Resolution> classes, Set<String> packages, List<Set<String>> lambdaEntries,
                           Watched watched, boolean members) {
        this.classes = classes;
        this.packages = packages;
        this.lambdaEntries = lambdaEntries;
        this.watched = watched;
        this.members = members;
    }

    /**
     * Starts the header-only scan of one class path entry, for a caller that hands it the entry's classes itself.
     *
     * @param name         what messages call the entry
     * @param multiRelease whether the entry's versioned directories count
     * @param watch        the class entries worth reporting, such as a library that reads what a step drops
     * @return the scan
     */
    static LayerScan scan(String name, boolean multiRelease, Predicate<String> watch) {
        return scan(name, multiRelease, false, watch, new Interner());
    }

    /**
     * Starts the scan of one class path entry, for a caller that hands it the entry's classes itself.
     *
     * @param name         what messages call the entry
     * @param multiRelease whether the entry's versioned directories count
     * @param members      whether to record member tables, nest hosts and the classes that may hold a lambda
     * @param watch        the class entries worth reporting, such as a library that reads what a step drops
     * @param strings      the interner every scan of one run shares
     * @return the scan
     */
    static LayerScan scan(String name, boolean multiRelease, boolean members, Predicate<String> watch,
                          Interner strings) {
        return new LayerScan(name, multiRelease, members, watch, strings);
    }

    /**
     * Scans one class path entry header-only: a jar, or a directory of classes.
     *
     * @param entry the entry
     * @param name  what messages call it
     * @param watch the class entries worth reporting
     * @return the scan
     * @throws IOException if an entry of the jar, or a file of the directory, cannot be read
     */
    static LayerScan scan(Path entry, String name, Predicate<String> watch) throws IOException {
        return scan(entry, name, false, watch, new Interner());
    }

    /**
     * Scans one class path entry: a jar, or a directory of classes. An entry that does not exist, and a file that
     * is no zip archive, hold no class, as the JDK's application class loader treats them.
     *
     * @param entry   the entry
     * @param name    what messages call it
     * @param members whether to record member tables, nest hosts and the classes that may hold a lambda
     * @param watch   the class entries worth reporting
     * @param strings the interner every scan of one run shares
     * @return the scan
     * @throws IOException if an entry of the jar, or a file of the directory, cannot be read
     */
    static LayerScan scan(Path entry, String name, boolean members, Predicate<String> watch, Interner strings)
            throws IOException {
        if (Files.isDirectory(entry)) {
            LayerScan scan = scan(name, false, members, watch, strings);
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
            return scan(name, false, members, watch, strings);
        }
        ZipFile zip;
        try {
            zip = new ZipFile(entry.toFile());
        } catch (ZipException e) {
            return scan(name, false, members, watch, strings);
        }
        try (zip) {
            LayerScan scan = scan(name, isMultiRelease(zip), members, watch, strings);
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
     * Merges the scans of every entry, in class-path order. The position of a scan in the list is the index of its
     * entry, which {@link Copy#layer()} answers.
     *
     * @param scans one finished scan per class path entry
     * @return the model, with member tables when every scan recorded them
     */
    static ClassPathModel merge(List<LayerScan> scans) {
        Map<String, Resolution> classes = new HashMap<>();
        Set<String> packages = new HashSet<>();
        List<Set<String>> lambdaEntries = new ArrayList<>(scans.size());
        Watched watched = null;
        boolean members = true;
        for (int index = 0; index < scans.size(); index++) {
            LayerScan scan = scans.get(index);
            members &= scan.members;
            lambdaEntries.add(Set.copyOf(scan.lambdaEntries));
            if (watched == null && scan.watched != null) {
                watched = new Watched(scan.name, scan.watched);
            }
            for (String name : scan.names) {
                classes.computeIfAbsent(name, key -> new Resolution()).holders++;
                packages.add(packageOf(name));
            }
            for (String name : scan.versionedNames) {
                classes.computeIfAbsent(name, key -> new Resolution()).versioned = true;
                packages.add(packageOf(name));
            }
            // Within one entry, variants in descending version before the base entry; the sort is stable, so a
            // name an entry carries twice resolves to its first copy.
            Map<String, List<Copy>> byName = new LinkedHashMap<>();
            for (Copy copy : scan.copies) {
                copy.layer = index;
                byName.computeIfAbsent(copy.name, key -> new ArrayList<>(1)).add(copy);
            }
            for (Map.Entry<String, List<Copy>> chain : byName.entrySet()) {
                Resolution resolution = classes.computeIfAbsent(chain.getKey(), key -> new Resolution());
                if (resolution.winner != null) {
                    continue;
                }
                List<Copy> copies = chain.getValue();
                copies.sort(Comparator.comparingInt((Copy copy) -> -copy.version));
                resolution.winner = copies.get(0);
            }
        }
        return new ClassPathModel(classes, Set.copyOf(packages), List.copyOf(lambdaEntries), watched, members);
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
     * The number of distinct class names the model holds a copy for.
     *
     * @return the class count
     */
    int size() {
        int size = 0;
        for (Resolution resolution : classes.values()) {
            if (resolution.winner != null) {
                size++;
            }
        }
        return size;
    }

    /**
     * The copy of a class that the class path resolves first, as the gate verifies against it.
     *
     * @param internalName the class, such as {@code com/example/Foo}
     * @return the copy, or empty when no entry holds one that the model could read
     */
    Optional<Copy> winner(String internalName) {
        Resolution resolution = classes.get(internalName);
        return resolution == null ? Optional.empty() : Optional.ofNullable(resolution.winner);
    }

    /**
     * How many entries of the class path hold a base copy of a class: a {@code .class} entry outside
     * {@code META-INF/} by that name, whether the model could read it or not.
     *
     * @param internalName the class
     * @return the number of entries
     */
    int holders(String internalName) {
        Resolution resolution = classes.get(internalName);
        return resolution == null ? 0 : resolution.holders;
    }

    /**
     * Whether any entry holds a {@code META-INF/versions/N/} copy of a class, whatever its manifest says: a fat JAR
     * or an image may load it, depending on a manifest the transform does not see.
     *
     * @param internalName the class
     * @return whether a versioned copy exists
     */
    boolean versioned(String internalName) {
        Resolution resolution = classes.get(internalName);
        return resolution != null && resolution.versioned;
    }

    /**
     * Whether any entry holds a class of this name, as a base copy or in a versioned directory, whether a JDK loads
     * it or not.
     *
     * @param internalName the class
     * @return whether the name is taken
     */
    boolean known(String internalName) {
        return classes.containsKey(internalName);
    }

    /**
     * Whether any entry holds a class in a package.
     *
     * @param internalPackage the package, such as {@code com/example}, or the empty string
     * @return whether the class path holds the package
     */
    boolean holdsPackage(String internalPackage) {
        return packages.contains(internalPackage);
    }

    /**
     * Whether a class entry of a class path entry passes the desugar step's pre-filter
     * ({@link LambdaDesugarer#matchesBytes(byte[])}), which the scan checks on the bytes it read anyway, so that the
     * step reads again only the classes it may rewrite.
     *
     * @param layer     the index of the class path entry
     * @param entryName the class's entry name
     * @return whether it names {@code LambdaMetafactory}; {@code false} when the model has no member tables
     */
    boolean lambdas(int layer, String entryName) {
        return layer >= 0 && layer < lambdaEntries.size() && lambdaEntries.get(layer).contains(entryName);
    }

    /**
     * Whether any class of a class path entry passes the desugar step's pre-filter.
     *
     * @param layer the index of the class path entry
     * @return whether the entry may hold a lambda call site; {@code false} when the model has no member tables
     */
    boolean hasLambdas(int layer) {
        return layer >= 0 && layer < lambdaEntries.size() && !lambdaEntries.get(layer).isEmpty();
    }

    /**
     * Whether the scans recorded member tables and nest hosts.
     *
     * @return whether {@link Copy#member(String, String)} and {@link Copy#nestHost()} answer
     */
    boolean hasMembers() {
        return members;
    }

    /**
     * Whether code in a package can access a member of a class, by the rules the JVM applies to the recorded
     * copies: the class must be public or in the same package, and the member public, or not private and in the
     * same package. A protected member of a class in another package is reported as inaccessible, because that
     * depends on the accessing class, which the model is not told.
     *
     * @param owner       the class that declares the member, such as {@code com/example/Foo}
     * @param name        the member's name
     * @param descriptor  the member's descriptor
     * @param fromPackage the accessing package, such as {@code com/example}, or the empty string
     * @return whether the access is legal; {@code false} when the model has no such class or member
     * @throws IllegalStateException if the model was built without member tables
     */
    boolean isAccessible(String owner, String name, String descriptor, String fromPackage) {
        if (!members) {
            throw new IllegalStateException("The class path model was built without member tables");
        }
        Optional<Copy> copy = winner(owner);
        if (copy.isEmpty()) {
            return false;
        }
        Member member = copy.get().member(name, descriptor);
        if (member == null) {
            return false;
        }
        boolean samePackage = packageOf(owner).equals(fromPackage);
        if ((copy.get().flags & ClassFile.ACC_PUBLIC) == 0 && !samePackage) {
            return false;
        }
        if ((member.flags() & ClassFile.ACC_PUBLIC) != 0) {
            return true;
        }
        return (member.flags() & ClassFile.ACC_PRIVATE) == 0 && samePackage;
    }

    @Override
    public ClassHierarchyInfo getClassInfo(ClassDesc classDesc) {
        if (classDesc.isClassOrInterface()) {
            Resolution resolution = classes.get(internalName(classDesc));
            if (resolution != null && resolution.winner != null) {
                Copy winner = resolution.winner;
                if (winner.isInterface()) {
                    return ClassHierarchyInfo.ofInterface();
                }
                return ClassHierarchyInfo.ofClass(winner.superName == null
                        ? null : ClassDesc.ofInternalName(winner.superName));
            }
        }
        return JDK.getClassInfo(classDesc);
    }

    /**
     * Whether a jar's manifest declares {@code Multi-Release: true}. A manifest the JDK cannot parse counts as none.
     *
     * @param zip the jar
     * @return whether its versioned directories count
     * @throws IOException if the manifest cannot be read
     */
    static boolean isMultiRelease(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(JarFile.MANIFEST_NAME);
        if (entry == null) {
            return false;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            java.util.jar.Attributes main = new Manifest(in).getMainAttributes();
            return "true".equalsIgnoreCase(main.getValue(Name.MULTI_RELEASE));
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
    static int versionOf(String entryName) {
        if (!entryName.startsWith(VERSIONS_PREFIX)) {
            return 0;
        }
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
     * The path of a versioned entry below its version directory.
     *
     * @param entryName an entry for which {@link #versionOf(String)} is not {@code 0}
     * @return the path, such as {@code a/B.class} for {@code META-INF/versions/11/a/B.class}
     */
    static String pathOf(String entryName) {
        return entryName.substring(entryName.indexOf('/', VERSIONS_PREFIX.length()) + 1);
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private static String internalName(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
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
     * One field or method of a class.
     *
     * @param name       its name
     * @param descriptor its descriptor
     * @param flags      its access flags
     */
    record Member(String name, String descriptor, int flags) {
    }

    /**
     * One copy of a class, as one class path entry holds it.
     */
    static final class Copy {

        private final String name;
        private final int version;
        private final int flags;
        private final String superName;
        private final List<String> interfaces;
        private final List<Member> members;
        private final String nestHost;
        private final boolean inert;
        private final InterfaceInitializers.Initializer initializer;
        /** The index of the entry that holds the copy, set when the scans are merged. */
        private int layer = -1;

        private Copy(String name, int version, int flags, String superName, List<String> interfaces,
                     List<Member> members, String nestHost, boolean inert,
                     InterfaceInitializers.Initializer initializer) {
            this.name = name;
            this.version = version;
            this.flags = flags;
            this.superName = superName;
            this.interfaces = interfaces;
            this.members = members;
            this.nestHost = nestHost;
            this.inert = inert;
            this.initializer = initializer;
        }

        /**
         * The class's internal name.
         *
         * @return the name, such as {@code com/example/Foo}
         */
        String name() {
            return name;
        }

        /**
         * The class path entry that holds this copy.
         *
         * @return its index in the class path
         */
        int layer() {
            return layer;
        }

        /**
         * The version directory this copy lives in.
         *
         * @return {@code N} of {@code META-INF/versions/N/}, or {@code 0} for the base entry
         */
        int version() {
            return version;
        }

        /**
         * The class's access flags.
         *
         * @return the flags
         */
        int flags() {
            return flags;
        }

        /**
         * The superclass.
         *
         * @return its internal name, or {@code null} for {@code java/lang/Object}
         */
        String superName() {
            return superName;
        }

        /**
         * The directly implemented interfaces.
         *
         * @return their internal names, in declaration order; empty when the model has no member tables
         */
        List<String> interfaces() {
            return interfaces;
        }

        /**
         * Whether the class is an interface.
         *
         * @return whether {@code ACC_INTERFACE} is set
         */
        boolean isInterface() {
            return (flags & ClassFile.ACC_INTERFACE) != 0;
        }

        /**
         * The class its {@code NestHost} attribute names.
         *
         * @return the nest host's internal name, or {@code null} when the class has no such attribute, or the model
         * has no member tables
         */
        String nestHost() {
            return nestHost;
        }

        /**
         * A field or method the class declares itself.
         *
         * @param memberName the member's name
         * @param descriptor its descriptor
         * @return the member, or {@code null} when the class declares none, or the model has no member tables
         */
        Member member(String memberName, String descriptor) {
            if (members == null) {
                return null;
            }
            for (Member member : members) {
                if (member.name().equals(memberName) && member.descriptor().equals(descriptor)) {
                    return member;
                }
            }
            return null;
        }

        /**
         * Whether the class is inert: creating an instance of it runs no code of the class path besides the
         * initialization of its interfaces ({@link InterfaceInitializers#inert}).
         *
         * @return whether it is inert; {@code false} when the model has no member tables
         */
        boolean inert() {
            return inert;
        }

        /**
         * What the quiet static initializer of an interface depends on ({@link InterfaceInitializers#summarize}).
         *
         * @return the summary; {@code null} when the class has no static initializer, has one that is not quiet, is
         * not an interface, or the model has no member tables
         */
        InterfaceInitializers.Initializer initializer() {
            return initializer;
        }

        /**
         * Whether the class declares a method that is neither abstract nor static, other than a constructor: for an
         * interface, what makes the JVM initialize it before a class that implements it.
         *
         * @return whether such a method is declared; {@code false} when the model has no member tables
         */
        boolean declaresConcreteInstanceMethod() {
            if (members == null) {
                return false;
            }
            for (Member member : members) {
                if (member.descriptor().charAt(0) == '(' && member.name().charAt(0) != '<'
                        && (member.flags() & (ClassFile.ACC_ABSTRACT | ClassFile.ACC_STATIC)) == 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** What the model knows about one binary name. */
    private static final class Resolution {
        private Copy winner;
        private int holders;
        private boolean versioned;
    }

    /**
     * Deduplicates the names and descriptors of one run's scans, which run on several threads at once.
     */
    static final class Interner {

        private final ConcurrentHashMap<String, String> strings = new ConcurrentHashMap<>();

        String intern(String value) {
            String existing = strings.putIfAbsent(value, value);
            return existing == null ? value : existing;
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
        private final boolean members;
        private final Predicate<String> watch;
        private final Interner strings;
        private final List<Copy> copies = new ArrayList<>();
        private final Set<String> names = new HashSet<>();
        private final Set<String> versionedNames = new HashSet<>();
        private final Set<String> lambdaEntries = new HashSet<>();
        private String watched;

        private LayerScan(String name, boolean multiRelease, boolean members, Predicate<String> watch,
                          Interner strings) {
            this.name = Objects.requireNonNull(name, "name");
            this.multiRelease = multiRelease;
            this.members = members;
            this.watch = Objects.requireNonNull(watch, "watch");
            this.strings = Objects.requireNonNull(strings, "strings");
        }

        /**
         * Whether an entry is a class this scan records, so its bytes are worth reading. It also notes the name of
         * every class entry, readable or not, and the first watched class.
         *
         * @param entryName the entry name
         * @param size      its uncompressed size, or {@code -1} when it is not known
         * @return whether {@link #accept(String, byte[])} should be called with its content
         */
        boolean wants(String entryName, long size) {
            if (!ClassTransformPipeline.isClass(entryName)) {
                return false;
            }
            if (!entryName.startsWith(META_INF)) {
                if (!LocalVariableStripper.isModuleInfo(entryName)) {
                    names.add(strings.intern(withoutSuffix(entryName)));
                }
            } else if (versionOf(entryName) != 0) {
                String path = pathOf(entryName);
                if (ClassTransformPipeline.isClass(path) && !path.startsWith(META_INF)
                        && !LocalVariableStripper.isModuleInfo(path)) {
                    versionedNames.add(strings.intern(withoutSuffix(path)));
                }
            }
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
            String internalName = withoutSuffix(path);
            int version = entryName.equals(path) ? 0 : versionOf(entryName);
            Copy copy;
            try {
                copy = read(internalName, version, PARSER.parse(bytes));
            } catch (IllegalArgumentException e) {
                // The ClassFile API reads lazily: a truncated class, or a header index that points at the wrong kind
                // of constant, only fails when the name, the flags, the superclass, an interface or a member is
                // asked for, so every read of the parsed class sits inside this try.
                return;
            }
            if (copy != null) {
                copies.add(copy);
                if (members && LambdaDesugarer.matchesBytes(bytes)) {
                    lambdaEntries.add(entryName);
                }
            }
        }

        /**
         * Reads everything the model keeps of one parsed class.
         *
         * @return the copy, or {@code null} when the class does not declare the name its entry implies
         * @throws IllegalArgumentException if any part of the header, or of a member, is malformed
         */
        private Copy read(String internalName, int version, ClassModel model) {
            if (!model.thisClass().asInternalName().equals(internalName)) {
                return null;
            }
            int flags = model.flags().flagsMask();
            String superName = model.superclass().map(ClassEntry::asInternalName).map(strings::intern).orElse(null);
            if (!members) {
                return new Copy(strings.intern(internalName), version, flags, superName, List.of(), null, null, false,
                        null);
            }
            List<String> interfaces = new ArrayList<>(model.interfaces().size());
            for (ClassEntry entry : model.interfaces()) {
                interfaces.add(strings.intern(entry.asInternalName()));
            }
            String nestHost = model.findAttribute(Attributes.nestHost())
                    .map(attribute -> strings.intern(attribute.nestHost().asInternalName())).orElse(null);
            List<Member> table = new ArrayList<>(model.fields().size() + model.methods().size());
            for (FieldModel field : model.fields()) {
                table.add(new Member(strings.intern(field.fieldName().stringValue()),
                        strings.intern(field.fieldType().stringValue()), field.flags().flagsMask()));
            }
            for (MethodModel method : model.methods()) {
                table.add(new Member(strings.intern(method.methodName().stringValue()),
                        strings.intern(method.methodType().stringValue()), method.flags().flagsMask()));
            }
            boolean isInterface = (flags & ClassFile.ACC_INTERFACE) != 0;
            InterfaceInitializers.Initializer initializer = isInterface
                    ? InterfaceInitializers.summarize(internalName, model) : null;
            return new Copy(strings.intern(internalName), version, flags, superName,
                    interfaces.isEmpty() ? List.of() : Collections.unmodifiableList(interfaces),
                    List.copyOf(table), nestHost, !isInterface && InterfaceInitializers.inert(model),
                    initializer);
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
            if (!multiRelease) {
                return null;
            }
            int version = versionOf(entryName);
            if (version == 0 || version > RUNTIME_FEATURE) {
                return null;
            }
            String path = pathOf(entryName);
            return path.isEmpty() || path.startsWith(META_INF) ? null : path;
        }

        private static String withoutSuffix(String path) {
            return path.substring(0, path.length() - CLASS_SUFFIX.length());
        }
    }
}
