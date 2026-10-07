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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
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
 * Desugaring lambdas through {@link ClassPathTransform#run(ClassPathTransform.Request)}, as a build plugin or Micronaut
 * Runner calls it: which names an open class path leaves uncertain, which JDK packages are the JDK's, how directories
 * and jars are copied, what the result reports, and how the step runs together with stripping.
 */
class ClassPathDesugaringTest {

    private static final String UNCERTAIN = LambdaDesugarer.Reason.SHADOWED_OR_UNCERTAIN.label();

    private static final String HOST = """
            package %s;
            import java.util.function.Supplier;
            public class Host {
                public static Supplier<String> make() {
                    return () -> "%s";
                }
            }
            """;

    @TempDir
    static Path temp;

    @Test
    void inAnOpenClassPathAHostANestHostOrAnOwnerThatTwoEntriesHoldStays() throws Exception {
        Map<String, byte[]> classes = compile("two-holders", Map.of(
                "dup/Host.java", HOST.formatted("dup", "dup"),
                "nest/Outer.java", """
                        package nest;
                        import java.util.function.Supplier;
                        public class Outer {
                            public static class Inner {
                                public Supplier<String> inner() {
                                    return () -> "inner";
                                }
                            }
                        }
                        """,
                "own/Owner.java", """
                        package own;
                        public class Owner {
                            public static String greet(String name) {
                                return "hello " + name;
                            }
                        }
                        """,
                "use/User.java", """
                        package use;
                        import java.util.function.Function;
                        public class User {
                            public static Function<String, String> greeter() {
                                return own.Owner::greet;
                            }
                        }
                        """));
        Path first = jar("two-holders/a.jar", classes, "dup/Host.class", "nest/Outer.class");
        Path second = jar("two-holders/b.jar", classes, "dup/Host.class", "nest/Outer.class",
                "nest/Outer$Inner.class", "use/User.class", "own/Owner.class");
        Path third = jar("two-holders/c.jar", classes, "own/Owner.class");
        Path output = temp.resolve("two-holders/out");

        ClassPathTransform.Result result = desugar(List.of(first, second, third), output);

        assertEquals(List.of(first, second, third), result.classPath(), "nothing is rewritten");
        assertEquals(List.of(), ClassPathTransformTest.written(output));
        assertEquals(List.of(first, second), paths(result));
        assertEquals(Map.of(UNCERTAIN, 1), result.entries().get(0).sitesLeft(), "the first copy of the host");
        assertEquals(Map.of(UNCERTAIN, 3), result.entries().get(1).sitesLeft(),
                "the second copy of the host, the member of a nest whose host two entries hold, and the site whose"
                        + " owner two entries hold");
        assertEquals(0, result.entries().get(1).sitesRewritten());
    }

    @Test
    void aFunctionalInterfaceThatTwoEntriesHoldMakesItsSiteStay() throws Exception {
        Map<String, byte[]> classes = compile("functional", Map.of(
                "fi/Fn.java", "package fi; public interface Fn { String get(); }\n",
                "base/Parent.java", "package base; public class Parent { public String name() { return \"p\"; } }\n",
                "dup/Child.java", "package dup; public class Child extends base.Parent { }\n",
                "use/User.java", """
                        package use;
                        import java.util.function.Supplier;
                        public class User {
                            public static fi.Fn fn() {
                                return () -> "fn";
                            }
                            public static Supplier<String> named(dup.Child child) {
                                return child::name;
                            }
                            public static Supplier<String> plain() {
                                return () -> "plain";
                            }
                        }
                        """));
        Path first = jar("functional/a.jar", classes, "fi/Fn.class", "dup/Child.class");
        Path second = jar("functional/b.jar", classes, "fi/Fn.class", "dup/Child.class", "base/Parent.class",
                "use/User.class");

        ClassPathTransform.Result result = desugar(List.of(first, second), temp.resolve("functional/out"));

        ClassPathTransform.Result.Entry entry = entry(result, second);
        assertEquals(Map.of(UNCERTAIN, 2), entry.sitesLeft(),
                "the functional interface, and the captured receiver's type, that two entries hold");
        assertEquals(1, entry.sitesRewritten(), "a site whose types are certain is rewritten");
    }

    @Test
    void aNameWithAVersionedCopyInAJarWithoutMultiReleaseOrInADirectoryStays() throws Exception {
        Map<String, byte[]> classes = compile("versioned", Map.of(
                "vj/Host.java", HOST.formatted("vj", "jar"),
                "vj/Plain.java", HOST.formatted("vj", "plain").replace("class Host", "class Plain"),
                "vd/Host.java", HOST.formatted("vd", "directory")));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("vj/Host.class", classes.get("vj/Host.class"));
        entries.put("vj/Plain.class", classes.get("vj/Plain.class"));
        entries.put("META-INF/versions/11/vj/Host.class", classes.get("vj/Host.class"));
        Path jar = ClassFixtures.jar(temp.resolve("versioned/plain.jar"), entries);
        Path directory = temp.resolve("versioned/classes");
        write(directory, "vd/Host.class", classes.get("vd/Host.class"));
        write(directory, "META-INF/versions/11/vd/Host.class", classes.get("vd/Host.class"));

        ClassPathTransform.Result result = desugar(List.of(directory, jar), temp.resolve("versioned/out"));

        assertEquals(directory, result.classPath().get(0), "the directory's only host stays");
        assertEquals(Map.of(UNCERTAIN, 1), entry(result, directory).sitesLeft());
        ClassPathTransform.Result.Entry inJar = entry(result, jar);
        assertEquals(Map.of(UNCERTAIN, 1), inJar.sitesLeft(),
                "a fat JAR whose manifest says Multi-Release would load the variant");
        assertEquals(1, inJar.sitesRewritten(), "the class without a variant is rewritten");
    }

    @Test
    void anOwnerInAPackageOfAModuleTheApplicationLoaderDefinesIsJudgedAsAJdkOwner() throws Exception {
        Map<String, byte[]> classes = compile("compiler", Map.of("jdkc/UsesCompiler.java", USES_COMPILER));
        Path jar = ClassFixtures.jar(temp.resolve("compiler/uses.jar"), classes);

        ClassPathTransform.Result result = desugar(List.of(jar), temp.resolve("compiler/out"));

        ClassPathTransform.Result.Entry entry = entry(result, jar);
        assertEquals(2, entry.sitesRewritten(), entry::toString);
        assertEquals(Map.of(), entry.sitesLeft());
        // The generated class that calls Trees.getTree on a DocTrees verifies only when the gate can resolve the
        // classes of jdk.compiler, which the platform class loader cannot see.
        assertEquals(0, entry.nestFallbacks(), entry::toString);
        assertEquals(List.of(), entry.notes());
    }

    @Test
    void aJdkPackageThatTheClassPathAlsoHoldsMakesItsSitesStay() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<>(compile("overlap", Map.of(
                "jdkc/UsesCompiler.java", USES_COMPILER,
                "jdko/UsesPlatform.java", """
                        package jdko;
                        import java.time.LocalDate;
                        import java.util.concurrent.Callable;
                        import java.util.function.Function;
                        public class UsesPlatform {
                            public static Function<java.sql.Date, LocalDate> date() {
                                return java.sql.Date::toLocalDate;
                            }
                            public static Callable<String> callable() {
                                return () -> "called";
                            }
                        }
                        """)));
        for (String extra : List.of("com/sun/source/tree/Extra", "com/sun/source/util/Extra", "java/sql/Extra",
                "java/util/concurrent/Extra")) {
            classes.put(extra + ".class", emptyClass(extra));
        }
        Path jar = ClassFixtures.jar(temp.resolve("overlap/overlap.jar"), classes);

        ClassPathTransform.Result result = desugar(List.of(jar), temp.resolve("overlap/out"));

        ClassPathTransform.Result.Entry entry = entry(result, jar);
        assertEquals(Map.of(UNCERTAIN, 4), entry.sitesLeft(), "owners in jdk.compiler (an application loader module)"
                + " and java.sql (a platform loader module), and a functional interface in java.base, each in a"
                + " package the class path also holds");
        assertEquals(0, entry.sitesRewritten());
        assertEquals(List.of(jar), result.classPath());
    }

    @Test
    void aDirectoryEntryIsRewrittenIntoADirectoryCopy() throws Exception {
        Map<String, byte[]> classes = compile("directory", Map.of("dir/Host.java", HOST.formatted("dir", "dir")));
        Path directory = temp.resolve("directory/classes");
        write(directory, "dir/Host.class", classes.get("dir/Host.class"));
        write(directory, "dir/application.properties", "a=b\n".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(directory.resolve("empty/nested"));
        FileTime host = FileTime.fromMillis(1_000_000_000_000L);
        FileTime resource = FileTime.fromMillis(1_100_000_000_000L);
        FileTime folder = FileTime.fromMillis(1_200_000_000_000L);
        Files.setLastModifiedTime(directory.resolve("dir/Host.class"), host);
        Files.setLastModifiedTime(directory.resolve("dir/application.properties"), resource);
        for (Path path : List.of(directory.resolve("empty/nested"), directory.resolve("empty"),
                directory.resolve("dir"), directory)) {
            Files.setLastModifiedTime(path, folder);
        }
        Map<String, String> before = tree(directory);
        Path output = temp.resolve("directory/out");

        ClassPathTransform.Result result = desugar(List.of(directory), output);

        Path copy = output.resolve("0/classes");
        assertEquals(List.of(copy), result.classPath(), "a directory is replaced by a directory of the same name");
        assertEquals(before, tree(directory), "the directory is not modified");
        Map<String, String> after = tree(copy);
        assertEquals(List.of("dir/", "dir/Host$$Lambda$R0.class", "dir/Host.class", "dir/application.properties",
                "empty/", "empty/nested/"), List.copyOf(after.keySet()), "every file and directory, and the generated"
                + " class next to its host");
        assertEquals(before.get("dir/application.properties"), after.get("dir/application.properties"));
        assertNotEquals(-1, Files.mismatch(directory.resolve("dir/Host.class"), copy.resolve("dir/Host.class")));
        assertEquals(host, Files.getLastModifiedTime(copy.resolve("dir/Host.class")));
        assertEquals(host, Files.getLastModifiedTime(copy.resolve("dir/Host$$Lambda$R0.class")),
                "a generated class takes its host's time");
        assertEquals(resource, Files.getLastModifiedTime(copy.resolve("dir/application.properties")));
        for (String path : List.of("", "dir", "empty", "empty/nested")) {
            assertEquals(folder, Files.getLastModifiedTime(copy.resolve(path)), path + " keeps its time");
        }
        assertEquals(1, entry(result, directory).sitesRewritten());
        assertEquals(List.of(), entry(result, directory).notes());
    }

    @Test
    void anEntryWithoutASiteIsReturnedAsItsOwnPathAndNothingIsWritten() throws Exception {
        Map<String, byte[]> classes = compile("no-site", Map.of(
                "plain/Plain.java", "package plain; public class Plain { public String name() { return \"p\"; } }\n"));
        Path jar = ClassFixtures.jar(temp.resolve("no-site/plain.jar"), classes);
        Path directory = temp.resolve("no-site/classes");
        write(directory, "plain/Plain.class", classes.get("plain/Plain.class"));
        Path output = temp.resolve("no-site/out");

        ClassPathTransform.Result result = desugar(List.of(directory, jar), output);

        assertEquals(List.of(directory, jar), result.classPath());
        assertEquals(List.of(), result.entries(), "no entry holds a site");
        assertFalse(Files.exists(output), "nothing is written");
        assertTrue(result.summary().startsWith("Desugared 0 lambda call sites into 0 generated classes in 0 class"
                + " path entries"), result::summary);
    }

    @Test
    void twoEntriesWithTheSameFileNameAreBothRewrittenIntoSeparateCopies() throws Exception {
        Map<String, byte[]> classes = compile("same-name", Map.of(
                "p1/Host.java", HOST.formatted("p1", "one"), "p2/Host.java", HOST.formatted("p2", "two")));
        Path first = jar("same-name/a/lib.jar", classes, "p1/Host.class");
        Path second = jar("same-name/b/lib.jar", classes, "p2/Host.class");
        Path output = temp.resolve("same-name/out");

        ClassPathTransform.Result result = desugar(List.of(first, second), output);

        assertEquals(List.of(output.resolve("0/lib.jar"), output.resolve("1/lib.jar")), result.classPath());
        assertEquals(List.of("p1/Host.class", "p1/Host$$Lambda$R0.class"), names(result.classPath().get(0)));
        assertEquals(List.of("p2/Host.class", "p2/Host$$Lambda$R0.class"), names(result.classPath().get(1)));
    }

    @Test
    void foreignPackagesExcludeHostsNestHostsAndOwners() throws Exception {
        Map<String, byte[]> classes = compile("foreign", Map.of(
                "frn/Host.java", HOST.formatted("frn", "host"),
                "frn/sub/Host.java", HOST.formatted("frn.sub", "sub"),
                "frn/Owner.java", """
                        package frn;
                        public class Owner {
                            public static String greet(String name) {
                                return "hello " + name;
                            }
                            public interface Fn {
                                String get();
                            }
                        }
                        """,
                "frnx/Host.java", HOST.formatted("frnx", "beside"),
                "user/User.java", """
                        package user;
                        import java.util.function.Function;
                        public class User {
                            public static Function<String, String> greeter() {
                                return frn.Owner::greet;
                            }
                            public static frn.Owner.Fn fn() {
                                return () -> "fn";
                            }
                        }
                        """));
        Path jar = ClassFixtures.jar(temp.resolve("foreign/lib.jar"), classes);

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(jar)).outputDirectory(temp.resolve("foreign/out")).desugarLambdas(true)
                .foreignPackages(Set.of("frn")).build());

        ClassPathTransform.Result.Entry entry = entry(result, jar);
        assertEquals(Map.of(UNCERTAIN, 4), entry.sitesLeft(),
                "a host in the package and one in a subpackage, an owner and a functional interface in it");
        assertEquals(1, entry.sitesRewritten(), "a package that only starts with the same name is not foreign");
        assertTrue(names(result.classPath().get(0)).contains("frnx/Host$$Lambda$R0.class"));
    }

    /**
     * The numbers each entry reports for the scenario, as Micronaut Runner's {@code transforms.txt} lines report
     * them: the sites rewritten, the classes rewritten and generated, the bridges, the nest fallbacks and the sites
     * left by reason.
     */
    @Test
    void entriesGiveTheCountsOfEachEntry() throws Exception {
        Map<Integer, List<String>> golden = new TreeMap<>();
        for (int release : new int[] {8, 25}) {
            List<Path> classPath = scenario("golden-" + release, release);
            ClassPathTransform.Result result = desugar(classPath, temp.resolve("golden-" + release + "/out"));
            List<String> lines = new ArrayList<>();
            for (ClassPathTransform.Result.Entry entry : result.entries()) {
                lines.add(classPath.indexOf(entry.path()) + " sites=" + entry.sitesRewritten() + " classes="
                        + entry.classesDesugared() + " generated=" + entry.classesGenerated() + " bridges="
                        + entry.bridges() + " nestFallbacks=" + entry.nestFallbacks() + " left=" + entry.sitesLeft()
                        + " notes=" + entry.notes().size());
            }
            lines.add(result.summary());
            golden.put(release, lines);
        }
        assertEquals(Map.of(
                8, List.of(
                        "0 sites=2 classes=1 generated=1 bridges=1 nestFallbacks=0 left={} notes=0",
                        "1 sites=29 classes=3 generated=15 bridges=11 nestFallbacks=0 left={java8Interface=2} notes=0",
                        "Desugared 31 lambda call sites into 16 generated classes in 2 class path entries (4 classes"
                                + " rewritten, 12 bridges, 2 sites left as invokedynamic, 0 nest fallbacks)"),
                25, List.of(
                        "0 sites=2 classes=1 generated=1 bridges=0 nestFallbacks=0 left={} notes=0",
                        "1 sites=31 classes=4 generated=17 bridges=0 nestFallbacks=0 left={} notes=0",
                        "Desugared 33 lambda call sites into 18 generated classes in 2 class path entries (5 classes"
                                + " rewritten, 0 bridges, 0 sites left as invokedynamic, 0 nest fallbacks)")),
                golden);
    }

    @Test
    void aGeneratedClassCarriesTheHostsSourceFileAndSyntheticMembers() throws Exception {
        Map<String, byte[]> classes = compile("synthetic", Map.of("syn/Host.java", """
                package syn;
                import java.util.function.Function;
                import java.util.function.Supplier;
                public class Host {
                    public static Supplier<String> constant() {
                        return () -> "constant";
                    }
                    public static Function<String, String> capturing(String prefix) {
                        return value -> prefix + value;
                    }
                }
                """));
        Path jar = ClassFixtures.jar(temp.resolve("synthetic/lib.jar"), classes);

        ClassPathTransform.Result result = desugar(List.of(jar), temp.resolve("synthetic/out"));

        Map<String, byte[]> copy = entries(result.classPath().get(0));
        List<String> generated = copy.keySet().stream().filter(name -> name.contains(LambdaClasses.GENERATED_INFIX))
                .toList();
        assertEquals(2, generated.size(), generated::toString);
        for (String name : generated) {
            ClassModel model = ClassFile.of().parse(copy.get(name));
            assertEquals(Optional.of("Host.java"), model.findAttribute(Attributes.sourceFile())
                    .map(attribute -> attribute.sourceFile().stringValue()), name + " reads (Host.java)");
            for (MethodModel method : model.methods()) {
                assertTrue(method.flags().has(AccessFlag.SYNTHETIC),
                        name + "." + method.methodName() + " is synthetic");
            }
            for (FieldModel field : model.fields()) {
                assertTrue(field.flags().has(AccessFlag.SYNTHETIC),
                        name + "." + field.fieldName() + " is synthetic");
            }
            assertFalse(model.fields().isEmpty(), "every generated class holds a field");
        }
    }

    @Test
    void aSecondRunOverItsOwnOutputRewritesNothing() throws Exception {
        List<Path> classPath = scenario("second-run", 8);
        ClassPathTransform.Result first = desugar(classPath, temp.resolve("second-run/out"));
        assertNotEquals(classPath, first.classPath(), "the first run rewrites the scenario");
        Path again = temp.resolve("second-run/again");

        ClassPathTransform.Result second = desugar(first.classPath(), again);

        assertEquals(first.classPath(), second.classPath(), "nothing is left to rewrite");
        assertFalse(Files.exists(again));
        for (ClassPathTransform.Result.Entry entry : second.entries()) {
            assertEquals(0, entry.sitesRewritten(), entry::toString);
        }
        assertTrue(second.entries().stream().anyMatch(entry -> !entry.sitesLeft().isEmpty()),
                "the sites the first run left are still counted");
    }

    @Test
    void aKnownLocalVariableTableReaderStopsStrippingButNotDesugaring() throws Exception {
        List<Path> classPath = new ArrayList<>(scenario("reader", 25));
        Path reader = ClassFixtures.jar(temp.resolve("reader/reader.jar"),
                Map.of("org/aspectj/weaver/World.class", new byte[0]));
        classPath.add(reader);
        Path library = classPath.get(1);

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath).outputDirectory(temp.resolve("reader/out")).desugarLambdas(true)
                .stripLocalVariables(List.of(library)).build());

        assertEquals(1, result.warnings().size(), result.warnings()::toString);
        assertNotEquals(library, result.classPath().get(1), "the library is desugared");
        ClassPathTransform.Result.Entry entry = entry(result, library);
        assertTrue(entry.sitesRewritten() > 0, entry::toString);
        assertEquals(0, entry.classesStripped() + entry.classesUnchanged(), "the strip step did not run");
        byte[] scenario = entries(result.classPath().get(1)).get("fix/Scenario.class");
        assertTrue(LambdaDesugarerTest.code(scenario, "annotated").findAttribute(Attributes.localVariableTable())
                .isPresent(), "its local-variable tables are kept");
        List<String> summary = result.summary().lines().toList();
        assertEquals(2, summary.size(), result::summary);
        assertTrue(summary.get(0).startsWith("Desugared "), result::summary);
        assertTrue(summary.get(1).startsWith("Stripped no local-variable table"), result::summary);
    }

    @Test
    void bothStepsRunInOnePassAndEachCountsEveryClassOnce() throws Exception {
        List<Path> classPath = scenario("both", 17);
        Path library = classPath.get(1);
        Path other = classPath.get(2);

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath).outputDirectory(temp.resolve("both/out")).desugarLambdas(true)
                .stripLocalVariables(List.of(library, other)).build());

        ClassPathTransform.Result.Entry entry = entry(result, library);
        int classes = (int) names(library).stream().filter(name -> name.endsWith(".class")).count();
        assertEquals(classes, entry.classesStripped() + entry.classesUnchanged() + entry.fallbacks(),
                "the strip step counts each class the jar had once: " + entry);
        assertTrue(entry.classesDesugared() > 0 && entry.classesStripped() > 0, entry::toString);
        Map<String, byte[]> copy = entries(result.classPath().get(1));
        byte[] scenario = copy.get("fix/Scenario.class");
        assertEquals(0, LambdaFixtures.sites(scenario, LambdaFixtures.METAFACTORY), "desugared");
        assertTrue(LambdaDesugarerTest.code(scenario, "annotated").findAttribute(Attributes.localVariableTable())
                .isEmpty(), "and stripped in the same pass");
        Map<String, byte[]> desugaredOnly = entries(desugar(classPath, temp.resolve("both/desugared")).classPath()
                .get(1));
        for (Map.Entry<String, byte[]> generated : copy.entrySet()) {
            if (generated.getKey().contains(LambdaClasses.GENERATED_INFIX)) {
                assertArrayEquals(desugaredOnly.get(generated.getKey()), generated.getValue(),
                        generated.getKey() + " is never stripped");
            }
        }
        assertEquals(List.of(classPath.get(0), library, other), paths(result), "the entries with a site, and the"
                + " jars named for stripping, in class-path order");
        List<String> summary = result.summary().lines().toList();
        assertTrue(summary.get(1).startsWith("Stripped local-variable tables from "), result::summary);
        assertTrue(summary.get(1).contains(" in 2 jars "), result::summary);
    }

    @Test
    void aJarThatHoldsAnEntryNameTwiceKeepsItsSitesAsUncertain() throws Exception {
        Map<String, byte[]> classes = compile("repeated", Map.of(
                "rep/Host.java", HOST.formatted("rep", "one"),
                "rep/Hosu.java", HOST.formatted("rep", "two").replace("class Host", "class Hosu")));
        Path jar = ClassFixtures.jar(temp.resolve("repeated/lib.jar"), classes);
        String bytes = new String(Files.readAllBytes(jar), StandardCharsets.ISO_8859_1);
        Files.write(jar, bytes.replace("rep/Hosu.class", "rep/Host.class").getBytes(StandardCharsets.ISO_8859_1));

        ClassPathTransform.Result result = desugar(List.of(jar), temp.resolve("repeated/out"));

        assertEquals(List.of(jar), result.classPath());
        ClassPathTransform.Result.Entry entry = entry(result, jar);
        assertEquals(Optional.of("the jar holds the entry rep/Host.class more than once"), entry.kept());
        assertEquals(Map.of(UNCERTAIN, 1), entry.sitesLeft(), entry::toString);
    }

    @Test
    void theDesugarStepIsNamedInTheRequestAndItsForeignPackagesArePackageNames() {
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                .foreignPackages(Set.of("io/micronaut/runner")));
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                .foreignPackages(Set.of("io.micronaut.")));
        assertThrows(IllegalArgumentException.class, () -> ClassPathTransform.Request.builder()
                .foreignPackages(Set.of("")));
        ClassPathTransform.Request.builder().foreignPackages(Set.of("io.micronaut.runner", "a"));
    }

    private static final String USES_COMPILER = """
            package jdkc;
            import com.sun.source.tree.Tree;
            import com.sun.source.util.DocTrees;
            import java.util.function.Function;
            import javax.lang.model.element.Element;
            public class UsesCompiler {
                public static Function<Tree, Tree.Kind> kind() {
                    return Tree::getKind;
                }
                public static Function<Element, Tree> trees(DocTrees trees) {
                    return trees::getTree;
                }
            }
            """;

    /**
     * The scenario of {@link LambdaFixtures} as a class path: the application's classes as a directory, then the two
     * libraries as jars.
     */
    static List<Path> scenario(String directory, int release) throws IOException {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve(directory + "/compiled"), release);
        Path classes = temp.resolve(directory + "/classes");
        for (Map.Entry<String, byte[]> entry : layers.get(0).entries().entrySet()) {
            write(classes, entry.getKey(), entry.getValue());
        }
        return List.of(classes, ClassFixtures.jar(temp.resolve(directory + "/fix.jar"), layers.get(1).entries()),
                ClassFixtures.jar(temp.resolve(directory + "/other.jar"), layers.get(2).entries()));
    }

    static ClassPathTransform.Result desugar(List<Path> classPath, Path output) throws IOException {
        return ClassPathTransform.run(ClassPathTransform.Request.builder().classPath(classPath)
                .outputDirectory(output).desugarLambdas(true).build());
    }

    static ClassPathTransform.Result.Entry entry(ClassPathTransform.Result result, Path path) {
        return result.entries().stream().filter(entry -> entry.path().equals(path)).findFirst()
                .orElseThrow(() -> new AssertionError("no entry for " + path + " in " + result.entries()));
    }

    private static List<Path> paths(ClassPathTransform.Result result) {
        return result.entries().stream().map(ClassPathTransform.Result.Entry::path).toList();
    }

    private static Map<String, byte[]> compile(String directory, Map<String, String> sources) throws IOException {
        return LambdaFixtures.compile(temp.resolve(directory + "/compiled"), 25, sources);
    }

    private static Path jar(String file, Map<String, byte[]> classes, String... names) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String name : names) {
            entries.put(name, classes.get(name));
        }
        return ClassFixtures.jar(temp.resolve(file), entries);
    }

    private static void write(Path directory, String name, byte[] bytes) throws IOException {
        Path file = directory.resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, bytes);
    }

    /** A public class with no member, in any package, which a compiler would not write in a package of the JDK. */
    private static byte[] emptyClass(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER).withSuperclass(ConstantDescs.CD_Object));
    }

    /** The entry names of a jar, in order. */
    static List<String> names(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return Collections.list(zip.entries()).stream().map(ZipEntry::getName)
                    .filter(name -> !name.startsWith("META-INF/")).toList();
        }
    }

    /** The entries of a jar, in order. */
    static Map<String, byte[]> entries(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        return entries;
    }

    /**
     * The files and directories below a directory, by relative name, a directory's with a trailing slash, each with
     * its content as ISO-8859-1 text, so that two trees compare by content.
     */
    private static Map<String, String> tree(Path directory) throws IOException {
        Map<String, String> tree = new TreeMap<>();
        UnaryOperator<String> slashed = name -> name.replace(directory.getFileSystem().getSeparator(), "/");
        try (Stream<Path> walk = Files.walk(directory)) {
            for (Path path : walk.toList()) {
                if (path.equals(directory)) {
                    continue;
                }
                String name = slashed.apply(directory.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    tree.put(name + "/", "");
                } else {
                    tree.put(name, new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1));
                }
            }
        }
        return tree;
    }
}
