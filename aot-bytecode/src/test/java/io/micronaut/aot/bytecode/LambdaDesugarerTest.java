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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.ObjectStreamClass;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.jar.Attributes.Name;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The desugar step, run through the class transform pipeline over a model of fixture entries: what it rewrites
 * behaves as compiled, and what it must not touch keeps its bytes. Ported from Micronaut Runner's tests of its
 * {@code LambdaDesugarer}.
 */
class LambdaDesugarerTest {

    private static final String METAFACTORY = LambdaFixtures.METAFACTORY;

    private static final String CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";

    private static final String OBJECT_METHODS = "java/lang/runtime/ObjectMethods";

    private static final int APPLICATION = 0;

    private static final int LIBRARY = 1;

    private static final int OTHER = 2;

    @TempDir
    static Path temp;

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void rewrittenLambdasBehaveAsCompiled(int release) throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("scenario-" + release), release);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.original(layers), LambdaFixtures.APPLICATION);
        assertEquals("static=s:a", expected.get(0), "the fixture runs as compiled");

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, false);
        Map<String, byte[]> classes = LambdaFixtures.classPath(outcome.outputs());
        List<String> actual = LambdaFixtures.run(classes, LambdaFixtures.APPLICATION);

        assertEquals(withoutHidden(expected), withoutHidden(actual), "the same results, line by line");
        assertEquals("hidden=truetrue", expected.get(expected.size() - 2), "a spun lambda class is hidden");
        assertEquals("hidden=falsefalse", actual.get(actual.size() - 2), "a generated class is not");
        assertTrue(actual.contains("identity=trueconstant"), actual::toString);
        assertTrue(actual.contains("serializable=ser!7"), actual::toString);
        assertTrue(actual.contains("application=app:ktrue"), actual::toString);
        for (ClassTransformPipeline.JarReport report : outcome.reports()) {
            assertEquals(List.of(), report.notes(), "nothing fell back");
        }

        Map<String, byte[]> library = outcome.outputs().get(LIBRARY);
        Map<String, byte[]> original = layers.get(LIBRARY).entries();
        // Below 55 an interface cannot take the bridge its private lambda bodies would need.
        Set<String> left = new TreeSet<>();
        library.forEach((name, bytes) -> {
            if (LambdaFixtures.sites(bytes, METAFACTORY) > 0) {
                left.add(name);
            }
        });
        assertEquals(release < 11 ? Set.of("fix/Scenario$Shape.class") : Set.of(), left,
                "the classes that still link a lambda at run time");
        if (release < 11) {
            assertArrayEquals(original.get("fix/Scenario$Shape.class"), library.get("fix/Scenario$Shape.class"),
                    "a Java 8 interface host with private implementations keeps its bytes");
            assertEquals(2, outcome.reports().get(LIBRARY).desugared().left()
                    .get(LambdaDesugarer.Reason.JAVA8_INTERFACE));
        }
        if (release >= 9) {
            assertEquals(LambdaFixtures.sites(original.get("fix/Scenario.class"), CONCAT_FACTORY),
                    LambdaFixtures.sites(library.get("fix/Scenario.class"), CONCAT_FACTORY),
                    "string concatenation keeps its call sites");
            assertTrue(LambdaFixtures.sites(library.get("fix/Scenario.class"), CONCAT_FACTORY) > 0);
        }

        assertGenerated(release, original, library, classes);
        assertEquals(List.of("app/Main.class", "app/Main$$Lambda$R0.class", "app/Main$$Lambda$R1.class"),
                List.copyOf(outcome.outputs().get(APPLICATION).keySet()),
                "an application host is rewritten and its generated classes follow it");
        assertArrayEquals(layers.get(OTHER).entries().get("other/Helper.class"),
                outcome.outputs().get(OTHER).get("other/Helper.class"), "a class without lambdas is untouched");
        ClassTransformPipeline.Desugared desugared = outcome.reports().get(LIBRARY).desugared();
        assertEquals(desugared.sites(), desugared.generated());
        assertEquals(desugared.generated(), library.keySet().stream()
                .filter(name -> name.contains(LambdaClasses.GENERATED_INFIX)).count());
        assertEquals(0, desugared.nestFallbacks());

        // The serialVersionUID a serializable Java 8 host declares is what it was, bridge or not.
        Class<?> serializable = LambdaFixtures.loader(classes).loadClass("fix.Ser");
        assertEquals(7L, ObjectStreamClass.lookup(serializable).getSerialVersionUID());
    }

    /**
     * The shape of what the step wrote: generated classes next to their hosts, in the nest from 55 and behind bridges
     * below it.
     */
    private static void assertGenerated(int release, Map<String, byte[]> original, Map<String, byte[]> library,
                                        Map<String, byte[]> classes) throws Exception {
        List<String> names = List.copyOf(library.keySet());
        ClassModel scenario = ClassFile.of().parse(library.get("fix/Scenario.class"));
        List<String> nestMembers = scenario.findAttribute(Attributes.nestMembers())
                .map(NestMembersAttribute::nestMembers).orElse(List.of()).stream()
                .map(ClassEntry::asInternalName).toList();
        int generated = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            int infix = name.indexOf(LambdaClasses.GENERATED_INFIX);
            if (infix < 0) {
                continue;
            }
            generated++;
            String host = name.substring(0, infix) + ".class";
            int hostIndex = names.indexOf(host);
            assertTrue(hostIndex >= 0 && hostIndex < i, name + " follows its host");
            for (int between = hostIndex + 1; between < i; between++) {
                assertTrue(names.get(between).startsWith(name.substring(0, infix) + LambdaClasses.GENERATED_INFIX),
                        "only generated classes sit between " + host + " and " + name);
            }
            ClassModel model = ClassFile.of().parse(library.get(name));
            ClassModel hostModel = ClassFile.of().parse(library.get(host));
            assertEquals(hostModel.majorVersion(), model.majorVersion(), name + " has its host's version");
            assertEquals(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_SUPER,
                    model.flags().flagsMask(), name + " is package-private, final and synthetic");
            String internalName = name.substring(0, name.length() - 6);
            if (release >= 11) {
                String nestHost = model.findAttribute(Attributes.nestHost()).orElseThrow().nestHost()
                        .asInternalName();
                if (host.startsWith("fix/Scenario")) {
                    assertEquals("fix/Scenario", nestHost, name + " joins its host's nest");
                    assertTrue(nestMembers.contains(internalName), "the nest host lists " + name);
                } else {
                    assertEquals(host.substring(0, host.length() - 6), nestHost);
                }
            } else {
                assertTrue(model.findAttribute(Attributes.nestHost()).isEmpty(), "no nest below 55");
            }
            Class<?> loaded = Class.forName(internalName.replace('/', '.'), false, LambdaFixtures.loader(classes));
            assertFalse(loaded.isHidden());
            assertTrue(loaded.isSynthetic());
        }
        assertTrue(generated >= 15, "the scenario has dozens of sites: " + generated);

        List<String> bridges = new ArrayList<>();
        for (MethodModel method : scenario.methods()) {
            if (method.methodName().stringValue().startsWith(LambdaClasses.BRIDGE_PREFIX)) {
                bridges.add(method.methodName().stringValue());
                int flags = method.flags().flagsMask();
                assertEquals(ClassFile.ACC_STATIC | ClassFile.ACC_SYNTHETIC, flags,
                        "a bridge is package-private, static and synthetic");
            }
        }
        if (release >= 11) {
            assertEquals(List.of(), bridges, "nestmates need no bridge");
            assertEquals(ClassFile.of().parse(original.get("fix/Scenario.class")).methods().size(),
                    scenario.methods().size(), "and the host gains no method");
            assertTrue(nestMembers.containsAll(List.of("fix/Scenario$Nested", "fix/Scenario$Inner",
                    "fix/Scenario$Shape")), "the members it had stay listed: " + nestMembers);
        } else {
            assertFalse(bridges.isEmpty(), "a Java 8 host reaches its private lambda bodies through bridges");
            Class<?> loaded = LambdaFixtures.loader(classes).loadClass("fix.Scenario");
            for (Method method : loaded.getDeclaredMethods()) {
                if (method.getName().startsWith(LambdaClasses.BRIDGE_PREFIX)) {
                    assertTrue(Modifier.isStatic(method.getModifiers()) && method.isSynthetic()
                            && !Modifier.isPublic(method.getModifiers())
                            && !Modifier.isPrivate(method.getModifiers()), method::toString);
                }
            }
            assertTrue(ClassFile.of().parse(library.get("fix/Ser.class")).methods().stream()
                            .anyMatch(method -> method.methodName().stringValue()
                                    .startsWith(LambdaClasses.BRIDGE_PREFIX)),
                    "a serializable Java 8 host that declares serialVersionUID is bridged");
        }
    }

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 11, 17, 21, 25})
    void desugaredAndStrippedClassesBehaveAsCompiled(int release) throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("stripped-" + release), release);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.original(layers), LambdaFixtures.APPLICATION);

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, true);
        List<String> actual = LambdaFixtures.run(LambdaFixtures.classPath(outcome.outputs()),
                LambdaFixtures.APPLICATION);

        assertEquals(withoutHidden(expected), withoutHidden(actual));
        ClassTransformPipeline.JarReport report = outcome.reports().get(LIBRARY);
        assertEquals(List.of(), report.notes());
        assertTrue(report.counts().get(0).rewritten() > 0, "desugared: " + report.counts());
        assertTrue(report.counts().get(1).rewritten() > 0, "stripped: " + report.counts());
        assertTrue(report.counts().get(0).bytesSaved() < 0, "desugaring adds classes: " + report.counts());
        byte[] scenario = outcome.outputs().get(LIBRARY).get("fix/Scenario.class");
        assertTrue(code(scenario, "annotated").findAttribute(Attributes.localVariableTable()).isEmpty(),
                "a desugared host is stripped in the same pass");
        assertEquals(0, LambdaFixtures.sites(scenario, METAFACTORY));
        // The application's own classes are user code: desugared, never stripped.
        byte[] application = outcome.outputs().get(APPLICATION).get("app/Main.class");
        assertTrue(code(application, "run").findAttribute(Attributes.localVariableTable()).isPresent());
        assertEquals(0, LambdaFixtures.sites(application, METAFACTORY));
        assertEquals(List.of(true, false), outcome.reports().get(APPLICATION).ran(),
                "the strip step does not run over the application's classes");
    }

    @Test
    void aMethodWithATypeAnnotatedLocalKeepsEveryOffset() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("offsets"), 25);
        byte[] original = layers.get(LIBRARY).entries().get("fix/Scenario.class");

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, false);
        byte[] rewritten = outcome.outputs().get(LIBRARY).get("fix/Scenario.class");

        CodeAttribute before = (CodeAttribute) code(original, "annotated");
        CodeAttribute after = (CodeAttribute) code(rewritten, "annotated");
        assertTrue(LambdaFixtures.sites(original, METAFACTORY) > 0, "the method sits in a class with lambdas");
        assertEquals(0, LambdaFixtures.sites(rewritten, METAFACTORY), "and its site was rewritten");
        assertEquals(before.codeLength(), after.codeLength(), "the call site keeps its length");
        assertEquals(localTargets(before), localTargets(after),
                "the type annotation still covers the same bytecode range");
        assertFalse(localTargets(after).isEmpty(), "the fixture has a type-annotated local");
        assertEquals(List.of(), outcome.reports().get(LIBRARY).notes());
    }

    @Test
    void transformingTheSameInputTwiceGivesIdenticalBytes() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("twice"), 25);

        LambdaFixtures.Outcome first = LambdaFixtures.transform(layers, true);
        LambdaFixtures.Outcome second = LambdaFixtures.transform(layers, true);

        assertEquals(first.reports(), second.reports());
        for (int layer = 0; layer < layers.size(); layer++) {
            Map<String, byte[]> one = first.outputs().get(layer);
            Map<String, byte[]> other = second.outputs().get(layer);
            assertEquals(List.copyOf(one.keySet()), List.copyOf(other.keySet()), "the same entries in order");
            one.forEach((name, bytes) -> assertArrayEquals(bytes, other.get(name), name));
        }
    }

    @Test
    void aSuperReferenceACallerSensitiveTargetAndOtherBootstrapsAreLeftAlone() throws Exception {
        Map<String, byte[]> compiled = compile("keep", 25, Map.of(
                "keep/Base.java", """
                        package keep;
                        public class Base {
                            public String name() {
                                return "base";
                            }
                        }
                        """,
                "keep/SuperRef.java", """
                        package keep;
                        import java.util.function.Supplier;
                        public class SuperRef extends Base {
                            public Supplier<String> ref() {
                                return this::name;
                            }
                        }
                        """,
                "keep/Sensitive.java", """
                        package keep;
                        public class Sensitive {
                            public interface Loader {
                                Class<?> load(String name) throws Exception;
                            }
                            public static Loader loader() {
                                return Class::forName;
                            }
                        }
                        """,
                "keep/Rec.java", """
                        package keep;
                        public record Rec(String text, int number) {
                            public String joined() {
                                return text + number;
                            }
                        }
                        """));
        Map<String, byte[]> entries = new LinkedHashMap<>(compiled);
        entries.put("keep/SuperRef.class", withSuperReference(compiled.get("keep/SuperRef.class")));
        assertTrue(LambdaFixtures.sites(entries.get("keep/Rec.class"), CONCAT_FACTORY) > 0);
        assertTrue(LambdaFixtures.sites(entries.get("keep/Rec.class"), OBJECT_METHODS) > 0);

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(
                List.of(LambdaFixtures.Layer.dependency("libs/keep.jar", entries)), false);

        assertUntouched(entries, outcome.outputs().get(0));
        assertEquals(Map.of(LambdaDesugarer.Reason.SUPER_CALL, 1, LambdaDesugarer.Reason.CALLER_SENSITIVE, 1),
                outcome.reports().get(0).desugared().left());
        assertEquals(0, outcome.reports().get(0).desugared().sites());
    }

    @Test
    void aSpecialReferenceToAMethodOfTheHostIsRewrittenOnlyWhenTheMethodIsPrivate() throws Exception {
        Map<String, byte[]> compiled = compile("special", 25, Map.of(
                "special/Open.java", """
                        package special;
                        import java.util.function.Supplier;
                        public class Open {
                            public String m() {
                                return "Open.m";
                            }
                            public Supplier<String> ref() {
                                return this::m;
                            }
                        }
                        """,
                "special/Sub.java", """
                        package special;
                        public class Sub extends Open {
                            @Override
                            public String m() {
                                return "Sub.m";
                            }
                        }
                        """,
                "special/Closed.java", """
                        package special;
                        import java.util.function.Supplier;
                        public class Closed {
                            private String m() {
                                return "Closed.m";
                            }
                            public Supplier<String> ref() {
                                return this::m;
                            }
                        }
                        """));
        Map<String, byte[]> entries = new LinkedHashMap<>(compiled);
        for (String host : List.of("Open", "Closed")) {
            entries.put("special/" + host + ".class", withSpecialReference(compiled.get("special/" + host + ".class"),
                    ClassDesc.of("special." + host)));
        }
        // LambdaMetafactory keeps such a handle non-virtual unless the method is private: through a subclass that
        // overrides it, the reference still calls the host's own method.
        assertEquals("Open.m", referenced(entries, "special.Sub"));
        assertEquals("Closed.m", referenced(entries, "special.Closed"));

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(
                List.of(LambdaFixtures.Layer.dependency("libs/special.jar", entries)), false);

        Map<String, byte[]> output = outcome.outputs().get(0);
        assertArrayEquals(entries.get("special/Open.class"), output.get("special/Open.class"),
                "an invokevirtual would reach the override");
        assertNull(output.get("special/Open$$Lambda$R0.class"));
        assertEquals("Open.m", referenced(output, "special.Sub"));
        assertEquals(Map.of(LambdaDesugarer.Reason.SUPER_CALL, 1), outcome.reports().get(0).desugared().left());
        assertEquals(1, outcome.reports().get(0).desugared().sites(), "the private method's site is rewritten");
        assertNotNull(output.get("special/Closed$$Lambda$R0.class"));
        assertEquals("Closed.m", referenced(output, "special.Closed"));
    }

    /**
     * Micronaut Runner judged an owner by the copy its own archive resolves; an open class path does not tell which
     * copy loads, so an owner that two entries hold, or that has a versioned copy, makes the site stay. An owner with
     * one copy is judged by that copy.
     */
    @Test
    void anOwnerThatAnotherEntryHoldsOrThatHasAVersionedCopyIsLeftAlone() throws Exception {
        Map<String, byte[]> earlier = compile("shadow-first", 25, Map.of(
                "lacking/Owner.java", "package lacking; public class Owner { }\n",
                "unexported/Owner.java", """
                        package unexported;
                        class Owner {
                            public static String greet(String name) {
                                return "earlier " + name;
                            }
                        }
                        """));
        String owner = """
                package %s;
                public class Owner {
                    public static String greet(String name) {
                        return "hello " + name;
                    }
                }
                """;
        String user = """
                package use;
                import java.util.function.Function;
                public class Uses%s {
                    public static Function<String, String> greeter() {
                        return %s.Owner::greet;
                    }
                }
                """;
        Map<String, byte[]> later = compile("shadow-second", 25, Map.of(
                "lacking/Owner.java", owner.formatted("lacking"),
                "unexported/Owner.java", owner.formatted("unexported"),
                "versioned/Owner.java", owner.formatted("versioned"),
                "use/UsesLacking.java", user.formatted("Lacking", "lacking"),
                "use/UsesUnexported.java", user.formatted("Unexported", "unexported"),
                "use/UsesVersioned.java", user.formatted("Versioned", "versioned")));
        Map<String, byte[]> missing = compile("shadow-missing", 25, Map.of(
                "lone/Owner.java", "package lone; public class Owner { }\n",
                "hidden/Owner.java", """
                        package hidden;
                        class Owner {
                            public static String greet(String name) {
                                return "hidden " + name;
                            }
                        }
                        """));
        Map<String, byte[]> loneUsers = compile("shadow-users", 25, Map.of(
                "lone/Owner.java", owner.formatted("lone"),
                "hidden/Owner.java", owner.formatted("hidden"),
                "use2/UsesLone.java", user.formatted("Lone", "lone").replace("package use;", "package use2;"),
                "use2/UsesHidden.java", user.formatted("Hidden", "hidden").replace("package use;", "package use2;")));
        Map<String, byte[]> multiRelease = new LinkedHashMap<>(LambdaFixtures.select(later, "versioned/"));
        multiRelease.put("META-INF/versions/26/versioned/Owner.class", later.get("versioned/Owner.class"));
        Map<String, byte[]> users = new LinkedHashMap<>(LambdaFixtures.select(later, "use/"));
        users.putAll(LambdaFixtures.select(later, "lacking/"));
        users.putAll(LambdaFixtures.select(later, "unexported/"));
        users.putAll(LambdaFixtures.select(loneUsers, "use2/"));
        // The only copies of these owners lack the member, or are not public.
        Map<String, byte[]> owners = new LinkedHashMap<>(missing);

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(List.of(
                LambdaFixtures.Layer.dependency("libs/first.jar", earlier),
                LambdaFixtures.Layer.multiRelease("libs/versioned.jar", multiRelease),
                LambdaFixtures.Layer.dependency("libs/second.jar", users),
                LambdaFixtures.Layer.dependency("libs/owners.jar", owners)), false);

        assertUntouched(users, outcome.outputs().get(2));
        assertEquals(Map.of(LambdaDesugarer.Reason.SHADOWED_OR_UNCERTAIN, 3,
                        LambdaDesugarer.Reason.OWNER_ACCESS, 2),
                outcome.reports().get(2).desugared().left(),
                "two entries hold the owner, or a versioned copy of it exists; or its only copy lacks the member,"
                        + " or is not public");
    }

    /**
     * An open class path does not tell which copy of a nest host loads: no copy of the nest is rewritten, and each
     * entry counts its own sites.
     */
    @Test
    void aNestWhoseHostTwoEntriesHoldIsLeftAloneAsAWhole() throws Exception {
        String outer = """
                package nest;
                import java.util.function.Supplier;
                public class Outer {
                    public Supplier<String> own() {
                        return () -> "%s";
                    }
                    public static class Inner {
                        public Supplier<String> inner() {
                            return () -> "inner";
                        }
                    }
                }
                """;
        Map<String, byte[]> earlier = compile("nest-first", 25, Map.of("nest/Outer.java", outer.formatted("first")));
        Map<String, byte[]> later = compile("nest-second", 25, Map.of("nest/Outer.java", outer.formatted("second")));
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("nest/Outer.class", earlier.get("nest/Outer.class"));

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(List.of(
                LambdaFixtures.Layer.dependency("libs/first.jar", first),
                LambdaFixtures.Layer.dependency("libs/second.jar", later)), false);

        assertUntouched(first, outcome.outputs().get(0));
        assertUntouched(later, outcome.outputs().get(1));
        assertEquals(Map.of(LambdaDesugarer.Reason.SHADOWED_OR_UNCERTAIN, 1),
                outcome.reports().get(0).desugared().left(), "the first copy of the host");
        assertEquals(Map.of(LambdaDesugarer.Reason.SHADOWED_OR_UNCERTAIN, 2),
                outcome.reports().get(1).desugared().left(), "the second copy of the host, and its nest member");
        assertEquals(List.of(), outcome.reports().get(1).notes(), "nothing was planned, so nothing fell back");
    }

    @Test
    void aSerializableJava8HostWithoutASerialVersionUidIsLeftAlone() throws Exception {
        Map<String, byte[]> compiled = compile("serial", 8, Map.of(
                "serial/NoUid.java", """
                        package serial;
                        import java.io.Serializable;
                        import java.util.function.Supplier;
                        public class NoUid implements Serializable {
                            public Supplier<String> make() {
                                return () -> "value";
                            }
                        }
                        """,
                "serial/Inherited.java", """
                        package serial;
                        import java.util.ArrayList;
                        import java.util.function.Supplier;
                        public class Inherited extends ArrayList<String> {
                            public Supplier<String> make() {
                                return () -> "value";
                            }
                            public static Supplier<Inherited> factory() {
                                return Inherited::new;
                            }
                        }
                        """));

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(
                List.of(LambdaFixtures.Layer.dependency("libs/serial.jar", compiled)), false);

        Map<String, byte[]> output = outcome.outputs().get(0);
        assertArrayEquals(compiled.get("serial/NoUid.class"), output.get("serial/NoUid.class"));
        assertEquals(Map.of(LambdaDesugarer.Reason.SERIAL_VERSION_UID, 2),
                outcome.reports().get(0).desugared().left(), "a class that inherits Serializable counts too");
        // A site of the same class whose implementation is public needs no bridge, so it is rewritten.
        assertEquals(List.of("serial/Inherited.class", "serial/Inherited$$Lambda$R0.class", "serial/NoUid.class"),
                List.copyOf(output.keySet()));
        long before = ObjectStreamClass.lookup(LambdaFixtures.loader(LambdaFixtures.classPath(List.of(compiled)))
                .loadClass("serial.Inherited")).getSerialVersionUID();
        long after = ObjectStreamClass.lookup(LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output)))
                .loadClass("serial.Inherited")).getSerialVersionUID();
        assertEquals(before, after, "its default serialVersionUID is what it was");
    }

    @Test
    void aSiteWhoseGeneratedNameIsTakenIsLeftAlone() throws Exception {
        String host = """
                package %s;
                import java.util.function.Supplier;
                public class Host {
                    public static Supplier<String> make() {
                        return () -> "value";
                    }
                }
                """;
        String occupant = "package %s; public class Host$$Lambda$R0 { }\n";
        Map<String, byte[]> compiled = compile("taken", 25, Map.of(
                "same/Host.java", host.formatted("same"),
                "same/Host$$Lambda$R0.java", occupant.formatted("same"),
                "elsewhere/Host.java", host.formatted("elsewhere"),
                "elsewhere/Host$$Lambda$R0.java", occupant.formatted("elsewhere")));
        Map<String, byte[]> first = new LinkedHashMap<>();
        first.put("elsewhere/Host$$Lambda$R0.class", compiled.get("elsewhere/Host$$Lambda$R0.class"));
        Map<String, byte[]> second = new LinkedHashMap<>(LambdaFixtures.select(compiled, "same/"));
        second.put("elsewhere/Host.class", compiled.get("elsewhere/Host.class"));

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(List.of(
                LambdaFixtures.Layer.dependency("libs/first.jar", first),
                LambdaFixtures.Layer.dependency("libs/second.jar", second)), false);

        assertUntouched(second, outcome.outputs().get(1));
        assertEquals(Map.of(LambdaDesugarer.Reason.NAME_TAKEN, 2), outcome.reports().get(1).desugared().left(),
                "one name is taken in the same entry, the other in another entry");
    }

    /**
     * A {@code META-INF/versions/} variant is never rewritten, and its sites are counted when the running JDK would
     * load it. Its base copy, which a fat JAR may load instead, has an uncertain name, and so does a nest member of a
     * nest host that has a variant.
     */
    @Test
    void versionedEntriesAndTheBaseClassesTheyReplaceAreLeftAlone() throws Exception {
        Map<String, byte[]> compiled = compile("versions", 25, Map.of(
                "mr/Host.java", """
                        package mr;
                        import java.util.function.Supplier;
                        public class Host {
                            public static Supplier<String> make() {
                                return () -> "value";
                            }
                        }
                        """,
                "mr/Outer.java", """
                        package mr;
                        import java.util.function.Supplier;
                        public class Outer {
                            public static class Inner {
                                public Supplier<String> inner() {
                                    return () -> "inner";
                                }
                            }
                        }
                        """,
                "mr/Plain.java", """
                        package mr;
                        import java.util.function.Supplier;
                        public class Plain {
                            public static Supplier<String> make() {
                                return () -> "plain";
                            }
                        }
                        """));
        Map<String, byte[]> entries = new LinkedHashMap<>(compiled);
        entries.put("META-INF/versions/21/mr/Host.class", compiled.get("mr/Host.class"));
        entries.put("META-INF/versions/21/mr/Outer.class", compiled.get("mr/Outer.class"));

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(
                List.of(LambdaFixtures.Layer.multiRelease("libs/mr.jar", entries)), false);

        Map<String, byte[]> output = outcome.outputs().get(0);
        for (String name : List.of("mr/Host.class", "mr/Outer.class", "mr/Outer$Inner.class",
                "META-INF/versions/21/mr/Host.class", "META-INF/versions/21/mr/Outer.class")) {
            assertArrayEquals(entries.get(name), output.get(name), name + " keeps its bytes");
        }
        assertEquals(Map.of(LambdaDesugarer.Reason.MULTI_RELEASE, 1, LambdaDesugarer.Reason.SHADOWED_OR_UNCERTAIN, 2),
                outcome.reports().get(0).desugared().left(),
                "the variant of Host the running JDK loads; the base copy of Host, and the member of a nest whose host"
                        + " has a variant");
        assertEquals(1, outcome.reports().get(0).desugared().sites(), "a class without a variant is rewritten");
        assertNotNull(output.get("mr/Plain$$Lambda$R0.class"));
        assertEquals(entries.size() + 1, output.size(), "and nothing else is generated");
    }

    @Test
    void aSignedJarKeepsEveryByteAndGetsNoGeneratedClass() throws Exception {
        Map<String, byte[]> compiled = compile("signed", 25, Map.of("signed/Host.java", """
                package signed;
                import java.util.function.Supplier;
                public class Host {
                    public static Supplier<String> make() {
                        return () -> "value";
                    }
                }
                """));
        Map<String, byte[]> entries = new LinkedHashMap<>(compiled);
        entries.put("META-INF/TEST.SF", ClassFixtures.utf8("Signature-Version: 1.0\n"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Name.MANIFEST_VERSION, "1.0");
        Path jar = ClassFixtures.jar(temp.resolve("signed/signed.jar"), manifest, entries);
        ClassPathModel model = ClassPathModel.merge(List.of(ClassPathModel.scan(jar, jar.toString(), true,
                name -> false, new ClassPathModel.Interner())));
        ClassTransformPipeline pipeline = new ClassTransformPipeline(
                List.of(new LambdaDesugarer(model, Set.of()), new LocalVariableStripper()), model);
        Path target = temp.resolve("signed/out/0/signed.jar");

        JarRewriter.Outcome outcome = JarRewriter.rewrite(jar, target, pipeline, jar.toString(), 0, true);

        assertFalse(outcome.written(), "no copy");
        assertFalse(target.toFile().exists());
        assertEquals("the jar is signed", outcome.kept());
        ClassTransformPipeline.JarReport report = outcome.report();
        assertEquals(Map.of(LambdaDesugarer.Reason.SIGNED_JAR, 1), report.desugared().left(),
                "its sites are counted, and nothing else is planned");
        assertEquals(0, report.desugared().sites());
        assertEquals(new ClassTransformPipeline.StepCount(LambdaDesugarer.NAME, 0, 1, 0, 0), report.counts().get(0));
    }

    @Test
    void theStepNeedsAModelWithMemberTables() {
        ClassPathModel.LayerScan scan = ClassPathModel.scan("empty", false, name -> false);
        ClassPathModel withoutMembers = ClassPathModel.merge(List.of(scan));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new LambdaDesugarer(withoutMembers, Set.of()));

        assertTrue(failure.getMessage().contains("member tables"), failure.getMessage());
    }

    @Test
    void aSyntheticFailureInOneClassOfANestMakesTheWholeNestFallBack() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("fallback"), 25);
        ClassPathModel model = LambdaFixtures.model(layers);
        Function<byte[], List<String>> real = ClassTransformPipeline.verifierOf(model);
        // One generated class of the Scenario nest, the one of its inner class, fails verification.
        Function<byte[], List<String>> failing = bytes -> ClassFile.of().parse(bytes).thisClass().asInternalName()
                .equals("fix/Scenario$Inner$$Lambda$R0") ? List.of("a synthetic verification error")
                : real.apply(bytes);

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, model, failing,
                List.of(new LocalVariableStripper()));
        LambdaFixtures.Outcome stripOnly = stripOnly(layers, model);

        Map<String, byte[]> library = outcome.outputs().get(LIBRARY);
        ClassTransformPipeline.JarReport report = outcome.reports().get(LIBRARY);
        assertEquals(1, report.notes().size(), report.notes()::toString);
        String[] note = report.notes().get(0).split("\t");
        assertEquals("libs/fix.jar", note[0]);
        assertEquals("the nest of fix/Scenario.class", note[1], "the note names the nest host");
        assertEquals(LambdaDesugarer.NAME, note[2], "and the dropped step");
        assertTrue(note[3].startsWith("fix/Scenario$Inner$$Lambda$R0.class: verification: "), note[3]);
        for (String name : library.keySet()) {
            assertFalse(name.startsWith("fix/Scenario") && name.contains(LambdaClasses.GENERATED_INFIX),
                    "the nest has no generated class: " + name);
        }
        for (String name : List.of("fix/Scenario.class", "fix/Scenario$Inner.class", "fix/Scenario$Nested.class",
                "fix/Scenario$Shape.class")) {
            assertArrayEquals(stripOnly.outputs().get(LIBRARY).get(name), library.get(name),
                    name + " is the original through the later step");
            assertEquals(LambdaFixtures.sites(layers.get(LIBRARY).entries().get(name), METAFACTORY),
                    LambdaFixtures.sites(library.get(name), METAFACTORY), name + " keeps its call sites");
        }
        assertFalse(ClassFile.of().parse(library.get("fix/Scenario.class")).methods().stream()
                .anyMatch(method -> method.methodName().stringValue().startsWith(LambdaClasses.BRIDGE_PREFIX)));
        // The other nest of the entry is not affected.
        assertNotNull(library.get("fix/Ser$$Lambda$R0.class"));
        ClassTransformPipeline.Desugared desugared = report.desugared();
        assertEquals(1, desugared.nestFallbacks());
        assertEquals(1, desugared.sites(), "only the other nest's site is rewritten");
        assertTrue(desugared.left().get(LambdaDesugarer.Reason.NEST_FALLBACK) >= 25, desugared::toString);
        ClassTransformPipeline.StepCount count = report.counts().get(0);
        assertEquals(3, count.fallbacks(), "the nest host and the two members that were planned: " + count);
        assertEquals(1, count.rewritten(), count::toString);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.original(layers), LambdaFixtures.APPLICATION);
        List<String> actual = LambdaFixtures.run(LambdaFixtures.classPath(outcome.outputs()),
                LambdaFixtures.APPLICATION);
        assertEquals(withoutHidden(expected), withoutHidden(actual), "and the application still behaves as compiled");
    }

    /**
     * When the strip step throws on a class of a planned nest, the nest is desugared again without it: the nest keeps
     * its generated classes and the local-variable tables of its classes, and one note names the strip step. A
     * verification failure, by contrast, is blamed on desugaring, the earliest step that changed the class
     * ({@link #aSyntheticFailureInOneClassOfANestMakesTheWholeNestFallBack()}).
     */
    @Test
    void aStripFailureInsideAPlannedNestRetriesTheNestWithoutStripping() throws Exception {
        List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("strip-failure"), 25);
        ClassPathModel model = LambdaFixtures.model(layers);
        FailingStrip failingStrip = new FailingStrip("fix/Scenario$Inner");

        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, model,
                ClassTransformPipeline.verifierOf(model), List.of(failingStrip));

        Map<String, byte[]> library = outcome.outputs().get(LIBRARY);
        ClassTransformPipeline.JarReport report = outcome.reports().get(LIBRARY);
        assertEquals(1, report.notes().size(), report.notes()::toString);
        String[] note = report.notes().get(0).split("\t");
        assertEquals("the nest of fix/Scenario.class", note[1]);
        assertEquals(FailingStrip.NAME, note[2], "the nest dropped the strip step");
        assertTrue(note[3].startsWith("fix/Scenario$Inner.class: IllegalStateException: "), note[3]);
        assertEquals(0, report.desugared().nestFallbacks(), "the nest is still desugared");
        assertNotNull(library.get("fix/Scenario$Inner$$Lambda$R0.class"));
        assertEquals(0, LambdaFixtures.sites(library.get("fix/Scenario$Inner.class"), METAFACTORY));
        assertTrue(code(library.get("fix/Scenario.class"), "annotated")
                .findAttribute(Attributes.localVariableTable()).isPresent(), "no class of the nest is stripped");
        assertTrue(code(library.get("fix/Ser.class"), "make").findAttribute(Attributes.localVariableTable())
                .isEmpty(), "a class of another nest is");
        ClassTransformPipeline.StepCount strip = report.counts().get(1);
        assertTrue(strip.fallbacks() >= 2, "the classes of the nest the strip step would have changed: " + strip);
        List<String> expected = LambdaFixtures.run(LambdaFixtures.original(layers), LambdaFixtures.APPLICATION);
        List<String> actual = LambdaFixtures.run(LambdaFixtures.classPath(outcome.outputs()),
                LambdaFixtures.APPLICATION);
        assertEquals(withoutHidden(expected), withoutHidden(actual));
    }

    private static LambdaFixtures.Outcome stripOnly(List<LambdaFixtures.Layer> layers, ClassPathModel model) {
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model);
        List<Map<String, byte[]>> outputs = new ArrayList<>();
        List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
        for (int index = 0; index < layers.size(); index++) {
            ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer("layer-" + index,
                    index, false, index != APPLICATION));
            Map<String, byte[]> output = new LinkedHashMap<>();
            layers.get(index).entries().forEach((name, bytes) -> output.put(name,
                    run.reads(bytes.length) ? run.process(name, bytes) : bytes));
            outputs.add(output);
            reports.add(run.report());
        }
        return new LambdaFixtures.Outcome(outputs, reports, pipeline);
    }

    /**
     * Asserts that an entry's output is its input, entry for entry and byte for byte.
     */
    static void assertUntouched(Map<String, byte[]> input, Map<String, byte[]> output) {
        assertEquals(List.copyOf(input.keySet()), List.copyOf(output.keySet()), "no entry is added");
        input.forEach((name, bytes) -> assertArrayEquals(bytes, output.get(name), name + " keeps its bytes"));
    }

    /**
     * Calls {@code ref().get()} on a new instance of a class, loaded with the given entries.
     */
    private static Object referenced(Map<String, byte[]> entries, String type) throws Exception {
        Class<?> loaded = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(entries))).loadClass(type);
        Object instance = loaded.getConstructor().newInstance();
        return ((Supplier<?>) loaded.getMethod("ref").invoke(instance)).get();
    }

    static List<String> withoutHidden(List<String> lines) {
        return lines.stream().filter(line -> !line.startsWith("hidden=")).toList();
    }

    private static Map<String, byte[]> compile(String directory, int release, Map<String, String> sources)
            throws IOException {
        return LambdaFixtures.compile(temp.resolve(directory), release, sources);
    }

    static CodeModel code(byte[] bytes, String method) {
        for (MethodModel candidate : ClassFile.of().parse(bytes).methods()) {
            if (candidate.methodName().equalsString(method)) {
                return candidate.code().orElseThrow();
            }
        }
        throw new AssertionError("no method " + method);
    }

    /** The bytecode ranges the local-variable type annotations of a method cover. */
    private static List<String> localTargets(CodeAttribute code) {
        List<String> targets = new ArrayList<>();
        code.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                .map(RuntimeVisibleTypeAnnotationsAttribute::annotations).orElse(List.of())
                .forEach(annotation -> {
                    if (annotation.targetInfo() instanceof TypeAnnotation.LocalVarTarget local) {
                        local.table().forEach(range -> targets.add(code.labelToBci(range.startLabel()) + "-"
                                + code.labelToBci(range.endLabel()) + "@" + range.index()));
                    }
                });
        return targets;
    }

    /**
     * Turns the {@code this::name} reference of a class into the {@code super::name} an older compiler would have
     * written: the same call site, with an {@code invokespecial} handle on the superclass.
     */
    private static byte[] withSuperReference(byte[] bytes) {
        return withSpecialReference(bytes, ClassFile.of().parse(bytes).superclass().orElseThrow().asSymbol());
    }

    /**
     * Turns the {@code this::name} reference of a class into an {@code invokespecial} handle on a given class, the
     * same call site otherwise.
     */
    private static byte[] withSpecialReference(byte[] bytes, ClassDesc owner) {
        ClassFile context = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        ClassModel model = context.parse(bytes);
        return context.transformClass(model, ClassTransform.transformingMethodBodies((builder, element) -> {
            if (element instanceof InvokeDynamicInstruction indy) {
                List<ConstantDesc> arguments = new ArrayList<>(indy.bootstrapArgs());
                DirectMethodHandleDesc implementation = (DirectMethodHandleDesc) arguments.get(1);
                arguments.set(1, MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.SPECIAL, owner,
                        implementation.methodName(), MethodTypeDesc.ofDescriptor(implementation.lookupDescriptor())));
                builder.invokedynamic(DynamicCallSiteDesc.of(indy.bootstrapMethod(), indy.name().stringValue(),
                        indy.typeSymbol(), arguments.toArray(ConstantDesc[]::new)));
            } else {
                builder.with(element);
            }
        }));
    }

    /**
     * The strip step, except that its transform throws on one class.
     */
    private static final class FailingStrip implements ClassTransformPipeline.Step {

        private static final String NAME = "failingStrip";

        private final LocalVariableStripper strip = new LocalVariableStripper();
        private final String failing;

        private FailingStrip(String failing) {
            this.failing = failing;
        }

        @Override
        public String name() {
            return NAME;
        }

        @Override
        public boolean appliesTo(ClassTransformPipeline.Layer layer) {
            return strip.appliesTo(layer);
        }

        @Override
        public boolean matches(String entryName, byte[] bytes) {
            return strip.matches(entryName, bytes);
        }

        @Override
        public boolean changes(ClassModel model) {
            return strip.changes(model);
        }

        @Override
        public ClassTransform transform(ClassModel model) {
            if (model.thisClass().asInternalName().equals(failing)) {
                return (builder, element) -> {
                    throw new IllegalStateException("a synthetic failure of the strip step");
                };
            }
            return strip.transform(model);
        }

        @Override
        public boolean rebuildsConstantPool() {
            return true;
        }

        @Override
        public boolean skipsLargerOutput() {
            return true;
        }

        @Override
        public String summary(ClassTransformPipeline.StepCount total,
                              List<ClassTransformPipeline.JarReport> reports) {
            return NAME + ": " + total;
        }
    }
}
