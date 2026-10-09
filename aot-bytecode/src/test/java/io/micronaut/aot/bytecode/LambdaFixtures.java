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
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The lambda fixtures of the desugaring tests, and the harness that runs the class transform pipeline over fixture
 * entries the way {@link ClassPathTransform} does: the class path is scanned into a model, each entry is planned,
 * and its classes are then written in order, a host's generated classes right after it.
 */
final class LambdaFixtures {

    /** The class whose static {@code run()} runs the scenario and its own lambdas. */
    static final String APPLICATION = "app.Main";

    /** The constant pool owner of a lambda call site's bootstrap. */
    static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private static final String SCENARIO_SOURCE = """
            package fix;

            import fix.util.Texts;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.util.ArrayList;
            import java.util.Arrays;
            import java.util.List;
            import java.util.Objects;
            import java.util.function.BiFunction;
            import java.util.function.Consumer;
            import java.util.function.Function;
            import java.util.function.IntToLongFunction;
            import java.util.function.Supplier;
            import java.util.function.ToIntFunction;

            public class Scenario {

                @Target(ElementType.TYPE_USE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Note {
                }

                public interface Shape {
                    String name();

                    default Supplier<String> described() {
                        return () -> "shape:" + name();
                    }

                    static Function<String, String> wrapping() {
                        return value -> "[" + value + "]";
                    }
                }

                public static final class Nested {
                    private final String label;

                    private Nested(String label) {
                        this.label = label;
                    }

                    private static String secret(String value) {
                        return "secret:" + value;
                    }

                    private String tagged(String value) {
                        return label + "/" + value;
                    }
                }

                public final class Inner {
                    private final int base;

                    Inner(int base) {
                        this.base = base;
                    }

                    List<String> compute() {
                        List<String> out = new ArrayList<>();
                        Function<Integer, Integer> add = value -> value + base + prefix.length();
                        out.add("inner=" + add.apply(10));
                        Supplier<String> outer = Scenario.this::hidden;
                        out.add("innerOuter=" + outer.get());
                        return out;
                    }
                }

                private final String prefix;

                public Scenario(String prefix) {
                    this.prefix = prefix;
                }

                static String staticImpl(String value) {
                    return "s:" + value;
                }

                String virtualImpl(String value) {
                    return prefix + ":" + value;
                }

                private String hidden() {
                    return "hidden:" + prefix;
                }

                private String privateImpl(String value) {
                    return "p:" + prefix + value;
                }

                static int increment(int value) {
                    return value + 1;
                }

                static long toLong(long value) {
                    return value * 2L;
                }

                int count() {
                    return prefix.length();
                }

                long wide() {
                    return 1L << 40;
                }

                static double half(float value) {
                    return value / 2.0;
                }

                static Supplier<String> constant() {
                    return () -> "constant";
                }

                static String annotated(List<String> values) {
                    Function<String, String> mark = value -> value + "!";
                    @Note String first = values.get(0);
                    String second = mark.apply(first);
                    return second;
                }

                Supplier<String> capturing(int number, long big, double ratio, String text) {
                    return () -> prefix + number + big + ratio + text;
                }

                public static List<String> run() throws Exception {
                    List<String> out = new ArrayList<>();
                    Scenario scenario = new Scenario("x");
                    Function<String, String> f1 = Scenario::staticImpl;
                    out.add("static=" + f1.apply("a"));
                    Function<String, String> f2 = scenario::virtualImpl;
                    out.add("bound=" + f2.apply("b"));
                    BiFunction<Scenario, String, String> f3 = Scenario::virtualImpl;
                    out.add("unbound=" + f3.apply(scenario, "c"));
                    Function<List<String>, Integer> f4 = List::size;
                    out.add("interface=" + f4.apply(Arrays.asList("1", "2")));
                    Function<String, String> f5 = scenario::privateImpl;
                    out.add("private=" + f5.apply("d"));
                    Function<String, String> f6 = Nested::secret;
                    out.add("nestmateStatic=" + f6.apply("e"));
                    Function<String, Nested> f7 = Nested::new;
                    Nested nested = f7.apply("n");
                    Function<String, String> f8 = nested::tagged;
                    out.add("nestmate=" + f8.apply("f"));
                    Supplier<ArrayList<String>> f9 = ArrayList::new;
                    Function<String, Scenario> f10 = Scenario::new;
                    out.add("constructors=" + f9.get().size() + f10.apply("y").virtualImpl("z"));
                    Function<Integer, Integer> f11 = Scenario::increment;
                    out.add("boxing=" + f11.apply(41));
                    Function<Integer, Long> f12 = Scenario::toLong;
                    out.add("widening=" + f12.apply(21));
                    IntToLongFunction f13 = Scenario::toLong;
                    out.add("primitiveWidening=" + f13.applyAsLong(4));
                    ToIntFunction<String> f14 = String::length;
                    out.add("checkcast=" + f14.applyAsInt("four"));
                    Supplier<Object> f15 = scenario::count;
                    out.add("boxedResult=" + f15.get());
                    Function<Float, Object> f16 = Scenario::half;
                    out.add("floatToDouble=" + f16.apply(3.0f));
                    List<String> sink = new ArrayList<>();
                    Consumer<String> f17 = sink::add;
                    f17.accept("dropped");
                    Runnable f18 = scenario::wide;
                    f18.run();
                    out.add("discarded=" + sink);
                    out.add("capturedThis=" + scenario.capturing(3, 5L, 1.5, "t").get());
                    out.add("identity=" + (constant() == constant()) + constant().get());
                    out.add("annotated=" + annotated(Arrays.asList("first")));
                    out.addAll(scenario.new Inner(7).compute());
                    Shape shape = () -> "square";
                    out.add("interfaceHost=" + shape.described().get() + Shape.wrapping().apply("w"));
                    Function<String, String> f19 = Texts::shout;
                    out.add("samePackageJar=" + f19.apply("loud"));
                    Function<String, String> f20 = other.Helper::twice;
                    out.add("otherJar=" + f20.apply("ab"));
                    Function<Object, String> f21 = String::valueOf;
                    Function<String, String> f22 = Objects::requireNonNull;
                    out.add("jdk=" + f21.apply(12) + f22.apply("nn"));
                    out.add("serializable=" + new Ser().make().get()
                            + java.io.ObjectStreamClass.lookup(Ser.class).getSerialVersionUID());
                    // Class.isHidden() is newer than the oldest release this source is compiled at.
                    java.lang.reflect.Method hidden = Class.class.getMethod("isHidden");
                    out.add("hidden=" + hidden.invoke(f1.getClass()) + hidden.invoke(constant().getClass()));
                    return out;
                }
            }
            """;

    private static final String SERIALIZABLE_SOURCE = """
            package fix;

            import java.io.Serializable;
            import java.util.function.Supplier;

            public class Ser implements Serializable {
                private static final long serialVersionUID = 7L;

                public Supplier<String> make() {
                    String local = "ser";
                    return () -> local + hidden();
                }

                private String hidden() {
                    return "!";
                }
            }
            """;

    private static final String TEXTS_SOURCE = """
            package fix.util;

            public class Texts {
                public static String shout(String value) {
                    return value.toUpperCase() + "!";
                }
            }
            """;

    private static final String HELPER_SOURCE = """
            package other;

            public class Helper {
                public static String twice(String value) {
                    return value + value;
                }
            }
            """;

    private static final String APPLICATION_SOURCE = """
            package app;

            import fix.Scenario;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Function;

            public class Main {
                public static List<String> run() throws Exception {
                    List<String> out = new ArrayList<>(Scenario.run());
                    Function<String, String> own = value -> "app:" + value;
                    Function<String, Scenario> dependency = Scenario::new;
                    out.add("application=" + own.apply("k") + (dependency.apply("q") != null));
                    return out;
                }

                public static void main(String[] args) throws Exception {
                    for (String line : run()) {
                        System.out.println(line);
                    }
                }
            }
            """;

    private LambdaFixtures() {
    }

    /**
     * Compiles the scenario at a release, with {@code -g}: the application's own classes, a library and a second
     * library the first one's lambdas call into.
     *
     * @param directory where the sources and the classes go
     * @param release   the {@code --release} to compile at
     * @return the three entries, the application's first
     * @throws IOException if the fixture does not compile
     */
    static List<Layer> scenario(Path directory, int release) throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fix/Scenario.java", SCENARIO_SOURCE);
        sources.put("fix/Ser.java", SERIALIZABLE_SOURCE);
        sources.put("fix/util/Texts.java", TEXTS_SOURCE);
        sources.put("other/Helper.java", HELPER_SOURCE);
        sources.put("app/Main.java", APPLICATION_SOURCE);
        Map<String, byte[]> compiled = compile(directory, release, sources);
        return List.of(
                Layer.application(select(compiled, "app/")),
                Layer.dependency("libs/fix.jar", select(compiled, "fix/")),
                Layer.dependency("libs/other.jar", select(compiled, "other/")));
    }

    /**
     * Compiles sources with {@code -g} at a release.
     *
     * @param directory where the sources and the classes go
     * @param release   the {@code --release} to compile at
     * @param sources   the sources, keyed by relative path
     * @return the classes, keyed by entry name, in name order
     * @throws IOException if the fixture does not compile
     */
    static Map<String, byte[]> compile(Path directory, int release, Map<String, String> sources)
            throws IOException {
        Files.createDirectories(directory);
        return ClassFixtures.classes(ClassFixtures.compile(directory.resolve("src"), directory.resolve("classes"),
                List.of("-g", "--release", Integer.toString(release)), sources));
    }

    /**
     * The entries of a compilation whose names start with a prefix, in name order.
     *
     * @param compiled the compiled classes
     * @param prefix   the prefix
     * @return the matching entries
     */
    static Map<String, byte[]> select(Map<String, byte[]> compiled, String prefix) {
        Map<String, byte[]> selected = new LinkedHashMap<>();
        compiled.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) {
                selected.put(name, bytes);
            }
        });
        return selected;
    }

    /**
     * Runs the pipeline over fixture entries.
     *
     * @param layers the entries, in class-path order; the model numbers them from zero
     * @param strip  whether the strip step runs after desugaring, over every entry but the application's
     * @return what each entry became and what the pipeline reports
     * @throws IOException never, from in-memory entries
     */
    static Outcome transform(List<Layer> layers, boolean strip) throws IOException {
        ClassPathModel model = model(layers);
        return transform(layers, model, ClassTransformPipeline.verifierOf(model),
                strip ? List.of(new LocalVariableStripper()) : List.of());
    }

    /**
     * Runs the pipeline over fixture entries with a verifier and later steps of the test's own.
     *
     * @param layers   the entries, in class-path order
     * @param model    the class path model of the entries
     * @param verifier returns the verification errors of a class
     * @param later    the steps that run after desugaring
     * @return what each entry became and what the pipeline reports
     * @throws IOException never, from in-memory entries
     */
    static Outcome transform(List<Layer> layers, ClassPathModel model, Function<byte[], List<String>> verifier,
                             List<ClassTransformPipeline.Step> later) throws IOException {
        List<ClassTransformPipeline.Step> steps = new ArrayList<>();
        steps.add(new LambdaDesugarer(model, Set.of()));
        steps.addAll(later);
        ClassTransformPipeline pipeline = new ClassTransformPipeline(steps, model, verifier);
        List<Map<String, byte[]>> outputs = new ArrayList<>();
        List<ClassTransformPipeline.JarReport> reports = new ArrayList<>();
        for (int index = 0; index < layers.size(); index++) {
            Layer layer = layers.get(index);
            ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer(layer.name, index,
                    layer.signed, !layer.application));
            run.plan(new InMemory(layer));
            Map<String, byte[]> output = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> entry : layer.entries.entrySet()) {
                String name = entry.getKey();
                if (!name.endsWith(".class")) {
                    output.put(name, entry.getValue());
                    continue;
                }
                ClassTransformPipeline.Planned planned = run.planned(name);
                if (planned != null) {
                    output.put(name, planned.bytes());
                    for (ClassTransformPipeline.Generated generated : planned.generated()) {
                        output.put(generated.name(), generated.bytes());
                    }
                } else if (run.reads(entry.getValue().length)) {
                    output.put(name, run.process(name, entry.getValue()));
                } else {
                    run.pass(name);
                    output.put(name, entry.getValue());
                }
            }
            outputs.add(output);
            reports.add(run.report());
        }
        return new Outcome(outputs, reports, pipeline);
    }

    /**
     * Scans fixture entries into a class path model with member tables.
     *
     * @param layers the entries, in class-path order
     * @return the model
     */
    static ClassPathModel model(List<Layer> layers) {
        ClassPathModel.Interner strings = new ClassPathModel.Interner();
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        for (Layer layer : layers) {
            ClassPathModel.LayerScan scan = ClassPathModel.scan(layer.name, layer.multiRelease, true,
                    name -> false, strings);
            layer.entries.forEach((name, bytes) -> {
                if (scan.wants(name, bytes.length)) {
                    scan.accept(name, bytes);
                }
            });
            scans.add(scan);
        }
        return ClassPathModel.merge(scans);
    }

    /**
     * The classes a runtime would load from some entries: the first copy of each base entry, by binary name.
     *
     * @param layers what each entry holds, in class-path order
     * @return the classes
     */
    static Map<String, byte[]> classPath(List<Map<String, byte[]>> layers) {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (Map<String, byte[]> layer : layers) {
            layer.forEach((name, bytes) -> {
                if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                    classes.putIfAbsent(name.substring(0, name.length() - 6).replace('/', '.'), bytes);
                }
            });
        }
        return classes;
    }

    /**
     * The classes the scenario's original entries hold, as a runtime would load them.
     *
     * @param layers the entries
     * @return the classes
     */
    static Map<String, byte[]> original(List<Layer> layers) {
        return classPath(layers.stream().map(Layer::entries).toList());
    }

    /**
     * A loader that defines the given classes itself, and verifies them as any application loader does.
     *
     * @param classes the classes, by binary name
     * @return the loader
     */
    static ClassLoader loader(Map<String, byte[]> classes) {
        return new ClassLoader(ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /**
     * Calls the static {@code run()} of a class and returns the lines it produced.
     *
     * @param classes the class path
     * @param type    the class to run
     * @return the lines
     * @throws Exception if the class cannot be loaded or its {@code run()} fails
     */
    @SuppressWarnings("unchecked")
    static List<String> run(Map<String, byte[]> classes, String type) throws Exception {
        return (List<String>) loader(classes).loadClass(type).getMethod("run").invoke(null);
    }

    /**
     * The number of call sites of a class that a bootstrap method of the given class links.
     *
     * @param bytes     the class
     * @param bootstrap the internal name of the bootstrap method's class, such as
     *                  {@code java/lang/invoke/LambdaMetafactory}
     * @return the number of such {@code invokedynamic} instructions
     */
    static int sites(byte[] bytes, String bootstrap) {
        ClassModel model = ClassFile.of().parse(bytes);
        int sites = 0;
        for (MethodModel method : model.methods()) {
            if (method.code().isEmpty()) {
                continue;
            }
            for (CodeElement element : method.code().get()) {
                if (element instanceof InvokeDynamicInstruction indy && indy.bootstrapMethod().owner()
                        .descriptorString().equals("L" + bootstrap + ";")) {
                    sites++;
                }
            }
        }
        return sites;
    }

    /**
     * One entry of a fixture class path.
     */
    static final class Layer {

        private final String name;
        private final Map<String, byte[]> entries;
        private final boolean application;
        private final boolean multiRelease;
        private final boolean signed;

        private Layer(String name, Map<String, byte[]> entries, boolean application, boolean multiRelease,
                      boolean signed) {
            this.name = name;
            this.entries = entries;
            this.application = application;
            this.multiRelease = multiRelease;
            this.signed = signed;
        }

        static Layer application(Map<String, byte[]> entries) {
            return new Layer("build/classes", entries, true, false, false);
        }

        static Layer dependency(String name, Map<String, byte[]> entries) {
            return new Layer(name, entries, false, false, false);
        }

        static Layer multiRelease(String name, Map<String, byte[]> entries) {
            return new Layer(name, entries, false, true, false);
        }

        static Layer signed(String name, Map<String, byte[]> entries) {
            return new Layer(name, entries, false, false, true);
        }

        Map<String, byte[]> entries() {
            return entries;
        }
    }

    /**
     * What a pipeline run produced.
     *
     * @param outputs  the entries of each class path entry, in the order they would be written
     * @param reports  the pipeline's report on each class path entry
     * @param pipeline the pipeline that ran
     */
    record Outcome(List<Map<String, byte[]>> outputs, List<ClassTransformPipeline.JarReport> reports,
                   ClassTransformPipeline pipeline) {
    }

    /**
     * An entry's classes, as a planning step reads them.
     */
    private static final class InMemory implements ClassTransformPipeline.JarClasses {

        private final Layer layer;

        private InMemory(Layer layer) {
            this.layer = layer;
        }

        @Override
        public List<ClassTransformPipeline.ClassEntry> classes() {
            List<ClassTransformPipeline.ClassEntry> classes = new ArrayList<>();
            layer.entries.forEach((name, bytes) -> {
                if (name.endsWith(".class")) {
                    classes.add(new ClassTransformPipeline.ClassEntry(name, bytes.length));
                }
            });
            return classes;
        }

        @Override
        public long size(String entryName) {
            byte[] bytes = layer.entries.get(entryName);
            return bytes == null ? -1 : bytes.length;
        }

        @Override
        public boolean multiRelease() {
            return layer.multiRelease;
        }

        @Override
        public boolean kept() {
            return false;
        }

        @Override
        public byte[] read(String entryName) {
            return layer.entries.get(entryName);
        }
    }
}
