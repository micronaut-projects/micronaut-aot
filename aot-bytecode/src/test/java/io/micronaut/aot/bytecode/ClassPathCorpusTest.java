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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 * <p>Both steps run, in an open class path: lambdas are desugared in every jar, and every jar is named for stripping.
 * An application fails when any rewritten class verifies worse than its original, whether the gate caught it, which
 * a fallback note records, or not, when a generated class does not verify cleanly against the rewritten class path,
 * and when a copy holds anything but the jar's own entries and generated classes. It also fails when its class path
 * names a file that does not exist, holds no jar, or when no class or no lambda call site of it was rewritten: a run
 * that transformed nothing has verified nothing. The counts, the sites left by reason and every fallback are
 * printed.</p>
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
                .desugarLambdas(true)
                .stripLocalVariables(jars)
                .build());
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println(application + ": " + jars.size() + " jars, " + millis + " ms");
        System.out.println(application + ": " + result.summary());
        result.warnings().forEach(warning -> System.out.println(application + ": warning: " + warning));
        Map<String, Integer> left = new TreeMap<>();
        int sites = 0;
        int nestFallbacks = 0;
        for (ClassPathTransform.Result.Entry entry : result.entries()) {
            entry.kept().ifPresent(reason -> System.out.println(application + ": kept " + entry.path() + ": " + reason));
            entry.notes().forEach(note -> System.out.println(application + ": fallback " + note));
            entry.sitesLeft().forEach((reason, count) -> left.merge(reason, count, Integer::sum));
            sites += entry.sitesRewritten();
            nestFallbacks += entry.nestFallbacks();
        }
        System.out.println(application + ": sites left by reason " + left + ", nest fallbacks " + nestFallbacks);

        // Every class the run rewrote, verified again on both sides against a model of the original class path, with
        // the gate's comparison, which ignores the bytecode offset an error names: a rebuilt pool moves the errors a
        // class already had.
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        for (Path jar : jars) {
            scans.add(ClassPathModel.scan(jar, jar.toString(), LocalVariableStripper::isKnownReader));
        }
        ClassPathModel model = ClassPathModel.merge(scans);
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(model);
        // A generated class has no original: it verifies cleanly against the rewritten class path, which holds the
        // other generated classes and the rewritten hosts.
        List<ClassPathModel.LayerScan> rewrittenScans = new ArrayList<>();
        for (Path entry : result.classPath()) {
            rewrittenScans.add(ClassPathModel.scan(entry, entry.toString(), name -> false));
        }
        Function<byte[], List<String>> generatedVerifier =
                ClassTransformPipeline.verifierOf(ClassPathModel.merge(rewrittenScans));
        List<String> grown = new ArrayList<>();
        int rewritten = 0;
        int generated = 0;
        for (int position = 0; position < jars.size(); position++) {
            Path copy = result.classPath().get(position);
            if (copy.equals(jars.get(position))) {
                continue;
            }
            Map<String, byte[]> before = classes(jars.get(position));
            Map<String, byte[]> after = classes(copy);
            Set<String> added = new TreeSet<>(after.keySet());
            added.removeAll(before.keySet());
            assertTrue(after.keySet().containsAll(before.keySet()), copy + " holds the jar's classes");
            for (String name : added) {
                assertTrue(name.contains(LambdaClasses.GENERATED_INFIX), copy + " holds " + name);
                generated++;
                List<String> errors = generatedVerifier.apply(after.get(name));
                if (!errors.isEmpty()) {
                    grown.add(jars.get(position).getFileName() + " " + name + ": " + errors.get(0));
                }
            }
            for (Map.Entry<String, byte[]> entry : after.entrySet()) {
                byte[] original = before.get(entry.getKey());
                if (original == null || Arrays.equals(original, entry.getValue())) {
                    continue;
                }
                rewritten++;
                String error = ClassTransformPipeline.grown(verifier.apply(entry.getValue()), verifier.apply(original));
                if (error != null) {
                    grown.add(jars.get(position).getFileName() + " " + entry.getKey() + ": " + error);
                }
            }
        }
        for (ClassPathTransform.Result.Entry entry : result.entries()) {
            for (String note : entry.notes()) {
                if (note.split("\t", 4)[3].startsWith("verification: ")) {
                    grown.add(note.replace('\t', ' '));
                }
            }
        }
        System.out.println(application + ": " + rewritten + " rewritten and " + generated
                + " generated classes verified again");
        assertEquals(List.of(), grown, application + ": classes whose verification errors grew");
        assertTrue(rewritten > 0, application + ": no class of " + jars.size() + " jars was rewritten");
        assertTrue(sites > 0 && generated > 0, application + ": no lambda call site was rewritten");
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
