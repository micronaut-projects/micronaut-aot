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
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.AccessFlag;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classes that sites of one host share: one per functional interface, captured types and interface method, each
 * site keeping its own behaviour, its own constant instance and its own casts, behind a dispatch that stays within
 * HotSpot's {@code FreqInlineSize} and verifies with the frames written for it.
 */
class SharedLambdaClassesTest {

    @TempDir
    static Path temp;

    @Test
    void sitesThatShareAClassKeepTheirOwnBehaviourAndCaptureFreeInstances() throws Exception {
        Map<String, byte[]> output = desugar("shared", 25, "grp.Shared", """
                package grp;
                import java.util.function.Supplier;
                public class Shared {
                    public static Supplier<String> a() {
                        return () -> "a";
                    }
                    public static Supplier<String> b() {
                        return () -> "b";
                    }
                    public static Supplier<String> c(String value) {
                        return () -> "c" + value;
                    }
                    public static Supplier<String> d(String value) {
                        return () -> "d" + value;
                    }
                }
                """);

        assertEquals(Set.of("grp/Shared$$Lambda$R0.class", "grp/Shared$$Lambda$R2.class"), generated(output),
                "one class for the capture-free sites, one for the sites that capture a String");
        Class<?> shared = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Shared");
        Supplier<?> a = supplier(shared, "a");
        Supplier<?> b = supplier(shared, "b");
        assertEquals("a", a.get());
        assertEquals("b", b.get());
        assertSame(a, supplier(shared, "a"), "a capture-free site yields one constant instance");
        assertNotSame(a, b, "distinct from every other site's");
        assertSame(a.getClass(), b.getClass(), "the sites share a class");
        Supplier<?> c = supplier(shared, "c", "1");
        Supplier<?> d = supplier(shared, "d", "2");
        assertEquals("c1", c.get());
        assertEquals("d2", d.get());
        assertNotSame(c, supplier(shared, "c", "1"), "a capturing site gets a new instance on each evaluation");
        assertSame(c.getClass(), d.getClass());
        assertFalse(a.getClass().isHidden());
    }

    @Test
    void capturesOfTheSameTypeKeepTheirOrderInASharedClass() throws Exception {
        Map<String, byte[]> output = desugar("order", 25, "grp.Order", """
                package grp;
                import java.util.List;
                import java.util.function.Supplier;
                public class Order {
                    public static List<Supplier<String>> ordered(String first, String second) {
                        Supplier<String> forward = () -> first + "<" + second;
                        Supplier<String> backward = () -> second + ">" + first;
                        return List.of(forward, backward);
                    }
                }
                """);

        assertEquals(Set.of("grp/Order$$Lambda$R0.class"), generated(output), "both sites capture (String, String)");
        Class<?> order = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Order");
        @SuppressWarnings("unchecked")
        List<Supplier<String>> suppliers = (List<Supplier<String>>) order.getMethod("ordered", String.class,
                String.class).invoke(null, "x", "y");
        assertEquals(List.of("x<y", "y>x"), suppliers.stream().map(Supplier::get).toList());
    }

    @Test
    void aSharedClassTakesSitesUntilItsInterfaceMethodWouldPassFreqInlineSize() throws Exception {
        StringBuilder source = new StringBuilder("""
                package grp;
                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Supplier;
                public class Converters {
                """);
        for (int i = 0; i < 68; i++) {
            source.append("    public static final class C").append(i).append(" { public String toString() {"
                    + " return \"c").append(i).append("\"; } }\n");
        }
        source.append("    public static List<Supplier<Object>> all() {\n");
        source.append("        List<Supplier<Object>> all = new ArrayList<>();\n");
        for (int i = 0; i < 68; i++) {
            source.append("        all.add(C").append(i).append("::new);\n");
        }
        source.append("        return all;\n    }\n}\n");

        Map<String, byte[]> output = desugar("converters", 25, "grp.Converters", source.toString());

        Set<String> classes = generated(output);
        assertEquals(3, classes.size(), "68 constructor references, as PatternLayout has: " + classes);
        for (String name : classes) {
            int length = samLength(output.get(name), "get");
            assertTrue(length <= LambdaClasses.MAX_DISPATCH_BYTES, name + " dispatches in " + length + " bytes");
            assertTrue(length > LambdaClasses.MAX_DISPATCH_BYTES - 30 || name.equals(last(classes)),
                    name + " is full before the next class starts: " + length);
        }
        Class<?> converters = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output)))
                .loadClass("grp.Converters");
        @SuppressWarnings("unchecked")
        List<Supplier<Object>> all = (List<Supplier<Object>>) converters.getMethod("all").invoke(null);
        for (int i = 0; i < 68; i++) {
            assertEquals("c" + i, all.get(i).get().toString());
        }
    }

    /**
     * {@code bipush} pushes the tag, so a class serves at most 128 sites; a {@code tableswitch} takes 4 bytes per tag,
     * so within {@code FreqInlineSize} a class serves fewer still.
     */
    @Test
    void aSharedClassTakesAtMost128Sites() throws Exception {
        StringBuilder source = new StringBuilder("""
                package grp;
                public class Many {
                    static void m() {
                    }
                    public static Runnable[] all() {
                        return new Runnable[] {
                """);
        for (int i = 0; i < 130; i++) {
            source.append("            Many::m,\n");
        }
        source.append("        };\n    }\n}\n");

        Map<String, byte[]> output = desugar("many", 25, "grp.Many", source.toString());

        // A rewritten site is the tag, pushed right before the call of create.
        int tags = 0;
        Integer pushed = null;
        for (CodeElement element : code(output.get("grp/Many.class"), "all")) {
            if (element instanceof InvokeInstruction invoke && invoke.name().equalsString("create")
                    && invoke.typeSymbol().descriptorString().endsWith("I)Ljava/lang/Runnable;") && pushed != null) {
                tags = Math.max(tags, pushed);
            }
            if (element instanceof Instruction) {
                pushed = element instanceof ConstantInstruction.ArgumentConstantInstruction push
                        ? push.constantValue() : null;
            }
        }
        assertTrue(tags < LambdaClasses.MAX_TAGS, "the largest tag " + tags);
        assertTrue(tags <= (LambdaClasses.MAX_DISPATCH_BYTES - 20) / 4, "the largest tag " + tags);
        assertTrue(generated(output).size() >= 2, generated(output)::toString);
        Class<?> many = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Many");
        for (Runnable runnable : (Runnable[]) many.getMethod("all").invoke(null)) {
            runnable.run();
        }
    }

    @Test
    void aSiteAloneInItsKeyKeepsItsOwnClassWithoutATag() throws Exception {
        Map<String, byte[]> output = desugar("alone", 25, "grp.Alone", """
                package grp;
                import java.util.function.Function;
                import java.util.function.Supplier;
                public class Alone {
                    public static Supplier<String> supplier() {
                        return () -> "alone";
                    }
                    public static Function<String, String> function() {
                        return value -> value + "!";
                    }
                }
                """);

        assertEquals(Set.of("grp/Alone$$Lambda$R0.class", "grp/Alone$$Lambda$R1.class"), generated(output));
        ClassModel alone = ClassFile.of().parse(output.get("grp/Alone$$Lambda$R0.class"));
        assertTrue(alone.fields().stream().noneMatch(field -> field.fieldName().equalsString("tag")),
                "a class of one site has no tag");
        List<String> call = new ArrayList<>();
        for (CodeElement element : code(output.get("grp/Alone.class"), "supplier")) {
            if (element instanceof Instruction instruction) {
                call.add(instruction.opcode().name());
            }
        }
        assertEquals(List.of("INVOKESTATIC", "NOP", "NOP", "ARETURN"), call);
    }

    @Test
    void sharedClassesOfAJava8HostCallEachSitesBridge() throws Exception {
        Map<String, byte[]> output = desugar("java8", 8, "grp.Old", """
                package grp;
                import java.util.function.Function;
                public class Old {
                    private final String prefix;
                    public Old(String prefix) {
                        this.prefix = prefix;
                    }
                    public Function<String, String> first() {
                        return value -> prefix + "1" + value;
                    }
                    public Function<String, String> second() {
                        return value -> prefix + "2" + value;
                    }
                }
                """);

        assertEquals(Set.of("grp/Old$$Lambda$R0.class"), generated(output));
        List<String> calls = new ArrayList<>();
        for (CodeElement element : code(output.get("grp/Old$$Lambda$R0.class"), "apply")) {
            if (element instanceof InvokeInstruction invoke) {
                calls.add(invoke.name().stringValue());
            }
        }
        assertEquals(List.of(LambdaClasses.BRIDGE_PREFIX + "0", LambdaClasses.BRIDGE_PREFIX + "1"), calls,
                "each case calls the bridge of its own site");
        Class<?> old = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Old");
        Object instance = old.getConstructor(String.class).newInstance("p");
        assertEquals("p1x", function(old, instance, "first").apply("x"));
        assertEquals("p2y", function(old, instance, "second").apply("y"));
    }

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 11, 25})
    void aSharedClassVerifiesWithItsExplicitFrames(int release) throws Exception {
        Map<String, byte[]> output = desugar("frames-" + release, release, "grp.Wide", """
                package grp;
                public class Wide {
                    public interface Fn {
                        Object apply(long number, String text, double ratio, int[] values);
                    }
                    public static Fn[] all() {
                        return new Fn[] {
                            (number, text, ratio, values) -> number + values[0],
                            (number, text, ratio, values) -> text + values.length,
                            (number, text, ratio, values) -> ratio * 2
                        };
                    }
                }
                """);

        assertEquals(Set.of("grp/Wide$$Lambda$R0.class"), generated(output));
        byte[] shared = output.get("grp/Wide$$Lambda$R0.class");
        ClassPathModel model = LambdaFixtures.model(List.of(LambdaFixtures.Layer.dependency("libs/wide.jar", output)));
        assertEquals(List.of(), ClassTransformPipeline.verifierOf(model).apply(shared),
                "the class verifies with the frames it was given, and no other");
        assertTrue(ClassFile.of().parse(shared).methods().stream()
                .filter(method -> method.methodName().equalsString("apply"))
                .allMatch(method -> method.code().orElseThrow().findAttribute(Attributes.stackMapTable())
                        .orElseThrow().entries().size() == 3), "one frame per case");
        Class<?> wide = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Wide");
        Object[] all = (Object[]) wide.getMethod("all").invoke(null);
        Method apply = wide.getClassLoader().loadClass("grp.Wide$Fn").getMethod("apply", long.class, String.class,
                double.class, int[].class);
        List<Object> results = new ArrayList<>();
        for (Object fn : all) {
            apply.setAccessible(true);
            results.add(apply.invoke(fn, 40L, "t", 1.25, new int[] {2, 3}));
        }
        assertEquals(List.of(42L, "t2", 2.5), results);
    }

    @Test
    void casesOfOneClassMayDifferInTheirInstantiatedType() throws Exception {
        Map<String, byte[]> output = desugar("instantiated", 25, "grp.Casts", """
                package grp;
                import java.util.List;
                import java.util.function.Function;
                public class Casts {
                    public static Function<String, Integer> length() {
                        return String::length;
                    }
                    public static Function<List<?>, Integer> size() {
                        return List::size;
                    }
                }
                """);

        assertEquals(Set.of("grp/Casts$$Lambda$R0.class"), generated(output));
        Class<?> casts = LambdaFixtures.loader(LambdaFixtures.classPath(List.of(output))).loadClass("grp.Casts");
        @SuppressWarnings("unchecked")
        Function<Object, Integer> length = (Function<Object, Integer>) casts.getMethod("length").invoke(null);
        @SuppressWarnings("unchecked")
        Function<Object, Integer> size = (Function<Object, Integer>) casts.getMethod("size").invoke(null);
        assertEquals(3, length.apply("abc"));
        assertEquals(2, size.apply(List.of(1, 2)));
        assertThrows(ClassCastException.class, () -> length.apply(List.of()), "each case casts to its own type");
        assertThrows(ClassCastException.class, () -> size.apply("abc"));
    }

    /**
     * The scenario, desugared, runs in a JVM that verifies every class it loads, the classes of the application loader
     * included, and gives the same lines as the classes the compiler wrote.
     */
    @Test
    void theDesugaredScenarioRunsUnderVerifyAll() throws Exception {
        for (int release : new int[] {8, 25}) {
            List<LambdaFixtures.Layer> layers = LambdaFixtures.scenario(temp.resolve("verify-" + release), release);
            LambdaFixtures.Outcome outcome = LambdaFixtures.transform(layers, true);
            Path original = write(temp.resolve("verify-" + release + "/original"),
                    layers.stream().map(LambdaFixtures.Layer::entries).toList());
            Path rewritten = write(temp.resolve("verify-" + release + "/rewritten"), outcome.outputs());

            List<String> expected = fork(original);
            List<String> actual = fork(rewritten);

            assertEquals(LambdaDesugarerTest.withoutHidden(expected), LambdaDesugarerTest.withoutHidden(actual),
                    "--release " + release);
            assertTrue(actual.contains("hidden=falsefalse"), actual::toString);
        }
    }

    private static List<String> fork(Path classes) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(java.toString(), "-Xverify:all", "-cp", classes.toString(),
                LambdaFixtures.APPLICATION).redirectErrorStream(true).start();
        process.getOutputStream().close();
        String text = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), text);
        return text.lines().filter(line -> !line.contains("VM warning")).toList();
    }

    private static Path write(Path directory, List<Map<String, byte[]>> layers) throws IOException {
        for (Map<String, byte[]> layer : layers) {
            for (Map.Entry<String, byte[]> entry : layer.entrySet()) {
                Path file = directory.resolve(entry.getKey());
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
        }
        return directory;
    }

    /**
     * Compiles one class, desugars it and returns what its entry became, after checking that nothing fell back.
     */
    private static Map<String, byte[]> desugar(String directory, int release, String type, String source)
            throws IOException {
        Map<String, byte[]> compiled = LambdaFixtures.compile(temp.resolve(directory), release,
                Map.of(type.replace('.', '/') + ".java", source));
        LambdaFixtures.Outcome outcome = LambdaFixtures.transform(
                List.of(LambdaFixtures.Layer.dependency("libs/" + directory + ".jar", compiled)), false);
        assertEquals(List.of(), outcome.reports().get(0).notes());
        assertEquals(Map.of(), outcome.reports().get(0).desugared().left(), "every site is rewritten");
        return outcome.outputs().get(0);
    }

    private static Set<String> generated(Map<String, byte[]> output) {
        Map<String, byte[]> generated = new TreeMap<>();
        output.forEach((name, bytes) -> {
            if (name.contains(LambdaClasses.GENERATED_INFIX)) {
                generated.put(name, bytes);
            }
        });
        return generated.keySet();
    }

    private static String last(Set<String> names) {
        String last = null;
        int highest = -1;
        for (String name : names) {
            int number = Integer.parseInt(name.substring(name.indexOf(LambdaClasses.GENERATED_INFIX)
                    + LambdaClasses.GENERATED_INFIX.length(), name.length() - ".class".length()));
            if (number > highest) {
                highest = number;
                last = name;
            }
        }
        return last;
    }

    private static int samLength(byte[] bytes, String name) {
        for (MethodModel method : ClassFile.of().parse(bytes).methods()) {
            if (method.methodName().equalsString(name) && !method.flags().has(AccessFlag.BRIDGE)) {
                return ((CodeAttribute) method.code().orElseThrow()).codeLength();
            }
        }
        throw new AssertionError("no method " + name);
    }

    private static Iterable<CodeElement> code(byte[] bytes, String method) {
        return LambdaDesugarerTest.code(bytes, method);
    }

    private static Supplier<?> supplier(Class<?> type, String method, Object... arguments) throws Exception {
        Class<?>[] parameters = new Class<?>[arguments.length];
        Arrays.fill(parameters, String.class);
        return (Supplier<?>) type.getMethod(method, parameters).invoke(null, arguments);
    }

    @SuppressWarnings("unchecked")
    private static Function<String, String> function(Class<?> type, Object instance, String method)
            throws Exception {
        return (Function<String, String>) type.getMethod(method).invoke(instance);
    }
}
