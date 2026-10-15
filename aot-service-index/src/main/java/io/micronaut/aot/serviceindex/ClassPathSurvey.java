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
package io.micronaut.aot.serviceindex;

import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.GENERATED_PATH;
import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.LOADER_CLASS;
import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.LOADER_SERVICE;

/**
 * What the class path holds that decides the index, read from the entries' names and a few small files, without
 * loading anything: the service types to index, the earlier output in the holder, and what makes the class path one
 * that an index cannot describe.
 */
final class ClassPathSurvey {

    private static final String SERVICES = "META-INF/services/";
    private static final String MICRONAUT = "META-INF/micronaut/";
    private static final String LOADER_TYPE = LOADER_SERVICE.substring(SERVICES.length());
    private static final String LOADER_ENTRY = LOADER_CLASS.replace('.', '/') + ".class";
    private static final Pattern SIGNATURE = Pattern.compile("META-INF/(SIG-[^/]*|[^/]+\\.(SF|RSA|DSA|EC))");

    private final SortedSet<String> types;
    private final boolean nothingToIndex;
    private final @Nullable String loaderService;
    private final SortedSet<String> earlierOutput;
    private final @Nullable String standDown;
    private final @Nullable String failure;

    private ClassPathSurvey(SortedSet<String> types, boolean nothingToIndex, @Nullable String loaderService,
                            SortedSet<String> earlierOutput, @Nullable String standDown, @Nullable String failure) {
        this.types = types;
        this.nothingToIndex = nothingToIndex;
        this.loaderService = loaderService;
        this.earlierOutput = earlierOutput;
        this.standDown = standDown;
        this.failure = failure;
    }

    /**
     * Reads a class path.
     *
     * @param files  the entries, which exist
     * @param holder the position of the holder
     * @return what it holds
     * @throws IOException if an entry cannot be read
     */
    static ClassPathSurvey of(List<Path> files, int holder) throws IOException {
        List<EntrySurvey> entries = new ArrayList<>(files.size());
        for (Path file : files) {
            entries.add(Files.isDirectory(file) ? directory(file) : jar(file));
        }
        SortedSet<String> serviceTypes = new TreeSet<>();
        SortedSet<String> micronautTypes = new TreeSet<>();
        for (EntrySurvey entry : entries) {
            serviceTypes.addAll(entry.serviceTypes);
            micronautTypes.addAll(entry.micronautTypes);
        }
        // micronaut-core reads its loaders with java.util.ServiceLoader, never from an index, and this step rewrites
        // that file: indexing it would describe the file as it was.
        serviceTypes.remove(LOADER_TYPE);
        SortedSet<String> types = new TreeSet<>(serviceTypes);
        types.addAll(micronautTypes);
        types.addAll(ServiceIndexGenerator.ALWAYS_INDEXED);
        types.remove(LOADER_TYPE);
        EntrySurvey holderSurvey = entries.get(holder);
        return new ClassPathSurvey(types, serviceTypes.isEmpty() && micronautTypes.isEmpty(),
                holderSurvey.loaderService, new TreeSet<>(holderSurvey.generated), standDown(files, entries, holder),
                failure(files, entries, holder));
    }

    /**
     * @return the service types to ask the builder for, sorted: every type that has a {@code META-INF/services}
     *         file or a {@code META-INF/micronaut} directory, and the ones micronaut-core looks up at every start
     */
    SortedSet<String> types() {
        return types;
    }

    /**
     * @return whether the class path has neither a {@code META-INF/services} file, besides the loaders', nor a
     *         {@code META-INF/micronaut} directory
     */
    boolean nothingToIndex() {
        return nothingToIndex;
    }

    /**
     * @return the entries of the generated package that the holder already has, the output of an earlier generation
     */
    SortedSet<String> earlierOutput() {
        return earlierOutput;
    }

    /**
     * @return whether the holder already holds or registers an index loader
     */
    boolean replacesAnIndex() {
        return earlierOutput.contains(LOADER_ENTRY) || registersAnIndex();
    }

    /**
     * @return whether the holder's loader service file names an index loader
     */
    boolean registersAnIndex() {
        return loaderService != null && loaderService.lines().anyMatch(line -> LOADER_CLASS.equals(provider(line)));
    }

    /**
     * The holder's new loader service file: the generated loader first, so that the other loaders already find the
     * index when they look a service up, then every line the holder had, as it was, except an earlier index loader's.
     *
     * @return the file's content
     */
    byte[] loaderService() {
        return (LOADER_CLASS + "\n" + otherLoaderLines()).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The holder's loader service file once an earlier index is removed from it: {@link #loaderService()} without
     * the generated loader.
     *
     * @return the file's content, empty if it names no other loader
     */
    byte[] cleanedLoaderService() {
        String lines = otherLoaderLines();
        return lines.lines().allMatch(line -> provider(line).isEmpty()) ? new byte[0]
                : lines.getBytes(StandardCharsets.UTF_8);
    }

    /** Every line of the holder's loader service file, as it was, except an earlier index loader's. */
    private String otherLoaderLines() {
        StringBuilder text = new StringBuilder();
        if (loaderService != null) {
            loaderService.lines().filter(line -> !LOADER_CLASS.equals(provider(line)))
                    .forEach(line -> text.append(line).append('\n'));
        }
        return text.toString();
    }

    /**
     * @return why an index cannot describe the class path, or {@code null}
     */
    @Nullable String standDown() {
        return standDown;
    }

    /**
     * @return what is wrong with the class path, or {@code null}
     */
    @Nullable String failure() {
        return failure;
    }

    private static @Nullable String standDown(List<Path> files, List<EntrySurvey> entries, int holder)
            throws IOException {
        if (entries.get(holder).signed) {
            return name(files.get(holder)) + " is signed, and the entries of the index would break its signature";
        }
        String outside = manifestClassPathOutside(files, entries);
        return outside != null ? outside : otherIndex(files, entries, holder);
    }

    /**
     * The isolated class loader follows a manifest Class-Path as the JVM does, so a file it names on this machine
     * would be indexed although it is not the class path the application runs with.
     */
    private static @Nullable String manifestClassPathOutside(List<Path> files, List<EntrySurvey> entries)
            throws IOException {
        Set<Path> classPath = new HashSet<>();
        for (Path file : files) {
            classPath.add(file.toRealPath());
        }
        for (int i = 0; i < entries.size(); i++) {
            for (String name : entries.get(i).manifestClassPath) {
                Path named = resolve(files.get(i), name);
                if (named != null && Files.exists(named) && !classPath.contains(named.toRealPath())) {
                    return "the Class-Path of the manifest of " + name(files.get(i)) + " names " + name
                            + ", which is not on the class path given";
                }
            }
        }
        return null;
    }

    /** An index loader that an entry other than the holder holds or registers: a JVM can register one index. */
    private static @Nullable String otherIndex(List<Path> files, List<EntrySurvey> entries, int holder) {
        for (int i = 0; i < entries.size(); i++) {
            if (i != holder) {
                EntrySurvey entry = entries.get(i);
                if (entry.generated.contains(LOADER_ENTRY)) {
                    return name(files.get(i)) + " already holds a service index loader (" + LOADER_ENTRY
                            + "), and a JVM can register only one";
                }
                if (entry.loaderService != null
                        && entry.loaderService.lines().anyMatch(line -> LOADER_CLASS.equals(provider(line)))) {
                    return name(files.get(i)) + " already registers a service index loader (" + LOADER_CLASS
                            + "), and a JVM can register only one";
                }
            }
        }
        return null;
    }

    private static @Nullable String failure(List<Path> files, List<EntrySurvey> entries, int holder) {
        for (int i = 0; i < entries.size(); i++) {
            if (i != holder && !entries.get(i).generated.isEmpty()) {
                return name(files.get(i)) + " holds " + entries.get(i).generated.get(0) + ", in the package of the"
                        + " generated index";
            }
        }
        return null;
    }

    /**
     * Resolves a name of a manifest {@code Class-Path} the way the JVM does: as a URL relative to the jar.
     *
     * @return the file it names, or {@code null} if it names none
     */
    private static @Nullable Path resolve(Path jar, String name) {
        try {
            URI uri = jar.toAbsolutePath().toUri().resolve(name);
            return "file".equals(uri.getScheme()) ? Path.of(uri) : null;
        } catch (IllegalArgumentException e) {
            // not a URL, which the JVM ignores too
            return null;
        }
    }

    private static String name(Path file) {
        Path name = file.getFileName();
        return name == null ? file.toString() : name.toString();
    }

    /** The provider a line of a service file names, without its comment and white space. */
    private static String provider(String line) {
        int comment = line.indexOf('#');
        return (comment < 0 ? line : line.substring(0, comment)).strip();
    }

    private static EntrySurvey jar(Path file) throws IOException {
        EntrySurvey survey = new EntrySurvey();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    survey.directory(name);
                } else {
                    survey.file(name);
                    if (name.equals(LOADER_SERVICE)) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            survey.loaderService = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest != null) {
                try (InputStream in = zip.getInputStream(manifest)) {
                    String classPath = new Manifest(in).getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                    if (classPath != null) {
                        survey.manifestClassPath.addAll(List.of(classPath.strip().split("\\s+")));
                        survey.manifestClassPath.remove("");
                    }
                }
            }
        }
        return survey;
    }

    private static EntrySurvey directory(Path root) throws IOException {
        EntrySurvey survey = new EntrySurvey();
        for (Path file : children(root.resolve(SERVICES))) {
            if (Files.isRegularFile(file)) {
                survey.file(SERVICES + file.getFileName());
            }
        }
        // As the scan of a directory does, every directory below META-INF/micronaut/ is a type.
        for (Path type : children(root.resolve(MICRONAUT))) {
            if (Files.isDirectory(type)) {
                survey.directory(MICRONAUT + type.getFileName() + "/");
            }
        }
        Path generated = root.resolve(GENERATED_PATH);
        if (Files.isDirectory(generated)) {
            try (Stream<Path> files = Files.walk(generated)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    survey.file(root.relativize(file).toString().replace(File.separatorChar, '/'));
                }
            }
        }
        Path loaderService = root.resolve(LOADER_SERVICE);
        if (Files.isRegularFile(loaderService)) {
            survey.loaderService = Files.readString(loaderService, StandardCharsets.UTF_8);
        }
        return survey;
    }

    private static List<Path> children(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
            List<Path> sorted = new ArrayList<>();
            children.forEach(sorted::add);
            sorted.sort(null);
            return sorted;
        }
    }

    /** What one entry of the class path holds. */
    private static final class EntrySurvey {
        final Set<String> serviceTypes = new TreeSet<>();
        final Set<String> micronautTypes = new TreeSet<>();
        final List<String> generated = new ArrayList<>();
        final List<String> manifestClassPath = new ArrayList<>();
        @Nullable String loaderService;
        boolean signed;

        /**
         * Records the name of a directory entry, which counts for {@code META-INF/micronaut} only.
         *
         * @param name the name, with {@code /} separators and a trailing one
         */
        void directory(String name) {
            if (name.startsWith(MICRONAUT)) {
                file(name);
            }
        }

        /**
         * Records the name of a file entry.
         *
         * @param name the name, with {@code /} separators
         */
        void file(String name) {
            if (name.startsWith(MICRONAUT)) {
                int slash = name.indexOf('/', MICRONAUT.length());
                if (slash > MICRONAUT.length()) {
                    micronautTypes.add(name.substring(MICRONAUT.length(), slash));
                }
            } else if (name.startsWith(SERVICES)) {
                type(name.substring(SERVICES.length()));
            } else if (name.startsWith(GENERATED_PATH)) {
                generated.add(name);
            } else if (SIGNATURE.matcher(name).matches()) {
                signed = true;
            }
        }

        private void type(String type) {
            if (!type.isEmpty() && type.indexOf('/') < 0 && !type.startsWith(".")) {
                serviceTypes.add(type);
            }
        }
    }
}
