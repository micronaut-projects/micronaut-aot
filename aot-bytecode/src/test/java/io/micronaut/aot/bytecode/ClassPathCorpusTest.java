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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs {@link ClassPathTransform} with every step over the runtime class paths of real Micronaut applications, and
 * verifies both sides of every class it rewrites.
 *
 * <p>The class paths come from the system properties {@code aot.bytecode.corpus.<application>}, which the
 * {@code classPathCorpusTest} task sets from the resolved corpus configurations of this module's build. The task is
 * not part of {@code check}; the "Class path corpus" workflow runs it.</p>
 *
 * <p>An application fails when any rewritten class verifies worse than its original, whether the gate caught it,
 * which a fallback note records, or not. It also fails when its class path names a file that does not exist, holds no
 * jar, or when no class of it was rewritten: a run that transformed nothing has verified nothing. The counts and every
 * fallback are printed.</p>
 */
@Tag("class-path-corpus")
class ClassPathCorpusTest {

    private static final String PREFIX = "aot.bytecode.corpus.";

    @TempDir
    static Path temp;

    @TestFactory
    Stream<DynamicTest> everyRewrittenClassVerifiesNoWorseThanItsOriginal() {
        Map<String, String> classPaths = new TreeMap<>();
        System.getProperties().forEach((key, value) -> {
            if (key.toString().startsWith(PREFIX)) {
                classPaths.put(key.toString().substring(PREFIX.length()), value.toString());
            }
        });
        Assumptions.assumeFalse(classPaths.isEmpty(), "no corpus: run the classPathCorpusTest task");
        return classPaths.entrySet().stream().map(entry -> DynamicTest.dynamicTest(entry.getKey(),
                () -> runOver(entry.getKey(), entry.getValue())));
    }

    private static void runOver(String application, String classPath) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            Path jar = Path.of(entry);
            assertTrue(Files.isRegularFile(jar), () -> application + " names " + jar + ", which is not a file");
            jars.add(jar);
        }
        assertFalse(jars.isEmpty(), application + " names no jar");

        long start = System.nanoTime();
        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(jars)
                .outputDirectory(temp.resolve(application))
                .stripLocalVariables(jars)
                .build());
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println(application + ": " + jars.size() + " jars, " + millis + " ms");
        System.out.println(application + ": " + result.summary());
        result.warnings().forEach(warning -> System.out.println(application + ": warning: " + warning));
        result.report().lines().filter(line -> !line.startsWith("entry\t") && line.split("\t").length != 6)
                .forEach(line -> System.out.println(application + ": " + line));

        // Every class the run rewrote, verified again on both sides against a model of the original class path, with
        // the gate's comparison, which ignores the bytecode offset an error names: a rebuilt pool moves the errors a
        // class already had.
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        for (Path jar : jars) {
            scans.add(ClassPathModel.scan(jar, jar.toString(), LocalVariableStripper::isKnownReader));
        }
        ClassPathModel model = ClassPathModel.merge(scans);
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(model);
        List<String> grown = new ArrayList<>();
        int rewritten = 0;
        for (int position = 0; position < jars.size(); position++) {
            Path copy = result.classPath().get(position);
            if (copy.equals(jars.get(position))) {
                continue;
            }
            Map<String, byte[]> before = classes(jars.get(position));
            Map<String, byte[]> after = classes(copy);
            assertEquals(before.keySet(), after.keySet(), copy + " holds the same classes");
            for (Map.Entry<String, byte[]> entry : after.entrySet()) {
                byte[] original = before.get(entry.getKey());
                if (Arrays.equals(original, entry.getValue())) {
                    continue;
                }
                rewritten++;
                String error = ClassTransformPipeline.grown(verifier.apply(entry.getValue()), verifier.apply(original));
                if (error != null) {
                    grown.add(jars.get(position).getFileName() + " " + entry.getKey() + ": " + error);
                }
            }
        }
        for (String line : result.report().lines().toList()) {
            if (line.startsWith("fallback\t") && line.split("\t", 5)[4].startsWith("verification: ")) {
                grown.add(line.replace('\t', ' '));
            }
        }
        System.out.println(application + ": " + rewritten + " rewritten classes verified again");
        assertEquals(List.of(), grown, application + ": classes whose verification errors grew");
        assertTrue(rewritten > 0, application + ": no class of " + jars.size() + " jars was rewritten");
    }

    private static Map<String, byte[]> classes(Path jar) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (ClassTransformPipeline.isClass(entry.getName())) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        classes.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return classes;
    }
}
