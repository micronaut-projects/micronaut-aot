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
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How {@link JarRewriter} writes a copy, with the classes desugaring generates right after their hosts, and how it
 * fails: the failure that stopped a copy is the one thrown, a failure to delete the copy is added to it as suppressed,
 * and the message says what failed even when the failure has no message of its own.
 */
class JarRewriterTest {

    private static final String FIRST = "fixture/library/First.class";

    private static final String SECOND = "fixture/library/Second.class";

    private static final String NAME = "libs/library.jar";

    @TempDir
    static Path temp;

    /** A jar whose second class, which is stored, has a byte that does not match its CRC-32. */
    private static Path corrupt;
    private static ClassPathModel model;

    @BeforeAll
    static void writeTheCorruptJar() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.putAll(ClassFixtures.source("fixture.library.First", """
                package fixture.library;
                public class First {
                    public static int twice(int value) {
                        int doubled = value * 2;
                        return doubled;
                    }
                }
                """));
        sources.putAll(ClassFixtures.source("fixture.library.Second", """
                package fixture.library;
                public class Second {
                    public static int thrice(int value) {
                        int tripled = value * 3;
                        return tripled;
                    }
                }
                """));
        Map<String, byte[]> classes = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("src"),
                temp.resolve("classes"), List.of("-g"), sources));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(FIRST, classes.get(FIRST));
        entries.put(SECOND, classes.get(SECOND));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        corrupt = ClassFixtures.jar(temp.resolve("library.jar"), manifest, entries, Set.of(SECOND));
        byte[] jar = Files.readAllBytes(corrupt);
        byte[] second = classes.get(SECOND);
        int at = indexOf(jar, second);
        assertTrue(at >= 0, "the stored class is in the jar as it is");
        jar[at + second.length / 2] ^= 0x01;
        Files.write(corrupt, jar);

        ClassPathModel.LayerScan scan = ClassPathModel.scan(NAME, false, name -> false);
        classes.forEach(scan::accept);
        model = ClassPathModel.merge(List.of(scan));
    }

    @Test
    void aCopyThatFailsIsDeletedAndItsFailureIsThrownNamingTheJar() {
        Path target = temp.resolve("deleted/1/library.jar");
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model);

        IOException failure = assertThrows(IOException.class,
                () -> JarRewriter.rewrite(corrupt, target, pipeline, NAME, 0, true));

        assertEquals("Cannot rewrite the classes of " + NAME + ": ZipException: The entry " + SECOND
                + " does not match its recorded CRC-32", failure.getMessage());
        ZipException cause = assertInstanceOf(ZipException.class, failure.getCause());
        assertArrayEquals(new Throwable[0], cause.getSuppressed());
        assertFalse(Files.exists(target.getParent()), "the copy and its directory are deleted");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the test replaces the copy while it is open")
    void aFailureToDeleteTheCopyIsAddedToTheFailureThatStoppedIt() {
        Path target = temp.resolve("held/1/library.jar");
        // When the first class is read, the copy is open; a directory that is not empty takes its place, so the
        // copy cannot be deleted once the second class fails.
        ClassTransformPipeline.Step holder = new HoldingStep(() -> {
            try {
                Files.delete(target);
                Files.createDirectories(target.resolve("held"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(holder), model);

        IOException failure = assertThrows(IOException.class,
                () -> JarRewriter.rewrite(corrupt, target, pipeline, NAME, 0, true));

        assertEquals("Cannot rewrite the classes of " + NAME + ": ZipException: The entry " + SECOND
                + " does not match its recorded CRC-32", failure.getMessage(), "the failure that stopped the copy");
        ZipException cause = assertInstanceOf(ZipException.class, failure.getCause());
        assertEquals(1, cause.getSuppressed().length, () -> List.of(cause.getSuppressed()).toString());
        assertInstanceOf(DirectoryNotEmptyException.class, cause.getSuppressed()[0]);
        assertTrue(Files.isDirectory(target.resolve("held")));
    }

    /**
     * The nest host comes last in the jar, so the jar has to be planned before it is written. Each generated class is
     * written right after its host, with its host's time and method, and every rewritten or generated entry carries the
     * size and CRC-32 of its own bytes: a {@code STORED} entry cannot be written without them.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"STORED", "DEFLATED"})
    void desugaredHostsAndTheirGeneratedClassesCarryTheCrcOfTheirBytesAndStayTogether(String method)
            throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("desugared-" + method), 25);
        Map<String, byte[]> ordered = new LinkedHashMap<>(layers.get(1).entries());
        byte[] nestHost = ordered.remove("fix/Scenario.class");
        ordered.put("fix/Scenario.class", nestHost);
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Path source = ClassFixtures.jar(temp.resolve("desugared-" + method + "/fix.jar"), manifest, ordered,
                method.equals("STORED") ? ordered.keySet() : Set.of());
        ClassPathModel scenarioModel = LambdaFixtures.model(layers);
        ClassTransformPipeline pipeline = new ClassTransformPipeline(
                List.of(new LambdaDesugarer(scenarioModel, Set.of()), new LocalVariableStripper()), scenarioModel);
        Path target = temp.resolve("desugared-" + method + "/out/1/fix.jar");

        JarRewriter.Outcome outcome = JarRewriter.rewrite(source, target, pipeline, "libs/fix.jar", 1, true);

        assertTrue(outcome.written());
        try (ZipFile before = new ZipFile(source.toFile()); ZipFile after = new ZipFile(target.toFile())) {
            List<? extends ZipEntry> original = Collections.list(before.entries());
            List<? extends ZipEntry> written = Collections.list(after.entries());
            List<String> names = new ArrayList<>();
            written.forEach(entry -> names.add(entry.getName()));
            assertEquals(original.stream().map(ZipEntry::getName).toList(),
                    names.stream().filter(name -> !name.contains(LambdaClasses.GENERATED_INFIX)).toList(),
                    "the entries the jar had keep their order");
            int generated = 0;
            for (int i = 0; i < written.size(); i++) {
                ZipEntry entry = written.get(i);
                byte[] content;
                try (InputStream in = after.getInputStream(entry)) {
                    content = in.readAllBytes();
                }
                CRC32 crc = new CRC32();
                crc.update(content);
                assertEquals(crc.getValue(), entry.getCrc(), entry.getName() + " carries the CRC of its bytes");
                assertEquals(content.length, entry.getSize(), entry.getName());
                int infix = entry.getName().indexOf(LambdaClasses.GENERATED_INFIX);
                if (infix < 0) {
                    continue;
                }
                generated++;
                String host = entry.getName().substring(0, infix);
                String previous = written.get(i - 1).getName();
                assertTrue(previous.equals(host + ".class")
                                || previous.startsWith(host + LambdaClasses.GENERATED_INFIX),
                        entry.getName() + " sits right after its host, not after " + previous);
                ZipEntry hostEntry = after.getEntry(host + ".class");
                assertEquals(hostEntry.getTime(), entry.getTime(), entry.getName() + " takes its host's time");
                assertEquals(hostEntry.getMethod(), entry.getMethod(), entry.getName() + " takes its host's method");
                assertEquals(entry.getName().substring(0, entry.getName().length() - 6),
                        ClassFile.of().parse(content).thisClass().asInternalName(),
                        "the entry holds the class it names");
            }
            ClassTransformPipeline.JarReport report = outcome.report();
            assertEquals(report.desugared().generated(), generated, "every generated class is an entry");
            assertTrue(generated > 0, report.desugared()::toString);
            assertEquals(List.of(), report.notes());
            assertNotEquals(before.getEntry("fix/Scenario.class").getCrc(), after.getEntry("fix/Scenario.class")
                    .getCrc(), "the nest host was rewritten where it is");
            try (InputStream in = after.getInputStream(after.getEntry("fix/Scenario.class"))) {
                assertEquals(0, LambdaFixtures.sites(in.readAllBytes(), LambdaFixtures.METAFACTORY));
            }
            assertEquals(original.stream().filter(entry -> entry.getName().endsWith(".class")).count(),
                    (long) report.counts().get(0).classes(), "desugaring counts each class the jar had once");
        }
    }

    @Test
    void aFailureWithoutAMessageIsNamedByItsClass() {
        assertEquals("EOFException", ClassTransformPipeline.describe(new EOFException()));
        assertEquals("ZipException: zip END header not found",
                ClassTransformPipeline.describe(new ZipException("zip END header not found")));
    }

    private static int indexOf(byte[] bytes, byte[] part) {
        for (int i = 0; i + part.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A step that changes nothing and runs an action, once, on the first class it is offered.
     */
    private static final class HoldingStep implements ClassTransformPipeline.Step {

        private final Runnable action;
        private final AtomicBoolean done = new AtomicBoolean();

        private HoldingStep(Runnable action) {
            this.action = action;
        }

        @Override
        public String name() {
            return "holding";
        }

        @Override
        public boolean appliesTo(ClassTransformPipeline.Layer layer) {
            return true;
        }

        @Override
        public boolean matches(String entryName, byte[] bytes) {
            if (done.compareAndSet(false, true)) {
                action.run();
            }
            return false;
        }

        @Override
        public boolean changes(ClassModel model) {
            return false;
        }

        @Override
        public ClassTransform transform(ClassModel model) {
            return ClassTransform.ACCEPT_ALL;
        }

        @Override
        public String summary(ClassTransformPipeline.StepCount total,
                              List<ClassTransformPipeline.JarReport> reports) {
            return name() + ": " + total;
        }
    }
}
