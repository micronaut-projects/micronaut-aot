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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassPathTransform} as a build plugin calls it: what a request must name, which entries of a class path
 * are rewritten and how their copies look, the libraries that make stripping stand down, and the determinism of
 * the output.
 */
class ClassPathTransformTest {

    private static final String LIBRARY_SOURCE = """
            package fixture.library;

            public class Library {
                public static int twice(int value) {
                    int doubled = value * 2;
                    if (doubled > 100) {
                        return 100;
                    }
                    return doubled;
                }
            }
            """;

    private static final String LIBRARY_ENTRY = "fixture/library/Library.class";

    @TempDir
    static Path temp;

    private static Path application;
    private static Map<String, byte[]> library;

    @BeforeAll
    static void compileTheFixtures() throws IOException {
        application = ClassFixtures.compile(temp.resolve("app/src"), temp.resolve("app/classes"),
                List.of("-g", "--release", "25"), ClassFixtures.source("app.Main", """
                        package app;
                        public class Main {
                            public static void main(String[] args) {
                                int unused = args.length;
                            }
                        }
                        """));
        library = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("library/src"),
                temp.resolve("library/classes"), List.of("-g", "--release", "17"),
                ClassFixtures.source("fixture.library.Library", LIBRARY_SOURCE)));
    }

    @Test
    void aRequestEnablesAtLeastOneStep() throws IOException {
        Path jar = ClassFixtures.jar(temp.resolve("switch/library.jar"), library);
        Path output = temp.resolve("switch/out");

        IllegalStateException none = assertThrows(IllegalStateException.class, () -> ClassPathTransform.Request
                .builder().classPath(List.of(application, jar)).outputDirectory(output).build());
        assertTrue(none.getMessage().contains("No step is enabled"), none::getMessage);
        assertThrows(IllegalStateException.class, () -> ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar)).outputDirectory(output).stripLocalVariables(List.of()).build());
        assertThrows(IllegalStateException.class, () -> ClassPathTransform.Request.builder()
                .outputDirectory(output).stripLocalVariables(List.of(jar)).build());
        assertThrows(IllegalStateException.class, () -> ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar)).stripLocalVariables(List.of(jar)).build());
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder().parallelism(0));

        IllegalArgumentException notOnTheClassPath = assertThrows(IllegalArgumentException.class,
                () -> ClassPathTransform.Request.builder().classPath(List.of(application)).outputDirectory(output)
                        .stripLocalVariables(List.of(jar)).build());
        assertTrue(notOnTheClassPath.getMessage().contains("is not an entry of the class path"),
                notOnTheClassPath::getMessage);
        IllegalArgumentException directory = assertThrows(IllegalArgumentException.class,
                () -> ClassPathTransform.Request.builder().classPath(List.of(application, jar))
                        .outputDirectory(output).stripLocalVariables(List.of(application)).build());
        assertTrue(directory.getMessage().contains("A directory is never stripped"), directory::getMessage);

        // Desugaring alone is a step; so is stripping alone, and the jar may be named by another spelling of its path.
        ClassPathTransform.Request.builder().classPath(List.of(application, jar)).outputDirectory(output)
                .desugarLambdas(true).build();
        ClassPathTransform.Request.Builder noStep = ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar)).outputDirectory(output).desugarLambdas(false);
        assertThrows(IllegalStateException.class, noStep::build);
        Path spelled = jar.getParent().resolve("../switch/./library.jar");
        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar)).outputDirectory(output).stripLocalVariables(List.of(spelled))
                .build());
        assertEquals(List.of(application, output.resolve("1/library.jar")), result.classPath());
        assertEquals(List.of(), result.warnings());
    }

    /**
     * The class path of the acceptance criteria: the application's own classes, a third-party jar compiled with
     * {@code -g} and the same jar signed, a jar with {@code module-info} and a multi-release jar, a jar whose only
     * class carries an attribute the JDK does not know, and a project module that is on the class path but not named.
     */
    @Test
    void onlyTheNamedJarsLoseTheirTablesAndEveryOtherEntryKeepsItsBytes() throws Exception {
        Path directory = temp.resolve("output");
        Map<String, byte[]> withResources = new LinkedHashMap<>();
        withResources.put("fixture/", new byte[0]);
        withResources.put("fixture/library/", new byte[0]);
        withResources.put(LIBRARY_ENTRY, library.get(LIBRARY_ENTRY));
        withResources.put("fixture/library/data.txt", ClassFixtures.utf8("some data\n"));
        withResources.put("fixture/library/stored.txt", ClassFixtures.utf8("stored data\n"));
        Path thirdParty = ClassFixtures.jar(directory.resolve("libs/library.jar"), manifest(false), withResources,
                Set.of(LIBRARY_ENTRY, "fixture/library/stored.txt"));
        Map<String, byte[]> signedEntries = new LinkedHashMap<>(library);
        signedEntries.put("META-INF/SIGNER.SF", ClassFixtures.utf8("Signature-Version: 1.0\n"));
        signedEntries.put("META-INF/SIGNER.RSA", new byte[] {1, 2, 3});
        Path signed = ClassFixtures.jar(directory.resolve("libs/signed.jar"), signedEntries);
        Map<String, byte[]> modular = new LinkedHashMap<>(ClassFixtures.classes(ClassFixtures.compile(
                directory.resolve("modular/src"), directory.resolve("modular/classes"), List.of("-g", "--release", "25"),
                Map.of("module-info.java", "module fixture.modular { exports fixture.modular; }\n",
                        "fixture/modular/Api.java", """
                                package fixture.modular;
                                public class Api {
                                    public static String greet(String name) {
                                        String greeting = "Hello " + name;
                                        return greeting;
                                    }
                                }
                                """))));
        Path moduleJar = ClassFixtures.jar(directory.resolve("libs/modular.jar"), modular);
        Map<String, byte[]> multiRelease = new LinkedHashMap<>();
        multiRelease.put(LIBRARY_ENTRY, library.get(LIBRARY_ENTRY));
        multiRelease.put("META-INF/versions/11/" + LIBRARY_ENTRY, library.get(LIBRARY_ENTRY));
        Path multiReleaseJar = ClassFixtures.jar(directory.resolve("libs/multi-release.jar"), manifest(true),
                multiRelease);
        byte[] vendorClass = ClassFixtures.withUnknownAttribute(library.get(LIBRARY_ENTRY));
        Path vendor = ClassFixtures.jar(directory.resolve("libs/vendor.jar"), Map.of(LIBRARY_ENTRY, vendorClass));
        Path projectModule = ClassFixtures.jar(directory.resolve("libs/project-module.jar"), library);
        List<Path> classPath = List.of(application, thirdParty, signed, moduleJar, multiReleaseJar, vendor,
                projectModule);
        Map<Path, byte[]> before = new LinkedHashMap<>();
        for (Path entry : classPath) {
            if (Files.isRegularFile(entry)) {
                before.put(entry, Files.readAllBytes(entry));
            }
        }
        Path output = directory.resolve("out");

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath)
                .outputDirectory(output)
                .stripLocalVariables(List.of(thirdParty, signed, moduleJar, multiReleaseJar, vendor))
                .build());

        assertEquals(List.of(application, output.resolve("1/library.jar"), signed,
                output.resolve("3/modular.jar"), output.resolve("4/multi-release.jar"), vendor, projectModule),
                result.classPath(), "exactly the jars that changed are replaced, each by a copy of the same name");
        assertEquals(List.of(output.resolve("1/library.jar"), output.resolve("3/modular.jar"),
                output.resolve("4/multi-release.jar")), written(output), "nothing else is written");
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()), entry.getKey() + " is not modified");
        }
        assertTrue(hasLocalVariables(Files.readAllBytes(application.resolve("app/Main.class"))),
                "the application's classes are left alone");

        assertCopy(thirdParty, result.classPath().get(1), Set.of(LIBRARY_ENTRY));
        assertCopy(moduleJar, result.classPath().get(3), Set.of("fixture/modular/Api.class"));
        assertCopy(multiReleaseJar, result.classPath().get(4),
                Set.of(LIBRARY_ENTRY, "META-INF/versions/11/" + LIBRARY_ENTRY));

        assertEquals(List.of(), result.warnings());
        assertEquals("Stripped local-variable tables from 4 of 7 dependency classes in 5 jars ("
                + saved(result) + " bytes saved, 0 fallbacks)", result.summary());
        assertEquals(List.of(thirdParty, signed, moduleJar, multiReleaseJar, vendor),
                result.entries().stream().map(ClassPathTransform.Result.Entry::path).toList(),
                "one entry per named jar, in class-path order, as the request spelled it");
        assertEquals(List.of(Optional.empty(), Optional.of("the jar is signed"), Optional.empty(), Optional.empty(),
                Optional.empty()), result.entries().stream().map(ClassPathTransform.Result.Entry::kept).toList());
        assertCounts(1, 0, 0, result.entries().get(0));
        assertCounts(0, 1, 0, result.entries().get(1));
        assertCounts(1, 1, 0, result.entries().get(2), "module-info is left alone");
        assertCounts(2, 0, 0, result.entries().get(3));
        assertCounts(0, 1, 0, result.entries().get(4), "an unknown attribute declines the class");
        assertEquals(0, result.entries().get(1).bytesSaved() + result.entries().get(4).bytesSaved());
        for (ClassPathTransform.Result.Entry entry : result.entries()) {
            assertEquals(List.of(), entry.notes(), entry::toString);
            assertEquals(entry.classesStripped() > 0, entry.bytesSaved() > 0, entry::toString);
        }
    }

    @Test
    void theOutputDirectoryMustNotHoldAnEntryOfTheClassPath() throws Exception {
        Path jar = ClassFixtures.jar(temp.resolve("rerun/library.jar"), library);
        Path output = temp.resolve("rerun/out");
        ClassPathTransform.Result first = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar))
                .outputDirectory(output)
                .stripLocalVariables(List.of(jar))
                .build());
        Path copy = first.classPath().get(1);
        assertEquals(output.resolve("1/library.jar"), copy);
        byte[] written = Files.readAllBytes(copy);

        // A run into the same directory would write its copy over the jar it reads.
        IllegalArgumentException rerun = assertThrows(IllegalArgumentException.class,
                () -> ClassPathTransform.Request.builder()
                        .classPath(first.classPath())
                        .outputDirectory(output)
                        .stripLocalVariables(List.of(copy))
                        .build());
        assertEquals("The class path entry " + copy + " is inside the output directory " + output,
                rerun.getMessage());
        // An entry that is only read is rejected too, as is the output directory itself, and another spelling of it.
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar, copy)).outputDirectory(output)
                .stripLocalVariables(List.of(jar)).build());
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                .classPath(List.of(output, jar)).outputDirectory(output).stripLocalVariables(List.of(jar)).build());
        Path link = temp.resolve("rerun/link");
        try {
            Files.createSymbolicLink(link, output);
        } catch (UnsupportedOperationException | IOException e) {
            link = null;
        }
        if (link != null) {
            Path linked = link;
            assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                    .classPath(List.of(application, jar, linked.resolve("1/library.jar"))).outputDirectory(output)
                    .stripLocalVariables(List.of(jar)).build());
            assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                    .classPath(List.of(application, jar, copy)).outputDirectory(linked)
                    .stripLocalVariables(List.of(jar)).build());
        }

        assertArrayEquals(written, Files.readAllBytes(copy), "the first run's copy is left as it was");
        assertEquals(List.of(copy), written(output));
    }

    @Test
    void theOutputDirectoryMustNotBeInsideADirectoryOfTheClassPathEvenWhenOnlyJarsAreStripped() throws Exception {
        Path jar = ClassFixtures.jar(temp.resolve("inside/library.jar"), library);
        Path output = application.resolve("out");

        // Stripping copies no directory, but the copy of the jar would be packaged with the application's classes.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ClassPathTransform.Request.builder()
                        .classPath(List.of(application, jar))
                        .outputDirectory(output)
                        .stripLocalVariables(List.of(jar))
                        .build());
        assertEquals("The output directory " + output + " is inside the class path directory " + application
                + ": what a run writes there would become part of that directory, so choose an output directory"
                + " outside it", failure.getMessage());
        assertFalse(Files.exists(output));
    }

    @Test
    void twoJarsWithTheSameFileNameGetSeparateCopiesAndARunOverTheOutputRewritesNothing() throws Exception {
        Path first = ClassFixtures.jar(temp.resolve("same-name/a/library.jar"), library);
        Path second = ClassFixtures.jar(temp.resolve("same-name/b/library.jar"), library);
        Path output = temp.resolve("same-name/out");

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, first, second))
                .outputDirectory(output)
                .stripLocalVariables(List.of(first, second))
                .build());

        assertEquals(List.of(application, output.resolve("1/library.jar"), output.resolve("2/library.jar")),
                result.classPath());
        assertEquals(-1, Files.mismatch(result.classPath().get(1), result.classPath().get(2)));

        Path again = temp.resolve("same-name/again");
        ClassPathTransform.Result rerun = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(result.classPath())
                .outputDirectory(again)
                .stripLocalVariables(result.classPath().subList(1, 3))
                .build());
        assertEquals(result.classPath(), rerun.classPath(), "nothing is left to strip");
        assertEquals(List.of(), written(again));
    }

    @Test
    void aCopyKeepsTheCommentsOfTheArchiveAndOfItsEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(LIBRARY_ENTRY, library.get(LIBRARY_ENTRY));
        entries.put("fixture/library/data.txt", ClassFixtures.utf8("some data\n"));
        Path jar = commented(temp.resolve("comments/library.jar"), entries, "an archive comment");

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar))
                .outputDirectory(temp.resolve("comments/out"))
                .stripLocalVariables(List.of(jar))
                .build());

        assertCopy(jar, result.classPath().get(1), Set.of(LIBRARY_ENTRY));
        try (ZipFile zip = new ZipFile(result.classPath().get(1).toFile())) {
            assertEquals("an archive comment", zip.getComment());
            assertEquals("about " + LIBRARY_ENTRY, zip.getEntry(LIBRARY_ENTRY).getComment());
        }
    }

    @Test
    void aJarThatHoldsAnEntryNameTwiceIsLeftAsItIs() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(LIBRARY_ENTRY, library.get(LIBRARY_ENTRY));
        entries.put(LIBRARY_ENTRY.replace("Library.class", "Librarz.class"), library.get(LIBRARY_ENTRY));
        Path jar = ClassFixtures.jar(temp.resolve("repeated/library.jar"), entries);
        // The second entry takes the first one's name, which has the same length, in its local header and in the
        // central directory.
        String bytes = new String(Files.readAllBytes(jar), StandardCharsets.ISO_8859_1);
        Files.write(jar, bytes.replace("Librarz.class", "Library.class").getBytes(StandardCharsets.ISO_8859_1));
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            assertEquals(2, Collections.list(zip.entries()).stream()
                    .filter(entry -> entry.getName().equals(LIBRARY_ENTRY)).count(), "the fixture repeats a name");
        }

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar))
                .outputDirectory(temp.resolve("repeated/out"))
                .stripLocalVariables(List.of(jar))
                .build());

        assertEquals(List.of(application, jar), result.classPath());
        assertEquals(1, result.entries().size(), result.entries()::toString);
        assertEquals(Optional.of("the jar holds the entry " + LIBRARY_ENTRY + " more than once"),
                result.entries().get(0).kept());
        assertCounts(0, 2, 0, result.entries().get(0));
        assertEquals(List.of(), written(temp.resolve("repeated/out")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"com/thoughtworks/paranamer/BytecodeReadingParanamer.class",
        "org/aspectj/weaver/World.class", "org/springframework/core/LocalVariableTableParameterNameDiscoverer.class"})
    void aKnownLocalVariableTableReaderMakesTheStepStripNothingWithOneWarning(String reader) throws Exception {
        String name = reader.substring(reader.lastIndexOf('/') + 1, reader.length() - ".class".length());
        Path directory = temp.resolve("reader-" + name);
        Path jar = ClassFixtures.jar(directory.resolve("library.jar"), library);
        Path readerJar = ClassFixtures.jar(directory.resolve("reader.jar"), Map.of(reader, new byte[0]));
        Path output = directory.resolve("out");

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(application, jar, readerJar))
                .outputDirectory(output)
                .stripLocalVariables(List.of(jar))
                .build());

        assertEquals(List.of(application, jar, readerJar), result.classPath(), "nothing is stripped");
        assertEquals(List.of(), written(output), "nothing is written");
        assertEquals(1, result.warnings().size(), result.warnings()::toString);
        assertTrue(result.warnings().get(0).contains(readerJar.toString()), result.warnings()::toString);
        assertTrue(result.warnings().get(0).contains(reader), result.warnings()::toString);
        assertTrue(result.summary().startsWith("Stripped no local-variable table"), result::summary);
        assertEquals(List.of(), result.entries());
    }

    @Test
    void aReaderInTheApplicationsOwnClassesCountsToo() throws Exception {
        Path classes = temp.resolve("reader-application/classes");
        Path reader = classes.resolve("org/aspectj/weaver/World.class");
        Files.createDirectories(reader.getParent());
        Files.write(reader, new byte[0]);
        Path jar = ClassFixtures.jar(temp.resolve("reader-application/library.jar"), library);

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(classes, jar))
                .outputDirectory(temp.resolve("reader-application/out"))
                .stripLocalVariables(List.of(jar))
                .build());

        assertEquals(List.of(classes, jar), result.classPath());
        assertEquals(1, result.warnings().size(), result.warnings()::toString);
        assertTrue(result.warnings().get(0).contains(classes.toString()), result.warnings()::toString);
    }

    @Test
    void twoRunsWriteTheSameBytesWhateverTheParallelism() throws Exception {
        List<Path> jars = new ArrayList<>();
        for (int release : new int[] {8, 11, 17, 21, 25}) {
            Map<String, byte[]> classes = ClassFixtures.classes(ClassFixtures.compile(
                    temp.resolve("determinism/src-" + release), temp.resolve("determinism/classes-" + release),
                    List.of("-g", "--release", Integer.toString(release)),
                    ClassFixtures.source("fixture.library.Library", LIBRARY_SOURCE)));
            jars.add(ClassFixtures.jar(temp.resolve("determinism/library-" + release + ".jar"), classes));
        }
        List<Path> classPath = new ArrayList<>();
        classPath.add(application);
        classPath.addAll(jars);
        // A library with lambdas, at the release that needs bridges, for both steps to rewrite.
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("determinism/lambdas"), 8);
        Path lambdas = ClassFixtures.jar(temp.resolve("determinism/fix.jar"), layers.get(1).entries());
        classPath.add(lambdas);
        jars.add(lambdas);

        List<ClassPathTransform.Result> results = new ArrayList<>();
        for (int parallelism : new int[] {1, 8, 1, 8}) {
            results.add(ClassPathTransform.run(ClassPathTransform.Request.builder()
                    .classPath(classPath)
                    .outputDirectory(temp.resolve("determinism/out-" + results.size()))
                    .desugarLambdas(true)
                    .stripLocalVariables(jars)
                    .parallelism(parallelism)
                    .build()));
        }

        ClassPathTransform.Result.Entry desugared = results.get(0).entries().get(results.get(0).entries().size() - 1);
        assertEquals(lambdas, desugared.path());
        assertTrue(desugared.sitesRewritten() > 0 && desugared.bridges() > 0 && desugared.classesStripped() > 0,
                desugared::toString);
        for (ClassPathTransform.Result result : results.subList(1, results.size())) {
            assertEquals(results.get(0).summary(), result.summary());
            assertEquals(results.get(0).entries().toString(), result.entries().toString());
            for (int position = 1; position < classPath.size(); position++) {
                Path copy = result.classPath().get(position);
                assertNotEquals(classPath.get(position), copy);
                assertEquals(-1, Files.mismatch(results.get(0).classPath().get(position), copy), copy::toString);
            }
        }
    }

    @Test
    void aJarThatCannotBeReadFailsTheRunAndNamesTheJar() throws Exception {
        Path broken = Files.writeString(Files.createDirectories(temp.resolve("broken")).resolve("library.jar"),
                "not a zip archive");

        IOException failure = assertThrows(IOException.class, () -> ClassPathTransform.run(
                ClassPathTransform.Request.builder()
                        .classPath(List.of(application, broken))
                        .outputDirectory(temp.resolve("broken/out"))
                        .stripLocalVariables(List.of(broken))
                        .build()));

        assertTrue(failure.getMessage().contains(broken.toString()), failure::getMessage);
    }

    /**
     * A copy holds the original's entries in the same order, with the same names, times, comments and methods, the
     * same manifest and archive comment, and the same bytes for every entry but the classes that were stripped,
     * which lost their local-variable tables.
     */
    private static void assertCopy(Path original, Path copy, Set<String> stripped) throws IOException {
        assertEquals(original.getFileName(), copy.getFileName());
        try (ZipFile before = new ZipFile(original.toFile()); ZipFile after = new ZipFile(copy.toFile())) {
            assertEquals(before.getComment(), after.getComment());
            List<? extends ZipEntry> expected = Collections.list(before.entries());
            List<? extends ZipEntry> actual = Collections.list(after.entries());
            assertEquals(expected.stream().map(ZipEntry::getName).toList(),
                    actual.stream().map(ZipEntry::getName).toList(), "the entries and their order");
            for (int i = 0; i < expected.size(); i++) {
                ZipEntry was = expected.get(i);
                ZipEntry is = actual.get(i);
                String name = was.getName();
                assertEquals(was.getMethod(), is.getMethod(), name + " method");
                assertEquals(was.getTime(), is.getTime(), name + " time");
                assertEquals(was.getLastModifiedTime(), is.getLastModifiedTime(), name + " modification time");
                assertEquals(was.getComment(), is.getComment(), name + " comment");
                byte[] content = read(after, is);
                assertEquals(is.getSize(), content.length, name + " size");
                if (stripped.contains(name)) {
                    assertTrue(hasLocalVariables(read(before, was)), name + " as published");
                    assertFalse(hasLocalVariables(content), name + " is stripped");
                    assertTrue(content.length < was.getSize(), name + " shrank");
                } else {
                    assertArrayEquals(read(before, was), content, name + " keeps its bytes");
                }
            }
        }
    }

    /** The files below a directory, in name order; none when it does not exist. */
    static List<Path> written(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static boolean hasLocalVariables(byte[] bytes) {
        for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
            for (CodeModel code : method.code().stream().toList()) {
                for (Attribute<?> attribute : code.attributes()) {
                    if (attribute.attributeName().stringValue().startsWith("LocalVariable")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static byte[] read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static long saved(ClassPathTransform.Result result) {
        return result.entries().stream().mapToLong(ClassPathTransform.Result.Entry::bytesSaved).sum();
    }

    /** The counts of the strip step on one jar. */
    static void assertCounts(int stripped, int unchanged, int fallbacks, ClassPathTransform.Result.Entry entry) {
        assertCounts(stripped, unchanged, fallbacks, entry, "");
    }

    static void assertCounts(int stripped, int unchanged, int fallbacks, ClassPathTransform.Result.Entry entry,
                             String message) {
        assertEquals(List.of(stripped, unchanged, fallbacks),
                List.of(entry.classesStripped(), entry.classesUnchanged(), entry.fallbacks()), message + " " + entry);
    }

    private static Manifest manifest(boolean multiRelease) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Title", "fixture");
        if (multiRelease) {
            manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        }
        return manifest;
    }

    /** A jar with an archive comment, which a copy keeps. */
    private static Path commented(Path file, Map<String, byte[]> entries, String comment) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out,
                manifest(false))) {
            jar.setComment(comment);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                record.setTime(1_000_000_000_000L);
                record.setComment("about " + entry.getKey());
                jar.putNextEntry(record);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }
}
