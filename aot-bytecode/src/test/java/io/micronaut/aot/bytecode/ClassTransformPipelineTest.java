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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.classfile.Attribute;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.attribute.UnknownAttribute;
import java.lang.classfile.constantpool.ConstantPool;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassTransformPipeline} with synthetic steps: how steps compose, which constant pool a rewritten
 * class gets, and what the gate and the fallback rule do when a step fails or makes a class verify worse.
 */
class ClassTransformPipelineTest {

    private static final String SUBJECT = "fixture/Subject";

    private static final String SOURCE = """
            package fixture;

            public class Subject {
                public static String run(Object value) {
                    String text = (String) value;
                    int count = 1;
                    String prefix = "a";
                    if (text.isEmpty()) {
                        return prefix;
                    }
                    return prefix.concat(Integer.toString(text.length() * count));
                }
            }
            """;

    private static final ClassTransformPipeline.Layer DEPENDENCY =
            new ClassTransformPipeline.Layer("libs/fixture.jar", false, true);

    @TempDir
    static Path temp;

    private static byte[] subject;
    private static ClassPathModel model;

    @BeforeAll
    static void compileTheSubject() throws Exception {
        Path classes = ClassFixtures.compile(temp.resolve("src"), temp.resolve("classes"), List.of("-g"),
                ClassFixtures.source("fixture.Subject", SOURCE));
        subject = ClassFixtures.classes(classes).get(SUBJECT + ".class");
        model = modelOf(Map.of(SUBJECT + ".class", subject));
        assertEquals("a1", run(subject, "x"));
    }

    @Test
    void aStepThatMakesTheClassVerifyWorseFallsBackToTheOriginalBytes() {
        Step dropCheckcast = new Step("dropCheckcast", false, element -> element instanceof TypeCheckInstruction
                check && check.opcode() == Opcode.CHECKCAST, (builder, element) -> { });
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(dropCheckcast), model);

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);
        ClassTransformPipeline.JarReport report = run.report();

        assertSame(subject, output, "the original bytes are written");
        assertEquals(1, dropCheckcast.hits.get(), "the step did run");
        assertEquals(List.of(new ClassTransformPipeline.StepCount("dropCheckcast", 0, 0, 1, 0)), report.counts());
        assertEquals(1, report.notes().size(), report.notes()::toString);
        String[] note = report.notes().get(0).split("\t");
        assertEquals(DEPENDENCY.name(), note[0], "the note names the jar");
        assertEquals(SUBJECT + ".class", note[1], "the note names the class");
        assertEquals("dropCheckcast", note[2], "the note names the dropped step");
        assertTrue(note[3].startsWith("verification: "), note[3]);
    }

    @Test
    void aThrowingStepAfterAWorkingStepIsDroppedAndTheWorkingStepsChangeStays() throws Exception {
        Step rewriteLdc = rewriteLdc(false);
        Step throwing = new Step("throwing", false, element -> element instanceof ConstantInstruction,
                (builder, element) -> {
                    throw new IllegalStateException("the synthetic step fails");
                });
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(rewriteLdc, throwing), model);

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);
        ClassTransformPipeline.JarReport report = run.report();

        assertEquals("b1", run(output, "x"), "the working step's change is kept");
        assertEquals(List.of(new ClassTransformPipeline.StepCount("rewriteLdc", 1, 0, 0,
                        subject.length - output.length),
                new ClassTransformPipeline.StepCount("throwing", 0, 0, 1, 0)), report.counts());
        assertEquals(1, report.notes().size(), report.notes()::toString);
        String[] note = report.notes().get(0).split("\t");
        assertEquals("throwing", note[2]);
        assertEquals("IllegalStateException: the synthetic step fails", note[3]);
    }

    @ParameterizedTest(name = "rebuilt pool: {0}")
    @ValueSource(booleans = {false, true})
    void twoStepsRewritingTheSameMethodBothTakeEffectWithExactHitCounts(boolean newPool) throws Exception {
        Step rewriteLdc = rewriteLdc(newPool);
        Step rewriteIconst = new Step("rewriteIconst", false,
                element -> element instanceof ConstantInstruction constant && constant.opcode() == Opcode.ICONST_1,
                (builder, element) -> builder.iconst_2());
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(rewriteLdc, rewriteIconst), model);

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);

        assertEquals("b2", run(output, "x"), "both rewrites took effect");
        assertEquals(1, rewriteLdc.hits.get());
        assertEquals(1, rewriteIconst.hits.get());
        ClassTransformPipeline.JarReport report = run.report();
        assertEquals(1, report.counts().get(0).rewritten());
        assertEquals(1, report.counts().get(1).rewritten());
        assertEquals(List.of(), report.notes());
        ClassModel rewritten = ClassFile.of().parse(output);
        CodeModel code = code(rewritten, "run");
        boolean localVariables = code.findAttribute(Attributes.localVariableTable()).isPresent();
        assertEquals(!newPool, localVariables, "debug elements are dropped only with a rebuilt pool");
        assertTrue(code.findAttribute(Attributes.stackMapTable()).isPresent(), "the frames are attached again");
        if (!newPool) {
            assertSharedPool(ClassFile.of().parse(subject).constantPool(), rewritten.constantPool());
        }
    }

    @Test
    void aClassWithAnUnknownAttributeKeepsASharedPoolAndTheAttributeBytes() throws Exception {
        byte[] withUnknown = ClassFixtures.withUnknownAttribute(subject);
        Step rewriteIconst = new Step("rewriteIconst", false,
                element -> element instanceof ConstantInstruction constant && constant.opcode() == Opcode.ICONST_1,
                (builder, element) -> builder.iconst_2());
        LocalVariableStripper strip = new LocalVariableStripper();
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(rewriteIconst, strip), model);

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", withUnknown);

        assertEquals("a2", run(output, "x"));
        ClassModel original = ClassFile.of().parse(withUnknown);
        ClassModel rewritten = ClassFile.of().parse(output);
        assertSharedPool(original.constantPool(), rewritten.constantPool());
        assertArrayEquals(unknown(original).contents(), unknown(rewritten).contents(),
                "the attribute is copied verbatim");
        int index = ((unknown(rewritten).contents()[0] & 0xFF) << 8) | (unknown(rewritten).contents()[1] & 0xFF);
        assertEquals(ClassFixtures.UNKNOWN_ATTRIBUTE_TARGET,
                rewritten.constantPool().entryByIndex(index).toString(),
                "and its pool index still names what it named");
        assertTrue(code(rewritten, "run").findAttribute(Attributes.localVariableTable()).isPresent(),
                "stripping declined the class");
        ClassTransformPipeline.JarReport report = run.report();
        assertEquals(new ClassTransformPipeline.StepCount("rewriteIconst", 1, 0, 0,
                withUnknown.length - output.length), report.counts().get(0));
        assertEquals(new ClassTransformPipeline.StepCount(LocalVariableStripper.NAME, 0, 1, 0, 0),
                report.counts().get(1));
    }

    @Test
    void aRewriteThatVerifiesCleanlyIsAcceptedWithoutVerifyingTheOriginal() {
        List<byte[]> verified = new ArrayList<>();
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(model);
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model,
                bytes -> {
                    verified.add(bytes);
                    return verifier.apply(bytes);
                });

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);

        assertNotEquals(subject.length, output.length, "the class was stripped");
        assertEquals(1, verified.size(), "only the rewritten class is verified");
        assertSame(output, verified.get(0));
    }

    @Test
    void aRewriteWithOnlyTheErrorsItsOriginalAlreadyHasIsAccepted() throws Exception {
        // Optional is compiled but left off the class path, as an optional dependency is: the verifier cannot
        // tell whether it is a Base, so User fails verification before any step touches it.
        Map<String, byte[]> classes = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("missing/src"),
                temp.resolve("missing/classes"), List.of("-g"), Map.of(
                        "fixture/missing/Base.java", "package fixture.missing; public class Base { }\n",
                        "fixture/missing/Optional.java",
                        "package fixture.missing; public class Optional extends Base { }\n",
                        "fixture/missing/User.java", """
                                package fixture.missing;
                                public class User {
                                    public static Base pick(Optional optional, boolean flag) {
                                        Base chosen = optional;
                                        String label = flag ? "a" : "b";
                                        return label.isEmpty() ? null : chosen;
                                    }
                                }
                                """)));
        String entry = "fixture/missing/User.class";
        byte[] user = classes.get(entry);
        ClassPathModel partial = modelOf(Map.of("fixture/missing/Base.class",
                classes.get("fixture/missing/Base.class"), entry, user));
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(partial);
        List<String> before = verifier.apply(user);
        assertFalse(before.isEmpty(), "the fixture fails verification as compiled");
        List<byte[]> verified = new ArrayList<>();
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), partial,
                bytes -> {
                    verified.add(bytes);
                    return verifier.apply(bytes);
                });

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(entry, user);
        ClassTransformPipeline.JarReport report = run.report();

        assertTrue(output.length < user.length, "the class was stripped");
        assertEquals(before, verifier.apply(output), "the rewrite fails exactly as the original does");
        assertEquals(2, verified.size(), "the original is verified because the rewrite has errors");
        assertSame(output, verified.get(0));
        assertSame(user, verified.get(1));
        assertEquals(List.of(new ClassTransformPipeline.StepCount(LocalVariableStripper.NAME, 1, 0, 0,
                user.length - output.length)), report.counts());
        assertEquals(List.of(), report.notes());
    }

    @Test
    void anErrorTheOriginalAlreadyHasIsNotGrowthWhenARebuiltPoolMovesItsOffset() throws Exception {
        // More than 256 constants ahead of the broken instruction: a rebuilt pool chooses ldc or ldc_w afresh
        // for them, which moves every later instruction.
        StringBuilder constants = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            constants.append(i == 0 ? "" : ", ").append("\"constant-").append(i).append('"');
        }
        String entry = "fixture/shifted/Shifted.class";
        byte[] compiled = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("shifted/src"),
                temp.resolve("shifted/classes"), List.of("-g"), ClassFixtures.source("fixture.shifted.Shifted", """
                        package fixture.shifted;
                        public class Shifted {
                            public static int run(Object value) {
                                String[] all = {@constants@};
                                String text = (String) value;
                                return all.length + text.length();
                            }
                        }
                        """.replace("@constants@", constants)))).get(entry);
        // Without its checkcast the method calls String.length() on an Object: an error the verifier reports at
        // the offset of that call. The method has no branch, so it needs no frames.
        ClassFile context = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        byte[] broken = context.transformClass(context.parse(compiled), ClassTransform.transformingMethodBodies(
                (builder, element) -> {
                    if (!(element instanceof TypeCheckInstruction)) {
                        builder.with(element);
                    }
                }));
        ClassPathModel shifted = modelOf(Map.of(entry, broken));
        Function<byte[], List<String>> verifier = ClassTransformPipeline.verifierOf(shifted);
        List<String> before = verifier.apply(broken);
        assertEquals(1, before.size(), before::toString);
        byte[] stripped = new ClassTransformPipeline(List.of(new LocalVariableStripper()), shifted,
                bytes -> List.of()).start(DEPENDENCY).process(entry, broken);
        List<String> after = verifier.apply(stripped);
        assertEquals(1, after.size(), after::toString);
        Assumptions.assumeFalse(before.equals(after),
                "the class file library of this JDK left the broken instruction at its offset");

        ClassTransformPipeline.JarRun run = new ClassTransformPipeline(List.of(new LocalVariableStripper()),
                shifted).start(DEPENDENCY);
        byte[] output = run.process(entry, broken);
        ClassTransformPipeline.JarReport report = run.report();

        assertArrayEquals(stripped, output, "the stripped class is written");
        assertEquals(List.of(new ClassTransformPipeline.StepCount(LocalVariableStripper.NAME, 1, 0, 0,
                broken.length - output.length)), report.counts());
        assertEquals(List.of(), report.notes(), "the error moved, it did not appear");
    }

    @Test
    void theGateComparesErrorsWithoutTheirBytecodeOffsets() {
        String moved = "Bad type on operand stack in fixture/Subject::run(Object) @%d";
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model,
                bytes -> List.of(moved.formatted(bytes == subject ? 41 : 42)));

        ClassTransformPipeline.JarRun run = pipeline.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);

        assertTrue(output.length < subject.length, "the class was stripped");
        assertEquals(List.of(), run.report().notes());
        assertEquals(0, run.report().counts().get(0).fallbacks());

        String first = "Bad type on operand stack in fixture/Subject::run(Object) @41 (current frame)";
        String second = "Bad type on operand stack in fixture/Subject::run(Object) @57 (current frame)";
        String other = "Bad type on operand stack in fixture/Subject::other() @41 (current frame)";
        String unresolved = "java.lang.IllegalArgumentException: Could not resolve class Optional";
        assertNull(ClassTransformPipeline.grown(List.of(), List.of(first)));
        assertNull(ClassTransformPipeline.grown(List.of(second), List.of(first)), "the same error, moved");
        assertNull(ClassTransformPipeline.grown(List.of(unresolved, second), List.of(first, unresolved)));
        assertEquals(first, ClassTransformPipeline.grown(List.of(first), List.of()));
        assertEquals(other, ClassTransformPipeline.grown(List.of(second, other), List.of(first)),
                "an error in another method is new");
        assertEquals(second, ClassTransformPipeline.grown(List.of(first, second), List.of(first)),
                "each error of the original accounts for one error of the rewrite");
    }

    @Test
    void aStepThatSkipsALargerOutputLeavesTheClassAloneOnlyWhenItIsTheOnlyChange() throws Exception {
        String longer = "a constant that is much longer than the one it replaces";
        Step lengthen = new Step("lengthen", false,
                element -> element instanceof ConstantInstruction.LoadConstantInstruction load
                        && "a".equals(load.constantValue()),
                (builder, element) -> builder.ldc(longer)).skippingLargerOutput();
        ClassTransformPipeline alone = new ClassTransformPipeline(List.of(lengthen), model);

        ClassTransformPipeline.JarRun run = alone.start(DEPENDENCY);
        byte[] output = run.process(SUBJECT + ".class", subject);

        assertSame(subject, output, "the larger output is thrown away");
        assertEquals(1, lengthen.hits.get(), "the step did run");
        assertEquals(List.of(new ClassTransformPipeline.StepCount("lengthen", 0, 1, 0, 0)), run.report().counts());
        assertEquals(List.of(), run.report().notes(), "a skip is not a fallback");

        Step rewriteIconst = new Step("rewriteIconst", false,
                element -> element instanceof ConstantInstruction constant && constant.opcode() == Opcode.ICONST_1,
                (builder, element) -> builder.iconst_2());
        ClassTransformPipeline together = new ClassTransformPipeline(List.of(lengthen, rewriteIconst), model);

        ClassTransformPipeline.JarRun second = together.start(DEPENDENCY);
        byte[] both = second.process(SUBJECT + ".class", subject);

        assertTrue(both.length > subject.length, "with another change the larger output is kept");
        assertEquals(longer + "2", run(both, "x"));
        assertEquals(List.of(
                new ClassTransformPipeline.StepCount("lengthen", 1, 0, 0, subject.length - both.length),
                new ClassTransformPipeline.StepCount("rewriteIconst", 1, 0, 0, subject.length - both.length)),
                second.report().counts());
        assertTrue(new LocalVariableStripper().skipsLargerOutput(), "stripping is such a step");
    }

    @Test
    void noStepAppliesToASignedJar() {
        Step rewriteLdc = rewriteLdc(false);
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(rewriteLdc), model);

        ClassTransformPipeline.JarRun run = pipeline.start(
                new ClassTransformPipeline.Layer("libs/signed.jar", true, true));

        assertFalse(run.applies(), "no step applies to a signed jar");
        assertFalse(run.reads(subject.length), "a signed jar's classes are never read into memory");
        run.pass();
        assertEquals(List.of(new ClassTransformPipeline.StepCount("rewriteLdc", 0, 1, 0, 0)), run.report().counts());
        assertEquals(0, rewriteLdc.hits.get());
    }

    private static ClassPathModel modelOf(Map<String, byte[]> classes) {
        ClassPathModel.LayerScan scan = ClassPathModel.scan("fixture", false, name -> false);
        classes.forEach(scan::accept);
        return ClassPathModel.merge(List.of(scan));
    }

    private static Step rewriteLdc(boolean newPool) {
        return new Step("rewriteLdc", newPool,
                element -> element instanceof ConstantInstruction.LoadConstantInstruction load
                        && "a".equals(load.constantValue()),
                (builder, element) -> builder.ldc("b"));
    }

    private static void assertSharedPool(ConstantPool original, ConstantPool rewritten) {
        assertTrue(rewritten.size() >= original.size(), "a shared pool only grows");
        for (int index = 1; index < original.size(); index++) {
            if (original.entryByIndex(index).width() == 2) {
                assertEquals(original.entryByIndex(index).toString(), rewritten.entryByIndex(index).toString());
                index++;
            } else {
                assertEquals(original.entryByIndex(index).toString(), rewritten.entryByIndex(index).toString(),
                        "entry " + index);
            }
        }
    }

    private static UnknownAttribute unknown(ClassModel model) {
        for (Attribute<?> attribute : model.attributes()) {
            if (attribute instanceof UnknownAttribute unknown
                    && unknown.attributeName().equalsString(ClassFixtures.UNKNOWN_ATTRIBUTE)) {
                return unknown;
            }
        }
        throw new AssertionError("no " + ClassFixtures.UNKNOWN_ATTRIBUTE);
    }

    private static CodeModel code(ClassModel model, String method) {
        for (MethodModel candidate : model.methods()) {
            if (candidate.methodName().equalsString(method)) {
                return candidate.code().orElseThrow();
            }
        }
        throw new AssertionError("no method " + method);
    }

    /** Defines a class in a fresh loader and calls its {@code run(Object)}. */
    private static String run(byte[] bytes, Object argument) throws Exception {
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (name.equals(SUBJECT.replace('/', '.'))) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
                throw new ClassNotFoundException(name);
            }
        };
        Method method = loader.loadClass(SUBJECT.replace('/', '.')).getMethod("run", Object.class);
        return (String) method.invoke(null, argument);
    }

    @Test
    void theStepsKeepTheirOrderInTheTotals() {
        ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(rewriteLdc(false),
                new LocalVariableStripper()), model);
        ClassTransformPipeline.JarRun first = pipeline.start(DEPENDENCY);
        first.process(SUBJECT + ".class", subject);
        ClassTransformPipeline.JarRun second = pipeline.start(DEPENDENCY);
        second.pass();

        List<ClassTransformPipeline.StepCount> totals = pipeline.totals(List.of(first.report(), second.report()));

        assertEquals(List.of("rewriteLdc", LocalVariableStripper.NAME),
                totals.stream().map(ClassTransformPipeline.StepCount::step).toList());
        assertEquals(2, totals.get(0).classes());
        assertEquals(1, totals.get(0).rewritten());
        assertEquals(1, totals.get(1).rewritten());
        assertEquals(1, totals.get(1).unchanged());
    }

    /**
     * A step that rewrites every instruction a predicate matches, counting each rewrite.
     */
    private static final class Step implements ClassTransformPipeline.Step {

        private final String name;
        private final boolean newPool;
        private final Predicate<CodeElement> target;
        private final CodeTransform replacement;
        private final AtomicInteger hits = new AtomicInteger();
        private boolean skipsLargerOutput;

        private Step(String name, boolean newPool, Predicate<CodeElement> target, CodeTransform replacement) {
            this.name = name;
            this.newPool = newPool;
            this.target = target;
            this.replacement = replacement;
        }

        private Step skippingLargerOutput() {
            skipsLargerOutput = true;
            return this;
        }

        @Override
        public boolean skipsLargerOutput() {
            return skipsLargerOutput;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean appliesTo(ClassTransformPipeline.Layer layer) {
            return true;
        }

        @Override
        public boolean matches(String entryName, byte[] bytes) {
            return true;
        }

        @Override
        public boolean changes(ClassModel model) {
            for (MethodModel method : model.methods()) {
                if (method.code().isPresent() && method.code().get().elementStream().anyMatch(target)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean rebuildsConstantPool() {
            return newPool;
        }

        @Override
        public ClassTransform transform(ClassModel model) {
            return ClassTransform.transformingMethodBodies((builder, element) -> {
                if (target.test(element)) {
                    hits.incrementAndGet();
                    replacement.accept(builder, element);
                } else {
                    builder.with(element);
                }
            });
        }

        @Override
        public String summary(ClassTransformPipeline.StepCount total, int jars) {
            return name + ": " + total;
        }
    }
}
