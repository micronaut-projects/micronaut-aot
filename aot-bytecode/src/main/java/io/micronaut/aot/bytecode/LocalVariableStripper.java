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

import java.lang.classfile.Attribute;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.FieldTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleTypeAnnotationsAttribute;
import java.lang.classfile.attribute.UnknownAttribute;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The strip step: drops the local-variable tables of a third-party class and rebuilds its constant pool.
 *
 * <p>What it drops: {@code LocalVariableTable}, {@code LocalVariableTypeTable} and {@code CharacterRangeTable},
 * which {@link ClassFile.DebugElementsOption#DROP_DEBUG} removes together; the type annotations of code, visible
 * and invisible, which carry raw bytecode offsets that a rebuilt constant pool can move; and
 * {@code RuntimeInvisibleTypeAnnotations} on the class, its fields and its methods, which nothing reads at run
 * time. The names and signatures of the locals are only gone from the class once its constant pool is rebuilt
 * ({@link ClassFile.ConstantPoolSharingOption#NEW_POOL}); dropping the attributes alone saves almost nothing.</p>
 *
 * <p>What it keeps: {@code LineNumberTable} ({@link ClassFile.LineNumbersOption#PASS_LINE_NUMBERS}),
 * {@code SourceFile}, {@code SourceDebugExtension}, {@code MethodParameters}, {@code Signature} and every other
 * annotation.</p>
 *
 * <p>What it declines, leaving the class byte for byte as it was:</p>
 * <ul>
 *     <li>a class with an attribute the JDK does not know, anywhere, including inside {@code Code}: it might hold
 *     constant pool indexes, which a rebuilt pool would silently break, because the ClassFile API copies an
 *     unknown attribute verbatim and {@code ClassFile.verify} never reads it;</li>
 *     <li>{@code module-info}, including a versioned copy;</li>
 *     <li>every class of a signed jar, whose manifest keeps a digest of each entry;</li>
 *     <li>every class of a class path entry that the caller did not declare a third-party jar, such as the
 *     application's own output and the modules of its build, which are user code;</li>
 *     <li>a class whose rewritten form is not smaller, when stripping is the only change;</li>
 *     <li>a class on which it fails, or whose rewritten form verifies worse than the original (the pipeline's
 *     fallback).</li>
 * </ul>
 *
 * <p>Libraries that read local-variable tables at run time lose what they read. When the class path contains one
 * of the {@linkplain #isKnownReader(String) known readers}, {@link ClassPathTransform} strips nothing and returns a
 * warning; desugaring, which does not depend on those tables, still runs.</p>
 *
 * <p>The class is self-contained: the transform, its options and every decline rule live here. Besides
 * {@code java.lang.classfile} it depends only on the pipeline's {@link ClassTransformPipeline.Step} contract, not
 * on the class path model or the jar writer; the pipeline is its only entry point, and it is the pipeline that
 * attaches a rewritten class's original frames again.</p>
 */
final class LocalVariableStripper implements ClassTransformPipeline.Step {

    /** The name of the step, which reports and notes use. */
    static final String NAME = "stripLocalVariables";

    /**
     * The options a class the step rewrites is parsed and written with. The pipeline adds
     * {@link ClassFile.StackMapsOption#DROP_STACK_MAPS} and its own class hierarchy.
     */
    static final List<ClassFile.Option> OPTIONS = List.of(
            ClassFile.ConstantPoolSharingOption.NEW_POOL,
            ClassFile.DebugElementsOption.DROP_DEBUG,
            ClassFile.LineNumbersOption.PASS_LINE_NUMBERS,
            ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES);

    /**
     * What the pre-filter looks for in the class bytes: every attribute the step drops is named by one of these in
     * the constant pool. {@code LocalVariableT} covers {@code LocalVariableTable} and
     * {@code LocalVariableTypeTable}, and {@code TypeAnnotations} both kinds of type annotations.
     */
    private static final byte[][] MARKERS = {
        "LocalVariableT".getBytes(StandardCharsets.UTF_8),
        "CharacterRangeTable".getBytes(StandardCharsets.UTF_8),
        "TypeAnnotations".getBytes(StandardCharsets.UTF_8)
    };

    /** A library class that reads local-variable tables at run time, by its entry name. */
    private static final List<String> KNOWN_READER_CLASSES = List.of(
            "com/thoughtworks/paranamer/BytecodeReadingParanamer.class",
            "org/springframework/core/LocalVariableTableParameterNameDiscoverer.class");

    /** A package whose classes read local-variable tables at run time: AspectJ's weaver. */
    private static final String KNOWN_READER_PACKAGE = "org/aspectj/weaver/";

    /**
     * Whether a class, by the name of its entry on the class path, reads local-variable tables at run time:
     * Paranamer's {@code BytecodeReadingParanamer}, any class of AspectJ's weaver, or Spring's pre-6.1
     * {@code LocalVariableTableParameterNameDiscoverer}. Reflection is unaffected by stripping, because
     * {@code MethodParameters} is kept; these libraries read the bytes instead.
     *
     * @param classEntry the entry name, relative to its class path entry, such as
     *                   {@code org/aspectj/weaver/World.class}
     * @return whether the class reads local-variable tables
     */
    static boolean isKnownReader(String classEntry) {
        return KNOWN_READER_CLASSES.contains(classEntry)
                || classEntry.startsWith(KNOWN_READER_PACKAGE) && classEntry.endsWith(".class");
    }

    /**
     * Whether an entry is a {@code module-info} class, including a versioned copy.
     *
     * @param entryName the entry name
     * @return whether it is a module descriptor
     */
    static boolean isModuleInfo(String entryName) {
        return entryName.equals("module-info.class") || entryName.endsWith("/module-info.class");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean appliesTo(ClassTransformPipeline.Layer layer) {
        return layer.thirdParty();
    }

    @Override
    public boolean matches(String entryName, byte[] bytes) {
        if (isModuleInfo(entryName)) {
            return false;
        }
        for (byte[] marker : MARKERS) {
            if (contains(bytes, marker)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean changes(ClassModel model) {
        if (model.isModuleInfo() || hasUnknownAttribute(model)) {
            return false;
        }
        if (model.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).isPresent()) {
            return true;
        }
        for (FieldModel field : model.fields()) {
            if (field.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).isPresent()) {
                return true;
            }
        }
        for (MethodModel method : model.methods()) {
            if (method.findAttribute(Attributes.runtimeInvisibleTypeAnnotations()).isPresent()) {
                return true;
            }
            Optional<CodeModel> code = method.code();
            if (code.isPresent() && hasDroppedCodeAttribute(code.get())) {
                return true;
            }
        }
        return false;
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
    public ClassTransform transform(ClassModel model) {
        // LocalVariableTable, LocalVariableTypeTable and CharacterRangeTable never reach the transform: the class
        // was parsed with DROP_DEBUG. What is left to drop is the type annotations.
        CodeTransform code = (builder, element) -> {
            if (!(element instanceof RuntimeVisibleTypeAnnotationsAttribute
                    || element instanceof RuntimeInvisibleTypeAnnotationsAttribute)) {
                builder.with(element);
            }
        };
        return ClassTransform.dropping(element -> element instanceof RuntimeInvisibleTypeAnnotationsAttribute)
                .andThen(ClassTransform.transformingFields(FieldTransform.dropping(
                        element -> element instanceof RuntimeInvisibleTypeAnnotationsAttribute)))
                .andThen(ClassTransform.transformingMethods(MethodTransform.dropping(
                                element -> element instanceof RuntimeInvisibleTypeAnnotationsAttribute)
                        .andThen(MethodTransform.transformingCode(code))));
    }

    @Override
    public String summary(ClassTransformPipeline.StepCount total, List<ClassTransformPipeline.JarReport> reports) {
        return "Stripped local-variable tables from " + total.rewritten() + " of " + total.classes()
                + " dependency classes in " + reports.size() + " jars (" + total.bytesSaved() + " bytes saved, "
                + total.fallbacks() + " fallbacks)";
    }

    private static boolean hasDroppedCodeAttribute(CodeModel code) {
        for (Attribute<?> attribute : code.attributes()) {
            String name = attribute.attributeName().stringValue();
            if (name.equals(Attributes.NAME_LOCAL_VARIABLE_TABLE)
                    || name.equals(Attributes.NAME_LOCAL_VARIABLE_TYPE_TABLE)
                    || name.equals(Attributes.NAME_CHARACTER_RANGE_TABLE)
                    || name.equals(Attributes.NAME_RUNTIME_VISIBLE_TYPE_ANNOTATIONS)
                    || name.equals(Attributes.NAME_RUNTIME_INVISIBLE_TYPE_ANNOTATIONS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any attribute of a class, of its fields, methods and record components, or of any method's code is
     * one the JDK does not know.
     */
    private static boolean hasUnknownAttribute(ClassModel model) {
        if (hasUnknown(model)) {
            return true;
        }
        for (FieldModel field : model.fields()) {
            if (hasUnknown(field)) {
                return true;
            }
        }
        for (MethodModel method : model.methods()) {
            if (hasUnknown(method)) {
                return true;
            }
            Optional<CodeModel> code = method.code();
            if (code.isPresent() && hasUnknown(code.get())) {
                return true;
            }
        }
        return hasUnknownRecordComponentAttribute(model);
    }

    /**
     * Whether any attribute of a record component of a class is one the JDK does not know.
     */
    private static boolean hasUnknownRecordComponentAttribute(ClassModel model) {
        Optional<RecordAttribute> recordAttribute = model.findAttribute(Attributes.record());
        if (recordAttribute.isEmpty()) {
            return false;
        }
        for (RecordComponentInfo component : recordAttribute.get().components()) {
            for (Attribute<?> attribute : component.attributes()) {
                if (attribute instanceof UnknownAttribute) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasUnknown(AttributedElement element) {
        for (Attribute<?> attribute : element.attributes()) {
            if (attribute instanceof UnknownAttribute) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a class's bytes hold a marker, which is how a pre-filter finds a constant pool string without parsing
     * the class.
     *
     * @param bytes  the class bytes
     * @param marker the bytes to look for, at least one
     * @return whether {@code bytes} contains {@code marker}
     */
    static boolean contains(byte[] bytes, byte[] marker) {
        byte first = marker[0];
        int last = bytes.length - marker.length;
        for (int i = 0; i <= last; i++) {
            if (bytes[i] != first) {
                continue;
            }
            int j = 1;
            while (j < marker.length && bytes[i + j] == marker[j]) {
                j++;
            }
            if (j == marker.length) {
                return true;
            }
        }
        return false;
    }
}
