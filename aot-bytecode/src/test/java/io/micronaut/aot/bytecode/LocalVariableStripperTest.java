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
import java.lang.annotation.Annotation;
import java.lang.classfile.Attribute;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.LineNumber;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strip step as a build plugin runs it: {@code -g}-compiled third-party classes rewritten by
 * {@link ClassPathTransform}, read back out of the copy it writes and compared, in behaviour and in structure, with
 * the classes as compiled.
 */
class LocalVariableStripperTest {

    private static final String FIXTURE = "fixture.Stripped";

    private static final String FIXTURE_ENTRY = "fixture/Stripped.class";

    private static final String INVISIBLE_TYPE_ANNOTATIONS = "RuntimeInvisibleTypeAnnotations";

    private static final String VISIBLE_TYPE_ANNOTATIONS = "RuntimeVisibleTypeAnnotations";

    @TempDir
    static Path temp;

    private static Path applicationClasses;

    @BeforeAll
    static void compileTheApplication() throws IOException {
        applicationClasses = ClassFixtures.compile(temp.resolve("app-src"), temp.resolve("app-classes"),
                List.of("--release", "25"), ClassFixtures.source("app.Main", """
                        package app;
                        public class Main {
                            public static void main(String[] args) {
                            }
                        }
                        """));
    }

    @ParameterizedTest(name = "--release {0}")
    @ValueSource(ints = {8, 11, 17, 25})
    void strippedClassesBehaveAsCompiledAndKeepWhatReflectionAndStackTracesRead(int release) throws Exception {
        Map<String, byte[]> original = fixture(release, "release-" + release, List.of("-g", "-parameters"));
        Path dependency = ClassFixtures.jar(temp.resolve("release-" + release + "/fixture.jar"), original);

        ClassPathTransform.Result result = strip(temp.resolve("release-" + release + "/out"), dependency);
        Map<String, byte[]> stripped = classes(result.classPath().get(1));

        ClassTransformPipeline.StepCount report = counts(result, dependency);
        assertEquals(LocalVariableStripper.NAME, report.step());
        assertEquals(0, report.fallbacks(), result::report);
        assertTrue(report.rewritten() >= 1, result::report);
        assertEquals(original.keySet(), stripped.keySet(), "no class is added or removed");
        assertTrue(stripped.get(FIXTURE_ENTRY).length < original.get(FIXTURE_ENTRY).length, "the class shrank");

        for (Map.Entry<String, byte[]> entry : stripped.entrySet()) {
            ClassModel before = ClassFile.of().parse(original.get(entry.getKey()));
            ClassModel after = ClassFile.of().parse(entry.getValue());
            assertEquals(before.majorVersion(), after.majorVersion(), entry.getKey());
            assertStructure(entry.getKey(), before, after);
        }
        assertTypeAnnotations(ClassFile.of().parse(original.get(FIXTURE_ENTRY)),
                ClassFile.of().parse(stripped.get(FIXTURE_ENTRY)));

        Class<?> compiled = load(original);
        Class<?> rewritten = load(stripped);
        assertEquals(behaviour(compiled), behaviour(rewritten), "the same results");
        assertEquals(reflection(compiled), reflection(rewritten), "the same reflective view");
        Method add = rewritten.getMethod("add", Comparable.class, int.class);
        assertEquals(List.of("item", "weight"), Arrays.stream(add.getParameters()).map(Parameter::getName).toList(),
                "MethodParameters is kept");
        assertTrue(add.getParameters()[0].isNamePresent());
        assertEquals(1, add.getAnnotatedReturnType().getAnnotations().length,
                "the visible type annotation of a return type is kept");
        AnnotatedParameterizedType items =
                (AnnotatedParameterizedType) rewritten.getDeclaredField("items").getAnnotatedType();
        assertEquals(1, items.getAnnotatedActualTypeArguments()[0].getAnnotations().length,
                "the visible type annotation of a field is kept");

        StackTraceElement thrownAsCompiled = thrownFrom(compiled);
        StackTraceElement thrownStripped = thrownFrom(rewritten);
        assertEquals("Stripped.java", thrownStripped.getFileName());
        assertEquals(thrownAsCompiled.getLineNumber(), thrownStripped.getLineNumber());
        assertTrue(thrownStripped.getLineNumber() > 0);
        assertTrue(thrownStripped.toString().endsWith("(Stripped.java:" + thrownAsCompiled.getLineNumber() + ")"),
                thrownStripped::toString);
    }

    @Test
    void aClassWithAnUnknownAttributeTheClassesOfASignedJarAndModuleInfoAreLeftAlone() throws Exception {
        Map<String, byte[]> classes = fixture(25, "left-alone", List.of("-g"));
        byte[] withUnknown = ClassFixtures.withUnknownAttribute(classes.get(FIXTURE_ENTRY));
        byte[] moduleInfo = moduleInfo();

        Map<String, byte[]> plainEntries = new LinkedHashMap<>();
        plainEntries.put(FIXTURE_ENTRY, withUnknown);
        plainEntries.put("module-info.class", moduleInfo);
        plainEntries.put("META-INF/versions/11/module-info.class", moduleInfo);
        Path plain = ClassFixtures.jar(temp.resolve("left-alone/plain.jar"), plainEntries);

        Map<String, byte[]> signedEntries = new LinkedHashMap<>(classes);
        signedEntries.put("META-INF/SIGNER.SF", ClassFixtures.utf8("Signature-Version: 1.0\n"));
        signedEntries.put("META-INF/SIGNER.RSA", new byte[] {1, 2, 3});
        Path signed = ClassFixtures.jar(temp.resolve("left-alone/signed.jar"), signedEntries);

        Path output = temp.resolve("left-alone/out");
        ClassPathTransform.Result result = strip(output, plain, signed);

        assertEquals(List.of(applicationClasses, plain, signed), result.classPath(),
                "an unknown attribute declines the class, and a signed jar and module-info are left alone");
        assertEquals(List.of(), ClassPathTransformTest.written(output), "nothing is written");
        assertTrue(result.report().contains("kept\t" + signed + "\tthe jar is signed\n"), result::report);
        ClassTransformPipeline.StepCount plainCounts = counts(result, plain);
        ClassTransformPipeline.StepCount signedCounts = counts(result, signed);
        assertEquals(0, plainCounts.rewritten() + signedCounts.rewritten(), result::report);
        assertEquals(0, plainCounts.fallbacks() + signedCounts.fallbacks(), result::report);
        assertEquals(3, plainCounts.unchanged(), result::report);
        assertEquals(classes.size(), signedCounts.unchanged(), result::report);
    }

    @Test
    void theClassesOfAJarThatIsNotNamedAreNeverStrippedAndNotCounted() throws Exception {
        Map<String, byte[]> classes = fixture(25, "project-module", List.of("-g"));
        Path module = ClassFixtures.jar(temp.resolve("project-module/lib.jar"), classes);
        Path library = ClassFixtures.jar(temp.resolve("project-module/library.jar"),
                fixture(25, "project-module-library", List.of("-g")));

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(applicationClasses, module, library))
                .outputDirectory(temp.resolve("project-module/out"))
                .stripLocalVariables(List.of(library))
                .build());

        assertEquals(module, result.classPath().get(1), "a project module keeps its own path");
        assertEquals(temp.resolve("project-module/out/2/library.jar"), result.classPath().get(2));
        // Only the library's copy of the class with code carries anything to strip; its annotation interfaces
        // do not, and no class of the module is counted.
        ClassTransformPipeline.StepCount report = counts(result, library);
        assertEquals(1, report.rewritten(), result::report);
        assertEquals(classes.size() - 1, report.unchanged(), result::report);
        assertEquals(0, report.fallbacks(), result::report);
        assertFalse(result.report().contains(module.toString()), result::report);
        assertTrue(result.summary().startsWith("Stripped local-variable tables from 1 of " + classes.size()
                + " dependency classes in 1 jars ("), result::summary);
    }

    @Test
    void theStepDeclinesAnUnknownAttributeAndAClassWithNothingToDrop() throws Exception {
        Map<String, byte[]> classes = fixture(25, "declines", List.of("-g"));
        byte[] compiled = classes.get(FIXTURE_ENTRY);
        ClassPathModel.LayerScan scan = ClassPathModel.scan("fixture", false, name -> false);
        classes.forEach(scan::accept);
        ClassTransformPipeline.JarRun run = new ClassTransformPipeline(List.of(new LocalVariableStripper()),
                ClassPathModel.merge(List.of(scan))).start(new ClassTransformPipeline.Layer("fixture.jar", false, true));

        byte[] stripped = run.process(FIXTURE_ENTRY, compiled);
        assertTrue(stripped.length < compiled.length);
        for (MethodModel method : ClassFile.of().parse(stripped).methods()) {
            method.code().ifPresent(code -> assertFalse(
                    code.findAttribute(Attributes.localVariableTable()).isPresent(), method.methodName()::toString));
        }
        byte[] withUnknown = ClassFixtures.withUnknownAttribute(compiled);
        assertSame(withUnknown, run.process(FIXTURE_ENTRY, withUnknown));
        byte[] withoutDebug = Files.readAllBytes(applicationClasses.resolve("app/Main.class"));
        assertSame(withoutDebug, run.process("app/Main.class", withoutDebug), "nothing to drop");
        assertEquals(List.of(new ClassTransformPipeline.StepCount(LocalVariableStripper.NAME, 1, 2, 0,
                compiled.length - stripped.length)), run.report().counts());
        assertEquals(List.of(), run.report().notes(), "declining is not a fallback");
        assertTrue(LocalVariableStripper.isKnownReader("org/aspectj/weaver/World.class"));
        assertFalse(LocalVariableStripper.isKnownReader("org/aspectj/lang/Aspects.class"));
        assertFalse(new LocalVariableStripper().appliesTo(new ClassTransformPipeline.Layer("fixture.jar", false,
                false)), "only a third-party jar is stripped");
    }

    @Test
    void aClassWhoseOnlyDebugTableIsACharacterRangeTableLosesIt() throws Exception {
        // javac writes a CharacterRangeTable only with -Xjcov; -g:source,lines keeps the local-variable tables
        // out, so that table is the only thing the step finds.
        Map<String, byte[]> original = ClassFixtures.classes(ClassFixtures.compile(temp.resolve("jcov/src"),
                temp.resolve("jcov/classes"), List.of("-g:source,lines", "-Xjcov", "--release", "25"),
                ClassFixtures.source("fixture.Ranged", """
                        package fixture;
                        public class Ranged {
                            public static int twice(int value) {
                                int doubled = value * 2;
                                if (doubled > 100) {
                                    return 100;
                                }
                                return doubled;
                            }
                        }
                        """)));
        String entry = "fixture/Ranged.class";
        assertEquals(List.of("CharacterRangeTable", "LineNumberTable", "StackMapTable"),
                codeAttributes(original.get(entry), "twice"), "the fixture has the table and no other to drop");
        Path dependency = ClassFixtures.jar(temp.resolve("jcov/fixture.jar"), original);

        ClassPathTransform.Result result = strip(temp.resolve("jcov/out"), dependency);
        Map<String, byte[]> stripped = classes(result.classPath().get(1));

        assertEquals(List.of("LineNumberTable", "StackMapTable"), codeAttributes(stripped.get(entry), "twice"));
        assertTrue(stripped.get(entry).length < original.get(entry).length, "the class shrank");
        ClassTransformPipeline.StepCount report = counts(result, dependency);
        assertEquals(1, report.rewritten(), result::report);
        assertEquals(0, report.fallbacks(), result::report);
        Method twice = load(stripped, "fixture.Ranged").getMethod("twice", int.class);
        assertEquals(14, twice.invoke(null, 7));
        assertEquals(100, twice.invoke(null, 70));
    }

    @ParameterizedTest(name = "parallelism {0}")
    @ValueSource(ints = {1, 4})
    void aDependencyClassWithACorruptHeaderIsWrittenAsItWasAndDoesNotFailTheBuild(int parallelism)
            throws Exception {
        Path directory = temp.resolve("corrupt-" + parallelism);
        String body = "{ public void run() { int local = 1; } }\n";
        Map<String, byte[]> compiled = ClassFixtures.classes(ClassFixtures.compile(directory.resolve("src"),
                directory.resolve("classes"), List.of("-g", "--release", "25"), Map.of(
                        "bad/BadSuper.java", "package bad; public class BadSuper implements Runnable " + body,
                        "bad/BadIface.java", "package bad; public class BadIface implements Runnable " + body,
                        "bad/Good.java", "package bad; public class Good implements Runnable " + body)));
        // Both classes still parse and still declare their own names: only their superclass, or their
        // interface, cannot be read, which the class path scan and the strip step both come across.
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("bad/BadSuper.class", ClassFixtures.withCorruptSuperclass(compiled.get("bad/BadSuper.class")));
        entries.put("bad/BadIface.class", ClassFixtures.withCorruptInterface(compiled.get("bad/BadIface.class")));
        entries.put("bad/Good.class", compiled.get("bad/Good.class"));
        Path dependency = ClassFixtures.jar(directory.resolve("bad.jar"), entries);
        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(List.of(applicationClasses, dependency))
                .outputDirectory(directory.resolve("out"))
                .stripLocalVariables(List.of(dependency))
                .parallelism(parallelism)
                .build());

        Map<String, byte[]> rewritten = classes(result.classPath().get(1));
        assertArrayEquals(entries.get("bad/BadSuper.class"), rewritten.get("bad/BadSuper.class"));
        assertArrayEquals(entries.get("bad/BadIface.class"), rewritten.get("bad/BadIface.class"));
        assertTrue(rewritten.get("bad/Good.class").length < compiled.get("bad/Good.class").length,
                "the class next to them is stripped");
        ClassTransformPipeline.StepCount report = counts(result, dependency);
        assertEquals(1, report.rewritten(), result::report);
        assertEquals(2, report.fallbacks(), result::report);
        assertEquals(3, report.classes(), result::report);
        assertEquals(2, result.report().lines().filter(line -> line.startsWith("fallback\t")).count(),
                result::report);
    }

    /** The structural comparison of one class, as compiled and as stripped. */
    private static void assertStructure(String name, ClassModel before, ClassModel after) {
        // A rebuilt pool writes BootstrapMethods last, so the class attributes are compared as a set.
        assertEquals(new TreeSet<>(withoutInvisibleTypeAnnotations(before.attributes())),
                new TreeSet<>(names(after.attributes())), name + " class attributes");
        assertEquals(before.fields().size(), after.fields().size(), name);
        for (int i = 0; i < before.fields().size(); i++) {
            FieldModel original = before.fields().get(i);
            FieldModel stripped = after.fields().get(i);
            assertEquals(original.fieldName().stringValue(), stripped.fieldName().stringValue(), name);
            assertEquals(withoutInvisibleTypeAnnotations(original.attributes()), names(stripped.attributes()),
                    name + " " + original.fieldName() + " attributes");
        }
        assertEquals(before.methods().size(), after.methods().size(), name);
        for (int i = 0; i < before.methods().size(); i++) {
            MethodModel original = before.methods().get(i);
            MethodModel stripped = after.methods().get(i);
            String method = name + " " + original.methodName() + original.methodType();
            assertEquals(original.methodName().stringValue() + original.methodType().stringValue(),
                    stripped.methodName().stringValue() + stripped.methodType().stringValue(), method);
            assertEquals(withoutInvisibleTypeAnnotations(original.attributes()), names(stripped.attributes()),
                    method + " attributes");
            if (original.code().isEmpty()) {
                continue;
            }
            CodeModel code = stripped.code().orElseThrow();
            List<String> codeAttributes = names(code.attributes());
            assertFalse(codeAttributes.contains("LocalVariableTable"), method);
            assertFalse(codeAttributes.contains("LocalVariableTypeTable"), method);
            assertFalse(codeAttributes.contains("RuntimeVisibleTypeAnnotations"), method);
            assertFalse(codeAttributes.contains("RuntimeInvisibleTypeAnnotations"), method);
            assertEquals(original.code().get().findAttribute(Attributes.stackMapTable()).isPresent(),
                    code.findAttribute(Attributes.stackMapTable()).isPresent(), method + " frames");
            assertEquals(linesByInstruction(original.code().get()), linesByInstruction(code),
                    method + " maps every instruction to the same line");
            assertEquals(opcodes(original.code().get()), opcodes(code), method + " has the same instructions");
        }
        for (String kept : List.of("Signature", "SourceFile", "RuntimeVisibleAnnotations", "InnerClasses")) {
            assertEquals(names(before.attributes()).contains(kept), names(after.attributes()).contains(kept),
                    name + " keeps " + kept);
        }
    }

    /**
     * The fixture carries an invisible and a visible type annotation on the class or its type parameter, on a
     * field and on a method: the invisible ones are dropped from all three, the visible ones are kept.
     */
    private static void assertTypeAnnotations(ClassModel compiled, ClassModel stripped) {
        assertTrue(names(compiled.attributes()).contains(INVISIBLE_TYPE_ANNOTATIONS), "the class as compiled");
        assertFalse(names(stripped.attributes()).contains(INVISIBLE_TYPE_ANNOTATIONS), "the class");
        List<String> field = names(field(compiled, "items").attributes());
        assertTrue(field.containsAll(List.of(INVISIBLE_TYPE_ANNOTATIONS, VISIBLE_TYPE_ANNOTATIONS)), field::toString);
        field = names(field(stripped, "items").attributes());
        assertTrue(field.contains(VISIBLE_TYPE_ANNOTATIONS), field::toString);
        assertFalse(field.contains(INVISIBLE_TYPE_ANNOTATIONS), field::toString);
        List<String> method = names(method(compiled, "add").attributes());
        assertTrue(method.containsAll(List.of(INVISIBLE_TYPE_ANNOTATIONS, VISIBLE_TYPE_ANNOTATIONS)),
                method::toString);
        method = names(method(stripped, "add").attributes());
        assertTrue(method.contains(VISIBLE_TYPE_ANNOTATIONS), method::toString);
        assertFalse(method.contains(INVISIBLE_TYPE_ANNOTATIONS), method::toString);
    }

    private static FieldModel field(ClassModel model, String name) {
        return model.fields().stream().filter(field -> field.fieldName().equalsString(name)).findFirst()
                .orElseThrow();
    }

    private static MethodModel method(ClassModel model, String name) {
        return model.methods().stream().filter(method -> method.methodName().equalsString(name)).findFirst()
                .orElseThrow();
    }

    private static List<String> codeAttributes(byte[] bytes, String method) {
        return names(method(ClassFile.of().parse(bytes), method).code().orElseThrow().attributes()).stream()
                .sorted().toList();
    }

    private static List<String> withoutInvisibleTypeAnnotations(List<Attribute<?>> attributes) {
        return names(attributes).stream().filter(name -> !name.equals(INVISIBLE_TYPE_ANNOTATIONS)).toList();
    }

    private static List<Integer> linesByInstruction(CodeModel code) {
        List<Integer> lines = new ArrayList<>();
        int line = -1;
        for (CodeElement element : code) {
            if (element instanceof LineNumber number) {
                line = number.line();
            } else if (element instanceof Instruction) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * The opcodes by instruction index, with {@code ldc} and {@code ldc_w} counted as one: a rebuilt pool is
     * free to choose either.
     */
    private static List<String> opcodes(CodeModel code) {
        List<String> opcodes = new ArrayList<>();
        for (CodeElement element : code) {
            if (element instanceof Instruction instruction) {
                String opcode = instruction.opcode().name();
                opcodes.add(opcode.equals("LDC_W") ? "LDC" : opcode);
            }
        }
        return opcodes;
    }

    private static List<String> names(List<Attribute<?>> attributes) {
        return attributes.stream().map(attribute -> attribute.attributeName().stringValue()).toList();
    }

    private static List<Object> behaviour(Class<?> type) throws Exception {
        List<Object> results = new ArrayList<>();
        Method constants = type.getMethod("constants", int.class);
        for (int index = 0; index < 400; index++) {
            results.add(constants.invoke(null, index));
        }
        Object instance = type.getConstructor().newInstance();
        results.add(type.getMethod("add", Comparable.class, int.class).invoke(instance, "abc", 4));
        results.add(type.getMethod("fail", int.class).invoke(null, 3));
        results.add(type.getMethod("size").invoke(instance));
        return results;
    }

    private static List<String> reflection(Class<?> type) {
        List<String> view = new ArrayList<>();
        view.add(type.toGenericString());
        view.add(Arrays.toString(type.getTypeParameters()));
        for (Method method : type.getDeclaredMethods()) {
            view.add(method.toGenericString());
            for (Annotation annotation : method.getAnnotations()) {
                view.add(method.getName() + " " + annotation);
            }
            view.add(method.getName() + " returns " + method.getAnnotatedReturnType());
            view.add(method.getName() + " " + Arrays.toString(method.getTypeParameters()));
            for (Parameter parameter : method.getParameters()) {
                view.add(method.getName() + " " + parameter + " " + parameter.isNamePresent());
            }
        }
        for (Field field : type.getDeclaredFields()) {
            view.add(field.toGenericString() + " " + field.getAnnotatedType());
        }
        view.sort(String::compareTo);
        return view;
    }

    private static StackTraceElement thrownFrom(Class<?> type) throws Exception {
        try {
            type.getMethod("fail", int.class).invoke(null, 9);
        } catch (InvocationTargetException e) {
            assertTrue(e.getCause() instanceof IllegalStateException, e::toString);
            return e.getCause().getStackTrace()[0];
        }
        throw new AssertionError("fail(9) did not throw");
    }

    private static Class<?> load(Map<String, byte[]> classes) throws ClassNotFoundException {
        return load(classes, FIXTURE);
    }

    private static Class<?> load(Map<String, byte[]> classes, String type) throws ClassNotFoundException {
        ClassLoader loader = new ClassLoader(LocalVariableStripperTest.class.getClassLoader().getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/') + ".class");
                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
        return loader.loadClass(type);
    }

    /** Strips the given jars, with the application's classes first on the class path. */
    private static ClassPathTransform.Result strip(Path output, Path... jars) throws IOException {
        List<Path> classPath = new ArrayList<>();
        classPath.add(applicationClasses);
        classPath.addAll(List.of(jars));
        return ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath)
                .outputDirectory(output)
                .stripLocalVariables(List.of(jars))
                .build());
    }

    /** The counts of the strip step on one jar, read back from the report. */
    static ClassTransformPipeline.StepCount counts(ClassPathTransform.Result result, Path jar) {
        for (String line : result.report().lines().toList()) {
            String[] fields = line.split("\t");
            if (fields.length == 6 && fields[0].equals(jar.toString())) {
                return new ClassTransformPipeline.StepCount(fields[1], Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), Long.parseLong(fields[5]));
            }
        }
        throw new AssertionError("no counts for " + jar + " in\n" + result.report());
    }

    /** The classes of a jar, by entry name. */
    static Map<String, byte[]> classes(Path jar) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.getName().endsWith(".class")) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        entries.put(entry.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private static Map<String, byte[]> fixture(int release, String directory, List<String> debug)
            throws IOException {
        List<String> options = new ArrayList<>(debug);
        options.addAll(List.of("--release", Integer.toString(release)));
        Path classes = ClassFixtures.compile(temp.resolve(directory + "/src"), temp.resolve(directory + "/classes"),
                options, ClassFixtures.source(FIXTURE, source()));
        return ClassFixtures.classes(classes);
    }

    private static byte[] moduleInfo() throws IOException {
        Path classes = ClassFixtures.compile(temp.resolve("module/src"), temp.resolve("module/classes"),
                List.of("-g", "--release", "25"), Map.of(
                        "module-info.java", "module fixture.module { exports fixture.module; }\n",
                        "fixture/module/Api.java", "package fixture.module; public interface Api { }\n"));
        return Files.readAllBytes(classes.resolve("module-info.class"));
    }

    /**
     * A class that exercises what a rebuilt constant pool moves: more than 256 constants, so that a rebuilt
     * pool chooses {@code ldc} and {@code ldc_w} afresh, a {@code tableswitch} and a {@code lookupswitch} after
     * them, whose padding then moves, a type-annotated local, lines holding several statements, a generic
     * signature and a runtime annotation. Its type parameter, a field and a method also carry a type annotation
     * of {@code CLASS} retention, which the step drops, next to one of {@code RUNTIME} retention, which it
     * keeps. It compiles at {@code --release 8}.
     */
    private static String source() {
        StringBuilder constants = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) {
                constants.append(", ");
            }
            constants.append("\"constant-").append(i).append('"');
        }
        return """
                package fixture;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                import java.util.ArrayList;
                import java.util.List;

                public class Stripped<@Stripped.Invisible T extends Comparable<T>> {

                    @Target(ElementType.TYPE_USE)
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Checked {
                    }

                    @Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
                    @Retention(RetentionPolicy.CLASS)
                    public @interface Invisible {
                    }

                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Marker {
                        String value();
                    }

                    private final @Invisible List<@Checked T> items = new ArrayList<T>();

                    @Marker("kept")
                    public <@Invisible E extends T> @Invisible @Checked int add(E item, int weight) {
                        @Checked String label = String.valueOf(item); int total = weight; items.add(item);
                        return label.length() + total;
                    }

                    public int size() {
                        return items.size();
                    }

                    public static String constants(int index) {
                        String[] all = {@constants@};
                        String picked = all[index % all.length];
                        switch (index % 4) {
                            case 0: picked = picked + "-zero"; break;
                            case 1: picked = picked + "-one"; break;
                            case 2: picked = picked + "-two"; break;
                            default: picked = picked + "-other";
                        }
                        switch (index * 1000) {
                            case 1000: picked = picked + "!"; break;
                            case 70000: picked = picked + "?"; break;
                            default: break;
                        }
                        return picked;
                    }

                    public static int fail(int value) {
                        int doubled = value * 2;
                        if (doubled > 10) {
                            throw new IllegalStateException("too big: " + doubled);
                        }
                        return doubled;
                    }
                }
                """.replace("@constants@", constants);
    }
}
