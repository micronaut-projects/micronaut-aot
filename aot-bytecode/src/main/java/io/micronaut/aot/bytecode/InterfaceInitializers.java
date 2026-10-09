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

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What the class path scan records about static initializers, so that desugaring can tell whether initializing a
 * generated class may run code that reaches a lambda call site again.
 *
 * <p>The JVM initializes every superinterface that declares a non-abstract instance method before a class that
 * implements it. A generated class implements the site's functional interface, so creating its first instance runs
 * the static initializers of such interfaces. When one of them reaches the site, on the same thread or another, the
 * generated class is still being initialized: a capture-free site would hand out {@code null}, and two threads could
 * wait for each other. {@code LambdaMetafactory} avoids both by linking the site again.</p>
 *
 * <p>An initializer is <em>quiet</em> when it runs no code that could reach a site outside the interfaces being
 * initialized: besides plain data and the static fields the interface declares, it only creates lambdas through
 * {@code LambdaMetafactory.metafactory} and instances of classes that are inert, that is with no static initializer,
 * {@code Object} as their superclass and constructors that only call {@code Object}'s, such as an anonymous
 * implementation. It calls no method, not even the JDK's: {@code Class.forName} or a {@code ServiceLoader} can run any
 * class's code. The scan records what such an initializer depends on; desugaring checks those classes, which may be
 * in other entries, once the model is complete.</p>
 */
final class InterfaceInitializers {

    private static final String OBJECT = "java/lang/Object";

    private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";

    private static final String METAFACTORY = "metafactory";

    private InterfaceInitializers() {
    }

    /**
     * Summarizes the static initializer of an interface.
     *
     * @param owner the interface, as an internal name
     * @param model the interface
     * @return what its quiet initializer depends on; {@code null} when it has no static initializer or one that is
     * not quiet
     * @throws IllegalArgumentException if the code is malformed
     */
    static Initializer summarize(String owner, ClassModel model) {
        Optional<CodeModel> code = classInitializer(model).flatMap(MethodModel::code);
        if (code.isEmpty()) {
            return null;
        }
        Set<String> fields = new HashSet<>();
        for (FieldModel field : model.fields()) {
            fields.add(field.fieldName().stringValue() + ':' + field.fieldType().stringValue());
        }
        List<String> lambdaInterfaces = new ArrayList<>();
        List<String> instantiated = new ArrayList<>();
        for (CodeElement element : code.get()) {
            if (element instanceof Instruction instruction
                    && !quiet(owner, fields, instruction, lambdaInterfaces, instantiated)) {
                return null;
            }
        }
        return new Initializer(List.copyOf(lambdaInterfaces), List.copyOf(instantiated));
    }

    /**
     * Whether a class is inert: not an interface, no static initializer, {@code Object} as its superclass, and
     * constructors that do nothing but call {@code Object}'s.
     *
     * @param model the class
     * @return whether creating an instance runs no code of the class path besides initializing its interfaces
     * @throws IllegalArgumentException if the code is malformed
     */
    static boolean inert(ClassModel model) {
        if ((model.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0 || classInitializer(model).isPresent()
                || model.superclass().isEmpty()
                || !model.superclass().get().name().equalsString(OBJECT)) {
            return false;
        }
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(ConstantDescs.INIT_NAME) && !callsOnlyObjectConstructor(method)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<MethodModel> classInitializer(ClassModel model) {
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(ConstantDescs.CLASS_INIT_NAME)
                    && method.methodType().equalsString("()V")) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    /**
     * Whether one instruction of an initializer keeps it quiet, recording the classes it depends on. A static field
     * must be one the interface declares itself, since accessing one it inherits initializes the interface that
     * declares it.
     */
    private static boolean quiet(String owner, Set<String> fields, Instruction instruction,
                                 List<String> lambdaInterfaces, List<String> instantiated) {
        return switch (instruction) {
            case InvokeDynamicInstruction indy -> {
                MemberRefEntry bootstrap = indy.invokedynamic().bootstrap().bootstrapMethod().reference();
                if (!bootstrap.owner().name().equalsString(METAFACTORY_OWNER)
                        || !bootstrap.name().equalsString(METAFACTORY)
                        || !indy.typeSymbol().returnType().isClassOrInterface()) {
                    yield false;
                }
                lambdaInterfaces.add(LambdaDesugarer.internalName(indy.typeSymbol().returnType()));
                yield true;
            }
            case InvokeInstruction invoke -> invoke.opcode() == Opcode.INVOKESPECIAL
                    && invoke.name().equalsString(ConstantDescs.INIT_NAME)
                    && (invoke.owner().name().equalsString(OBJECT) ? invoke.type().equalsString("()V")
                    : instantiated.contains(invoke.owner().asInternalName()));
            case FieldInstruction field -> field.opcode() == Opcode.GETFIELD || field.opcode() == Opcode.PUTFIELD
                    || field.owner().name().equalsString(owner)
                    && fields.contains(field.name().stringValue() + ':' + field.type().stringValue());
            case NewObjectInstruction create -> {
                String type = create.className().asInternalName();
                if (!type.equals(OBJECT) && !instantiated.contains(type)) {
                    instantiated.add(type);
                }
                yield true;
            }
            case ConstantInstruction constant -> !(constant.constantValue() instanceof DynamicConstantDesc<?>);
            default -> true;
        };
    }

    /**
     * Whether a constructor's code is {@code aload_0; invokespecial Object.<init>()V; return}.
     */
    private static boolean callsOnlyObjectConstructor(MethodModel constructor) {
        Optional<CodeModel> code = constructor.code();
        if (code.isEmpty()) {
            return false;
        }
        List<Instruction> instructions = new ArrayList<>(3);
        for (CodeElement element : code.get()) {
            if (element instanceof Instruction instruction) {
                if (instructions.size() == 3) {
                    return false;
                }
                instructions.add(instruction);
            }
        }
        return instructions.size() == 3 && instructions.get(0).opcode() == Opcode.ALOAD_0
                && instructions.get(1) instanceof InvokeInstruction call && call.opcode() == Opcode.INVOKESPECIAL
                && call.owner().name().equalsString(OBJECT) && call.name().equalsString(ConstantDescs.INIT_NAME)
                && call.type().equalsString("()V") && instructions.get(2).opcode() == Opcode.RETURN;
    }

    /**
     * What a quiet static initializer depends on.
     *
     * @param lambdaInterfaces the functional interfaces of the lambdas it creates, whose initialization linking them
     *                         runs
     * @param instantiated     the classes it creates instances of, which must be inert
     */
    record Initializer(List<String> lambdaInterfaces, List<String> instantiated) {
    }
}
