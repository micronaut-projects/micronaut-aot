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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the tests build applications from, and how they start them.
 *
 * <p>The class paths come from the build as system properties, and are never on the test class path: micronaut-core
 * with {@code ServiceIndexBuilder} and slf4j-api ({@value #CORE}), slf4j-simple ({@value #LOGGING}), a micronaut-core
 * without the builder ({@value #LEGACY_CORE}) and the classes of the probe ({@value #PROBE}).</p>
 */
final class Fixtures {

    static final String CORE = "aot.serviceindex.core";
    static final String LOGGING = "aot.serviceindex.logging";
    static final String LEGACY_CORE = "aot.serviceindex.legacyCore";
    static final String PROBE = "aot.serviceindex.probe";

    static final String PROBE_CLASS = "io.micronaut.aot.serviceindex.probe.ServiceIndexProbe";
    static final String LAUNCHER_CLASS = "io.micronaut.aot.serviceindex.probe.IsolatedLauncher";

    static final String BEAN_DEFINITIONS = "io.micronaut.inject.BeanDefinitionReference";
    static final String INTROSPECTIONS = "io.micronaut.core.beans.BeanIntrospectionReference";
    static final String SLF4J_PROVIDER = "org.slf4j.spi.SLF4JServiceProvider";

    static final String VALIDATE = "-Dmicronaut.service.index.validate=true";
    static final String USING = "INFO io.micronaut.core.io.service.ServiceIndex - Using the service index";
    static final String NOT_USING = "WARN io.micronaut.core.io.service.ServiceIndex - Not using the service index";

    static final String GREETING = "fixture.Greeting";
    static final String FAREWELL = "fixture.Farewell";
    static final String CONFIGURER = "io.micronaut.context.ApplicationContextConfigurer";

    /** The lookups of the probe for {@link #application()} and {@link #library()}. */
    static final List<String> LOOKUPS = List.of("services:" + GREETING, "services:" + FAREWELL, "services:" + CONFIGURER,
            "micronaut:" + BEAN_DEFINITIONS, "micronaut:" + INTROSPECTIONS);

    /** What the probe finds with {@link #LOOKUPS} on a class path of {@link #application()} alone. */
    static final String APPLICATION_LOOKUPS = """
            services:fixture.Greeting [fixture.Hello, fixture.Hi]
            services:fixture.Farewell [fixture.Bye]
            services:io.micronaut.context.ApplicationContextConfigurer []
            micronaut:io.micronaut.inject.BeanDefinitionReference [fixture.$Hello$Definition$Reference, fixture.$Hi$Definition$Reference]
            micronaut:io.micronaut.core.beans.BeanIntrospectionReference [fixture.$Book$Introspection]
            """;

    /**
     * What core's {@code INFO} line counts for {@link #application()}: 3 entries of 2 {@code META-INF/micronaut}
     * types, and 7 types of {@code META-INF/services}: the 2 of the application, slf4j-simple's provider, the 2
     * {@code META-INF/micronaut} types and the 2 that micronaut-core looks up at every start.
     */
    static final String APPLICATION_COUNTS =
            "META-INF/micronaut (3 entries of 2 service types) and the META-INF/services files of 7 service types are not scanned";

    private static final LocalDateTime TIME = LocalDateTime.of(2000, 1, 1, 0, 0);

    private Fixtures() {
    }

    /** An application's own service files and {@code META-INF/micronaut} entries. */
    static Content application() {
        return content()
                .services(GREETING, "fixture.Hello", "fixture.Hi")
                .services(FAREWELL, "fixture.Bye")
                .micronaut(BEAN_DEFINITIONS, "fixture.$Hello$Definition$Reference", "fixture.$Hi$Definition$Reference")
                .micronaut(INTROSPECTIONS, "fixture.$Book$Introspection");
    }

    /** A library with a service of the application's type and a bean definition. */
    static Content library() {
        return content()
                .services(GREETING, "fixture.LibraryGreeting")
                .micronaut(BEAN_DEFINITIONS, "fixture.$Library$Definition$Reference");
    }

    // ------------------------------------------------------------------ class paths of the build

    /** micronaut-core with ServiceIndexBuilder, then its dependencies. */
    static List<Path> core() {
        return paths(CORE);
    }

    /** The micronaut-core jar of {@link #core()}. */
    static Path coreJar() {
        return core().stream().filter(path -> path.getFileName().toString().startsWith("micronaut-core"))
                .findFirst().orElseThrow();
    }

    static List<Path> logging() {
        return paths(LOGGING);
    }

    static Path legacyCore() {
        return paths(LEGACY_CORE).get(0);
    }

    /** micronaut-core, slf4j-api and slf4j-simple: the libraries of every test application. */
    static List<Path> libraries() {
        List<Path> libraries = new ArrayList<>(core());
        libraries.addAll(logging());
        return libraries;
    }

    private static List<Path> paths(String property) {
        String value = System.getProperty(property);
        assertTrue(value != null && !value.isEmpty(), "the build passes " + property);
        return Arrays.stream(value.split(File.pathSeparator)).map(Path::of).toList();
    }

    // ------------------------------------------------------------------ content

    /** The entries of a jar or a directory, in order. */
    static final class Content {

        final Map<String, byte[]> entries = new LinkedHashMap<>();

        Content put(String name, byte[] bytes) {
            entries.put(name, bytes);
            return this;
        }

        Content put(String name, String text) {
            return put(name, text.getBytes(StandardCharsets.UTF_8));
        }

        /** A {@code META-INF/services} file. */
        Content services(String type, String... names) {
            return put("META-INF/services/" + type, String.join("\n", names) + "\n");
        }

        /** Empty {@code META-INF/micronaut} entries, as Micronaut's annotation processors write them. */
        Content micronaut(String type, Collection<String> names) {
            for (String name : names) {
                put("META-INF/micronaut/" + type + "/" + name, new byte[0]);
            }
            return this;
        }

        Content micronaut(String type, String... names) {
            return micronaut(type, List.of(names));
        }

        /** A class file, by binary name. */
        Content type(String binaryName, byte[] bytes) {
            return put(binaryName.replace('.', '/') + ".class", bytes);
        }

        /** The classes of the probe. */
        Content probe() throws IOException {
            for (Path root : paths(PROBE)) {
                if (Files.isDirectory(root)) {
                    try (Stream<Path> files = Files.walk(root)) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            put(root.relativize(file).toString().replace(File.separatorChar, '/'), Files.readAllBytes(file));
                        }
                    }
                }
            }
            return this;
        }

        /** A manifest with these main attributes, as the first entry. */
        Content manifest(Map<String, String> attributes) throws IOException {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            manifest.write(bytes);
            Map<String, byte[]> rest = new LinkedHashMap<>(entries);
            rest.remove("META-INF/MANIFEST.MF");
            entries.clear();
            entries.put("META-INF/MANIFEST.MF", bytes.toByteArray());
            entries.putAll(rest);
            return this;
        }

        /**
         * Adds the entries of jars, as Shadow merges them: the {@code META-INF/services} files are concatenated, the
         * first of any other name wins, and manifests, signatures and module descriptors are left out.
         */
        Content merge(List<Path> jars) throws IOException {
            for (Path jar : jars) {
                try (ZipFile zip = new ZipFile(jar.toFile())) {
                    for (ZipEntry entry : zip.stream().toList()) {
                        String name = entry.getName();
                        if (entry.isDirectory() || name.equals("META-INF/MANIFEST.MF") || name.endsWith("module-info.class")
                                || name.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) {
                            continue;
                        }
                        byte[] bytes;
                        try (InputStream in = zip.getInputStream(entry)) {
                            bytes = in.readAllBytes();
                        }
                        if (name.startsWith("META-INF/services/") && entries.containsKey(name)) {
                            String merged = new String(entries.get(name), StandardCharsets.UTF_8).strip() + "\n"
                                    + new String(bytes, StandardCharsets.UTF_8);
                            put(name, merged);
                        } else {
                            entries.putIfAbsent(name, bytes);
                        }
                    }
                }
            }
            return this;
        }
    }

    static Content content() {
        return new Content();
    }

    /** Writes a jar, deflated. */
    static Path jar(Path file, Content content) throws IOException {
        return jar(file, content, false);
    }

    /**
     * Writes a jar, stored when asked, so that its size depends only on its names and lengths. Like the jars of Gradle
     * and Maven, it has an entry for each directory, which a class loader needs to find a directory such as
     * {@code META-INF/micronaut/}.
     */
    static Path jar(Path file, Content content, boolean stored) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path written = Files.createTempFile(file.toAbsolutePath().getParent(), "jar", ".tmp");
        Set<String> directories = new java.util.HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(written))) {
            for (Map.Entry<String, byte[]> entry : content.entries.entrySet()) {
                String name = entry.getKey();
                for (int slash = name.indexOf('/'); slash > 0 && slash < name.length() - 1; slash = name.indexOf('/', slash + 1)) {
                    String directory = name.substring(0, slash + 1);
                    if (!content.entries.containsKey(directory) && directories.add(directory)) {
                        ZipEntry directoryEntry = new ZipEntry(directory);
                        directoryEntry.setTimeLocal(TIME);
                        out.putNextEntry(directoryEntry);
                        out.closeEntry();
                    }
                }
                if (name.endsWith("/")) {
                    if (directories.add(name)) {
                        ZipEntry directoryEntry = new ZipEntry(name);
                        directoryEntry.setTimeLocal(TIME);
                        out.putNextEntry(directoryEntry);
                        out.closeEntry();
                    }
                    continue;
                }
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTimeLocal(TIME);
                if (stored) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(entry.getValue().length);
                    zipEntry.setCrc(crc.getValue());
                }
                out.putNextEntry(zipEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return Files.move(written, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Writes the entries below a directory. */
    static Path directory(Path root, Content content) throws IOException {
        Files.createDirectories(root);
        for (Map.Entry<String, byte[]> entry : content.entries.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        return root;
    }

    /** Reads every entry of a jar, in order. */
    static Content read(Path jar) throws IOException {
        Content content = content();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : zip.stream().toList()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    content.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return content;
    }

    /** A fat jar of the libraries, the probe as its main class, and the application's own entries. */
    static Path fatJar(Path file, Content application) throws IOException {
        Content fat = content().manifest(Map.of("Main-Class", PROBE_CLASS)).probe();
        fat.entries.putAll(application.entries);
        return jar(file, fat.merge(libraries()));
    }

    // ------------------------------------------------------------------ generation

    /** Generates for a single jar, listed under its own file name. */
    static ServiceIndexGenerator.Result generateFor(Path jar) {
        return ServiceIndexGenerator.generate(ServiceIndexGenerator.Request.builder()
                .entry(jar, jar.getFileName().toString()).holder(jar).build());
    }

    /** Writes a generated result into a jar or a directory, as a caller does. */
    static void apply(Path holder, ServiceIndexGenerator.Result result) throws IOException {
        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        if (Files.isDirectory(holder)) {
            for (String removal : result.removals()) {
                Files.delete(holder.resolve(removal));
            }
            directory(holder, contentOf(result.entries()));
            return;
        }
        Content content = read(holder);
        content.entries.keySet().removeAll(result.removals());
        content.entries.putAll(result.entries());
        jar(holder, content);
    }

    static Content contentOf(Map<String, byte[]> entries) {
        Content content = content();
        content.entries.putAll(entries);
        return content;
    }

    // ------------------------------------------------------------------ class files

    /** An empty public class. */
    static byte[] emptyClass(String name) {
        return ClassFile.of().build(ClassDesc.of(name), builder -> builder
                .withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_()));
    }

    /** A {@code StaticOptimizations.Loader} whose {@code load()} returns a new instance of a class. */
    static byte[] loaderClass(String name, String optimization) {
        ClassDesc value = ClassDesc.of(optimization);
        return ClassFile.of().build(ClassDesc.of(name), builder -> builder
                .withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(ClassDesc.of("io.micronaut.core.optim.StaticOptimizations$Loader"))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .return_())
                .withMethodBody("load", MethodTypeDesc.of(ConstantDescs.CD_Object), ClassFile.ACC_PUBLIC, code -> code
                        .new_(value).dup()
                        .invokespecial(value, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .areturn()));
    }

    /**
     * A class whose static initialiser and constructor each create a marker file and throw: a class that the
     * generator must neither initialise nor instantiate.
     */
    static byte[] explodingClass(String name, Path markers, ClassDesc... interfaces) {
        ClassDesc path = ClassDesc.of("java.nio.file.Path");
        ClassDesc files = ClassDesc.of("java.nio.file.Files");
        ClassDesc attribute = ClassDesc.of("java.nio.file.attribute.FileAttribute");
        ClassDesc failure = ClassDesc.of("java.lang.IllegalStateException");
        java.util.function.BiConsumer<java.lang.classfile.CodeBuilder, String> explode = (code, marker) -> code
                .ldc(markers.resolve(marker).toString())
                .iconst_0().anewarray(ConstantDescs.CD_String)
                .invokestatic(path, "of", MethodTypeDesc.of(path, ConstantDescs.CD_String,
                        ConstantDescs.CD_String.arrayType()), true)
                .iconst_0().anewarray(attribute)
                .invokestatic(files, "createFile", MethodTypeDesc.of(path, path, attribute.arrayType()))
                .pop()
                .new_(failure).dup().ldc(name + " was run by the build")
                .invokespecial(failure, ConstantDescs.INIT_NAME, MethodTypeDesc.of(ConstantDescs.CD_void,
                        ConstantDescs.CD_String))
                .athrow();
        return ClassFile.of().build(ClassDesc.of(name), builder -> builder
                .withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withInterfaceSymbols(interfaces)
                .withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_STATIC,
                        code -> explode.accept(code, name + ".clinit"))
                .withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC, code -> {
                    code.aload(0).invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void);
                    explode.accept(code, name + ".init");
                }));
    }

    // ------------------------------------------------------------------ reading an index back

    /** What an index holds, as plain collections in iteration order. */
    record Index(Map<String, List<String>> micronautServices, Map<String, List<String>> standardServices,
                 List<String> classPath) {
    }

    /**
     * Runs the generated loader in micronaut-core: in a child of a class loader of the class path, which defines the
     * generated classes itself, and returns the index it builds.
     */
    static Index load(List<Path> classPath, Map<String, byte[]> entries) throws Exception {
        try (URLClassLoader application = isolated(classPath)) {
            ClassLoader generated = new ClassLoader("generated", application) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    byte[] bytes = entries.get(name.replace('.', '/') + ".class");
                    if (bytes == null) {
                        return super.loadClass(name, resolve);
                    }
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        return loaded != null ? loaded : defineClass(name, bytes, 0, bytes.length);
                    }
                }
            };
            Object loader = generated.loadClass(ServiceIndexGenerator.LOADER_CLASS).getConstructor().newInstance();
            Object index = loader.getClass().getMethod("load").invoke(loader);
            return describe(index);
        }
    }

    /** Builds the index of a class path with micronaut-core's own builder, as micronaut-core's tests would. */
    static Index build(List<Path> classPath, Collection<String> types) throws Exception {
        try (URLClassLoader application = isolated(classPath)) {
            Class<?> builder = Class.forName("io.micronaut.core.io.service.ServiceIndexBuilder", false, application);
            Object index;
            try {
                index = builder.getMethod("build", ClassLoader.class, Collection.class).invoke(null, application, types);
            } catch (InvocationTargetException e) {
                throw (Exception) e.getCause();
            }
            return describe(index);
        }
    }

    @SuppressWarnings("unchecked")
    private static Index describe(Object index) throws ReflectiveOperationException {
        Map<String, List<String>> micronaut = new LinkedHashMap<>();
        ((Map<String, Set<String>>) index.getClass().getMethod("micronautServices").invoke(index))
                .forEach((type, names) -> micronaut.put(type, List.copyOf(names)));
        Map<String, List<String>> standard = new LinkedHashMap<>();
        ((Map<String, List<String>>) index.getClass().getMethod("standardServices").invoke(index))
                .forEach((type, names) -> standard.put(type, List.copyOf(names)));
        List<?> entries = (List<?>) index.getClass().getMethod("classPath").invoke(index);
        List<String> classPath = null;
        if (entries != null) {
            classPath = new ArrayList<>();
            for (Object entry : entries) {
                classPath.add(entry.toString());
            }
        }
        return new Index(micronaut, standard, classPath);
    }

    static URLClassLoader isolated(List<Path> classPath) throws MalformedURLException {
        URL[] urls = new URL[classPath.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = classPath.get(i).toUri().toURL();
        }
        return new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
    }

    /** Verifies class files against micronaut-core and each other, as the JVM would. */
    static List<String> verify(Map<String, byte[]> entries) throws IOException {
        List<String> errors = new ArrayList<>();
        try (URLClassLoader core = isolated(core())) {
            ClassHierarchyResolver resolver = ClassHierarchyResolver.ofResourceParsing(desc -> {
                String name = desc.descriptorString();
                String path = name.substring(1, name.length() - 1) + ".class";
                byte[] bytes = entries.get(path);
                return bytes != null ? new ByteArrayInputStream(bytes) : core.getResourceAsStream(path);
            });
            ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                if (entry.getKey().endsWith(".class")) {
                    classFile.verify(entry.getValue()).forEach(error -> errors.add(entry.getKey() + ": " + error));
                }
            }
        }
        return errors;
    }

    // ------------------------------------------------------------------ forked JVMs

    /** What a forked JVM printed. */
    record Run(int exit, String out, String err) {

        /** Fails unless the probe ran to its end, and returns its lines. */
        String lookups() {
            assertEquals(0, exit, this::toString);
            assertTrue(out.endsWith("END\n"), this::toString);
            return out.substring(0, out.length() - "END\n".length());
        }

        boolean usesTheIndex() {
            return err.contains(USING);
        }

        boolean warns() {
            return err.contains(" WARN ");
        }
    }

    /** Starts a JVM in a directory, with these arguments after {@code java}. */
    static Run java(Path directory, List<String> arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xshare:auto");
        command.addAll(arguments);
        Path out = Files.createTempFile("probe", ".out");
        Path err = Files.createTempFile("probe", ".err");
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                    .redirectOutput(out.toFile()).redirectError(err.toFile());
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            Process process = builder.start();
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("the application did not finish: " + command);
            }
            return new Run(process.exitValue(), Files.readString(out), Files.readString(err));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    /** {@code java <options> -jar <jar> <lookups>}, in the jar's directory. */
    static Run javaJar(Path jar, List<String> options, List<String> lookups) throws IOException, InterruptedException {
        List<String> arguments = new ArrayList<>(options);
        arguments.add("-jar");
        arguments.add(jar.getFileName().toString());
        arguments.addAll(lookups);
        return java(jar.toAbsolutePath().getParent(), arguments);
    }

    /** Lists the entries of every jar of a class path that are under a prefix. */
    static List<String> names(Path jar, String prefix) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(prefix)) {
                    names.add(name);
                }
            }
        }
        return names;
    }
}
