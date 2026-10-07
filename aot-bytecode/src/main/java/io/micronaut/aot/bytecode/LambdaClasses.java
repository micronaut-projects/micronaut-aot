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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.classfile.attribute.StackMapFrameInfo;
import java.lang.classfile.attribute.StackMapTableAttribute;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.SwitchCase;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The classes that {@link LambdaDesugarer} generates, and what a rewritten call site becomes.
 *
 * <h2>What a site becomes</h2>
 * <p>A generated class, {@code <Host>$$Lambda$R<n>}: package-private, final and synthetic, with the host's
 * class-file version and {@code SourceFile}, in the host's package and class path entry. It implements the site's
 * functional interface, keeps the captured values in final fields, and forwards the interface method to the
 * implementation with the argument and return conversions {@code LambdaMetafactory} would apply. Every field and
 * method it has is synthetic, so that a debugger that skips synthetic methods steps straight into the lambda body.
 * A site that captures nothing always yields the same instance, distinct from every other site's.</p>
 *
 * <p>The sites of a host that share the functional interface, the interface method and the captured types share one
 * class, named after the first of them, so that an entry gains far fewer classes than it has sites: each is an entry
 * of the archive and a class defined and initialized at run time. Such a site becomes
 * {@code bipush <tag>; invokestatic <Host>$$Lambda$R<n>.create}; the instance keeps the tag in a final field, and the
 * interface method dispatches on it with a {@code tableswitch}, one case per site, each with the casts of its own
 * site. A class that captures nothing creates one instance per tag when it is initialized. A class stops taking
 * sites when its interface method would exceed {@value #MAX_DISPATCH_BYTES} bytes, HotSpot's default
 * {@code FreqInlineSize}, so that it can still be inlined where it is hot, or {@value #MAX_TAGS} sites, the tags that
 * {@code bipush} pushes. Each case starts with an explicit frame, the method's entry frame, so that writing the class
 * needs no class hierarchy.</p>
 *
 * <p>A site that shares its key with no other becomes {@code invokestatic <Host>$$Lambda$R<n>.create}, with the
 * descriptor of the {@code invokedynamic}, followed by two {@code nop}s, and its class has no tag. Either way the
 * site keeps the length of the {@code invokedynamic} and, after the call, its stack effect, so every bytecode offset
 * of the method, the frames the pipeline attaches again and the offsets of its type annotations stay valid.</p>
 *
 * <p>From class-file version 55 the generated class joins the host's nest, so it may call a private implementation
 * directly, and the nest host's {@code NestMembers} is extended. Below 55 there are no nestmates: a private
 * implementation of the host is reached through a package-private static synthetic bridge,
 * {@code $desugared$lambda$<n>}, added to the host.</p>
 *
 * <p>Ported from Micronaut Runner's {@code LambdaDesugarer}, with the shared classes of micronaut-build's trial of
 * desugaring Micronaut's own modules.</p>
 */
final class LambdaClasses {

    /** What follows the host's name in a generated class's name, before the site number. */
    static final String GENERATED_INFIX = "$$Lambda$R";

    /** What precedes the site number in the name of a bridge method. */
    static final String BRIDGE_PREFIX = "$desugared$lambda$";

    /** The static method of a generated class that a rewritten site calls. */
    static final String FACTORY_METHOD = "create";

    /** The name of a constructor. */
    static final String CONSTRUCTOR = ConstantDescs.INIT_NAME;

    /**
     * The largest interface method of a class that several sites share, in bytecode bytes: HotSpot's default
     * {@code FreqInlineSize}, the largest method C2 inlines at a hot call site.
     */
    static final int MAX_DISPATCH_BYTES = 325;

    /** The most sites a class can serve: {@code bipush} pushes the tag. */
    static final int MAX_TAGS = 128;

    private static final String INSTANCE_FIELD = "INSTANCE";

    private static final String INSTANCES_FIELD = "INSTANCES";

    private static final String TAG_FIELD = "tag";

    /**
     * The bytes of a shared interface method besides its cases: {@code aload_0}, {@code getfield}, and a
     * {@code tableswitch} at offset 4 with 3 bytes of padding and its default, low and high values.
     */
    private static final int DISPATCH_OVERHEAD = 1 + 3 + 1 + 3 + 12;

    /** The bytes a case adds to the {@code tableswitch}. */
    private static final int DISPATCH_CASE = 4;

    private static final ClassFile PARSER = ClassFile.of();

    /** The flags of every member of a generated class besides its access. */
    private static final int SYNTHETIC = ClassFile.ACC_SYNTHETIC;

    /**
     * A class for one site has no branch, so it needs no frames; a shared class gets explicit ones. Neither needs a
     * class hierarchy.
     */
    private static final ClassFile GENERATOR = ClassFile.of(ClassFile.StackMapsOption.DROP_STACK_MAPS);

    private LambdaClasses() {
    }

    /**
     * Whether a site has the shape {@code LambdaMetafactory} accepts and this step generates: the implementation
     * takes the captured values and then the interface method's arguments, the captured values match exactly, and
     * every argument and the result have a conversion.
     *
     * @param factoryType      the descriptor of the {@code invokedynamic}
     * @param samName          the interface method's name
     * @param samType          its erased descriptor
     * @param instantiatedType its descriptor at this site
     * @param implType         the implementation as a call
     * @param instance         whether the implementation takes a receiver
     * @return whether the site can be generated
     */
    static boolean shaped(MethodTypeDesc factoryType, String samName, MethodTypeDesc samType,
                          MethodTypeDesc instantiatedType, MethodTypeDesc implType, boolean instance) {
        int captured = factoryType.parameterCount();
        int arity = samType.parameterCount();
        if (implType.parameterCount() != captured + arity || instantiatedType.parameterCount() != arity) {
            return false;
        }
        if (samName.startsWith("<") || samName.equals(FACTORY_METHOD) && samType.equals(factoryType)) {
            return false;
        }
        // A captured receiver may be a subclass of the owner; every other captured value is exact.
        int first = instance && captured > 0 ? 1 : 0;
        if (first == 1 && factoryType.parameterType(0).isPrimitive()) {
            return false;
        }
        for (int i = first; i < captured; i++) {
            if (!factoryType.parameterType(i).equals(implType.parameterType(i))) {
                return false;
            }
        }
        for (int i = 0; i < arity; i++) {
            if (!Conversions.convertible(samType.parameterType(i), implType.parameterType(captured + i),
                    instantiatedType.parameterType(i))) {
                return false;
            }
        }
        ClassDesc expected = samType.returnType();
        if (isVoid(expected)) {
            return true;
        }
        return Conversions.convertible(implType.returnType(), expected, expected);
    }

    /**
     * Lets the rewritten sites of one host that have the same key, the functional interface, the captured types and
     * the interface method, share classes: in site order, each class takes sites until one more would push its
     * interface method past {@link #MAX_DISPATCH_BYTES}, or past {@link #MAX_TAGS} sites. A site left alone keeps a
     * class of its own.
     *
     * @param sites the host's rewritten sites, in site order
     */
    static void share(List<Site> sites) {
        Map<String, List<Site>> byKey = new LinkedHashMap<>();
        for (Site site : sites) {
            byKey.computeIfAbsent(site.shareKey(), key -> new ArrayList<>()).add(site);
        }
        for (List<Site> sameKey : byKey.values()) {
            if (sameKey.size() < 2) {
                continue;
            }
            List<Site> members = new ArrayList<>();
            int length = DISPATCH_OVERHEAD;
            for (Site site : sameKey) {
                int added = DISPATCH_CASE + site.forwardLength();
                if (!members.isEmpty() && (members.size() == MAX_TAGS || length + added > MAX_DISPATCH_BYTES)) {
                    group(members);
                    members = new ArrayList<>();
                    length = DISPATCH_OVERHEAD;
                }
                members.add(site);
                length += added;
            }
            group(members);
        }
    }

    private static void group(List<Site> members) {
        if (members.size() < 2) {
            return;
        }
        Group group = new Group(List.copyOf(members));
        for (int i = 0; i < members.size(); i++) {
            members.get(i).group = group;
            members.get(i).tag = i;
        }
    }

    private static boolean isVoid(ClassDesc type) {
        return type.descriptorString().equals("V");
    }

    /**
     * Starts a generated class: version, flags, superclass and interface.
     */
    private static void header(ClassBuilder builder, Home home, ClassDesc functionalInterface) {
        builder.withVersion(home.major(), home.minor());
        builder.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_SUPER);
        builder.withSuperclass(ConstantDescs.CD_Object);
        builder.withInterfaceSymbols(functionalInterface);
    }

    /**
     * Ends a generated class: its nest and its host's source file.
     */
    private static void trailer(ClassBuilder builder, Home home) {
        if (home.nestHost() != null) {
            builder.with(NestHostAttribute.of(home.nestHost()));
        }
        if (home.sourceFile() != null) {
            builder.with(SourceFileAttribute.of(home.sourceFile()));
        }
    }

    /**
     * Declares the fields {@code f0…} that hold a site's captured values.
     */
    private static void capturedFields(ClassBuilder builder, MethodTypeDesc factoryType) {
        for (int i = 0; i < factoryType.parameterCount(); i++) {
            builder.withField("f" + i, factoryType.parameterType(i),
                    ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL | SYNTHETIC);
        }
    }

    /**
     * Stores a constructor's leading parameters, the captured values, in the fields {@code f0…}.
     *
     * @return the next free local slot
     */
    private static int storeCaptured(CodeBuilder code, ClassDesc owner, MethodTypeDesc factoryType) {
        int slot = 1;
        for (int i = 0; i < factoryType.parameterCount(); i++) {
            ClassDesc type = factoryType.parameterType(i);
            TypeKind kind = TypeKind.from(type);
            code.aload(0).loadLocal(kind, slot).putfield(owner, "f" + i, type);
            slot += kind.slotSize();
        }
        return slot;
    }

    /**
     * Loads every parameter of a method type, from a first slot on.
     */
    private static void loadParameters(CodeBuilder code, MethodTypeDesc type, int first) {
        int slot = first;
        for (ClassDesc parameter : type.parameterList()) {
            TypeKind kind = TypeKind.from(parameter);
            code.loadLocal(kind, slot);
            slot += kind.slotSize();
        }
    }

    /**
     * The verification type of a local of a given type, as an explicit frame states it.
     */
    private static StackMapFrameInfo.VerificationTypeInfo verificationType(ClassDesc type) {
        return switch (TypeKind.from(type)) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT -> StackMapFrameInfo.SimpleVerificationTypeInfo.INTEGER;
            case LONG -> StackMapFrameInfo.SimpleVerificationTypeInfo.LONG;
            case FLOAT -> StackMapFrameInfo.SimpleVerificationTypeInfo.FLOAT;
            case DOUBLE -> StackMapFrameInfo.SimpleVerificationTypeInfo.DOUBLE;
            default -> StackMapFrameInfo.ObjectVerificationTypeInfo.of(type);
        };
    }

    /** How a generated class calls the implementation. */
    enum Invocation {
        STATIC, VIRTUAL, INTERFACE, CONSTRUCTOR
    }

    /**
     * Where the classes generated for a host go.
     *
     * @param host       the host
     * @param major      the host's class-file version, which its generated classes take
     * @param minor      the host's minor version
     * @param nestHost   the nest the generated classes join, or {@code null} below class-file version 55
     * @param sourceFile the host's {@code SourceFile}, which its generated classes take, or {@code null}
     */
    record Home(ClassDesc host, int major, int minor, ClassDesc nestHost, String sourceFile) {
    }

    /**
     * A call site, as the host's code states it.
     *
     * @param factoryType      the descriptor of the {@code invokedynamic}: the captured types to the functional
     *                         interface
     * @param samName          the name of the interface method
     * @param samType          the erased descriptor of the interface method, which the generated class declares
     * @param instantiatedType the descriptor the interface method has at this site, which decides the casts
     */
    record Shape(MethodTypeDesc factoryType, String samName, MethodTypeDesc samType,
                 MethodTypeDesc instantiatedType) {
    }

    /**
     * The implementation a site forwards to.
     *
     * @param owner          the class that declares it
     * @param ownerInterface whether the site names that class as an interface
     * @param name           its name, {@code <init>} for a constructor
     * @param descriptor     its descriptor, as its owner declares it
     * @param type           the implementation as a call: the receiver first for an instance method, the owner as a
     *                       constructor's result
     * @param invocation     how a generated class calls it
     * @param isStatic       whether it is a static method
     */
    record Target(ClassDesc owner, boolean ownerInterface, String name, MethodTypeDesc descriptor,
                  MethodTypeDesc type, Invocation invocation, boolean isStatic) {
    }

    /**
     * One rewritten site: everything its call, its generated class and its bridge are written from.
     */
    static final class Site {

        private final Home home;
        /** The class of the site's own, which also names a class it shares. */
        private final ClassDesc generated;
        private final MethodTypeDesc factoryType;
        private final String samName;
        private final MethodTypeDesc samType;
        private final MethodTypeDesc instantiatedType;
        private final Target target;
        /** The bridge the host gains and the generated class calls, or {@code null}. */
        private final String bridgeName;
        /** The class this site shares with others of its host, or {@code null} when it has its own. */
        private Group group;
        /** The site's tag in its {@link #group}. */
        private int tag;

        Site(Home home, ClassDesc generated, Shape shape, Target target, String bridgeName) {
            this.home = home;
            this.generated = generated;
            this.factoryType = shape.factoryType();
            this.samName = shape.samName();
            this.samType = shape.samType();
            this.instantiatedType = shape.instantiatedType();
            this.target = target;
            this.bridgeName = bridgeName;
        }

        /**
         * Whether the site reaches its implementation through a bridge on its host.
         *
         * @return whether the host gains a bridge for it
         */
        boolean bridged() {
            return bridgeName != null;
        }

        /**
         * The class the site calls.
         *
         * @return the class
         */
        ClassDesc generatedClass() {
            return group != null ? group.generated : generated;
        }

        /**
         * Whether the site is the first of its class, which names the class and generates it.
         *
         * @return whether {@link #generate()} writes the site's class
         */
        boolean firstOfClass() {
            return group == null || tag == 0;
        }

        /**
         * Replaces the site's {@code invokedynamic}, keeping its length and, after the call, its stack effect.
         *
         * @param code the method's code
         * @param indy the instruction the site was planned for
         */
        void replace(CodeBuilder code, InvokeDynamicInstruction indy) {
            if (!indy.typeSymbol().equals(factoryType) || !indy.name().equalsString(samName)) {
                throw new IllegalStateException("The call site of " + generated.displayName()
                        + " is not where it was planned");
            }
            if (group != null) {
                // 2 + 3 bytes, as the invokedynamic; the call consumes the tag, so the stack after it is the same.
                code.bipush(tag).invokestatic(group.generated, FACTORY_METHOD, group.createType);
                return;
            }
            // 3 + 1 + 1 bytes, as the invokedynamic, with the same stack effect.
            code.invokestatic(generated, FACTORY_METHOD, factoryType).nop().nop();
        }

        /**
         * Adds the site's bridge to its host, if it has one: a static method with the implementation's call type
         * that loads its arguments, calls the private implementation and returns.
         *
         * @param builder the host
         */
        void addBridge(ClassBuilder builder) {
            if (bridgeName == null) {
                return;
            }
            MethodTypeDesc implType = target.type();
            builder.withMethodBody(bridgeName, implType, ClassFile.ACC_STATIC | SYNTHETIC, code -> {
                if (target.invocation() == Invocation.CONSTRUCTOR) {
                    code.new_(target.owner()).dup();
                }
                int slot = 0;
                for (int i = 0; i < implType.parameterCount(); i++) {
                    TypeKind kind = TypeKind.from(implType.parameterType(i));
                    code.loadLocal(kind, slot);
                    slot += kind.slotSize();
                }
                if (target.isStatic()) {
                    code.invokestatic(target.owner(), target.name(), target.descriptor());
                } else {
                    // The pre-nestmate form of a call to a private instance method or constructor.
                    code.invokespecial(target.owner(), target.name(), target.descriptor());
                }
                code.return_(TypeKind.from(implType.returnType()));
            });
        }

        /**
         * Writes the site's generated class: its own, or the one it shares, which its first site writes.
         *
         * @return the class file
         */
        byte[] generate() {
            if (group != null) {
                return group.generate();
            }
            int captured = factoryType.parameterCount();
            MethodTypeDesc constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, factoryType.parameterList());
            return GENERATOR.build(generated, builder -> {
                header(builder, home, factoryType.returnType());
                if (captured == 0) {
                    builder.withField(INSTANCE_FIELD, generated,
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | SYNTHETIC);
                    builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void,
                            ClassFile.ACC_STATIC | SYNTHETIC, code -> code
                                    .new_(generated).dup()
                                    .invokespecial(generated, CONSTRUCTOR, constructorType)
                                    .putstatic(generated, INSTANCE_FIELD, generated)
                                    .return_());
                }
                capturedFields(builder, factoryType);
                builder.withMethodBody(CONSTRUCTOR, constructorType, ClassFile.ACC_PRIVATE | SYNTHETIC,
                        code -> {
                            code.aload(0).invokespecial(ConstantDescs.CD_Object, CONSTRUCTOR, ConstantDescs.MTD_void);
                            storeCaptured(code, generated, factoryType);
                            code.return_();
                        });
                builder.withMethodBody(FACTORY_METHOD, factoryType, ClassFile.ACC_STATIC | SYNTHETIC, code -> {
                    if (captured == 0) {
                        code.getstatic(generated, INSTANCE_FIELD, generated).areturn();
                        return;
                    }
                    code.new_(generated).dup();
                    loadParameters(code, factoryType, 0);
                    code.invokespecial(generated, CONSTRUCTOR, constructorType).areturn();
                });
                builder.withMethodBody(samName, samType, ClassFile.ACC_PUBLIC | SYNTHETIC,
                        code -> forward(code, generated));
                trailer(builder, home);
            });
        }

        /**
         * What sites of one host must have in common to share a class: the functional interface, the captured types
         * and the interface method.
         */
        private String shareKey() {
            return factoryType.descriptorString() + ' ' + samName + samType.descriptorString();
        }

        /**
         * The length of the interface method's body for this site alone, which is the case it adds to a shared
         * class.
         */
        private int forwardLength() {
            byte[] probe = GENERATOR.build(generated, builder -> builder.withMethodBody(samName, samType,
                    ClassFile.ACC_PUBLIC, code -> forward(code, generated)));
            return PARSER.parse(probe).methods().get(0).findAttribute(Attributes.code())
                    .map(CodeAttribute::codeLength).orElseThrow();
        }

        /**
         * The body of the interface method: the captured values, then each argument adapted to the implementation,
         * the call, and the result adapted back.
         *
         * @param code  where to write it
         * @param owner the class that holds the captured values
         */
        void forward(CodeBuilder code, ClassDesc owner) {
            int captured = factoryType.parameterCount();
            MethodTypeDesc implType = target.type();
            if (target.invocation() == Invocation.CONSTRUCTOR && bridgeName == null) {
                code.new_(target.owner()).dup();
            }
            for (int i = 0; i < captured; i++) {
                code.aload(0).getfield(owner, "f" + i, factoryType.parameterType(i));
            }
            int slot = 1;
            for (int i = 0; i < samType.parameterCount(); i++) {
                ClassDesc argument = samType.parameterType(i);
                TypeKind kind = TypeKind.from(argument);
                code.loadLocal(kind, slot);
                slot += kind.slotSize();
                Conversions.convert(code, argument, implType.parameterType(captured + i),
                        instantiatedType.parameterType(i));
            }
            if (bridgeName != null) {
                code.invokestatic(home.host(), bridgeName, implType);
            } else {
                switch (target.invocation()) {
                    case STATIC -> code.invokestatic(target.owner(), target.name(), target.descriptor(),
                            target.ownerInterface());
                    case VIRTUAL -> code.invokevirtual(target.owner(), target.name(), target.descriptor());
                    case INTERFACE -> code.invokeinterface(target.owner(), target.name(), target.descriptor());
                    case CONSTRUCTOR -> code.invokespecial(target.owner(), target.name(), target.descriptor());
                    default -> throw new IllegalStateException("Unknown invocation " + target.invocation());
                }
            }
            ClassDesc result = implType.returnType();
            ClassDesc expected = samType.returnType();
            if (isVoid(expected)) {
                if (!isVoid(result)) {
                    if (TypeKind.from(result).slotSize() == 2) {
                        code.pop2();
                    } else {
                        code.pop();
                    }
                }
                code.return_();
                return;
            }
            Conversions.convert(code, result, expected, expected);
            code.return_(TypeKind.from(expected));
        }
    }

    /**
     * One generated class for several sites of a host that share the functional interface, the interface method and
     * the captured types. A site passes its tag, {@code bipush tag; invokestatic create}, the instance keeps it, and
     * the interface method dispatches on it. A class whose sites capture nothing creates one instance per tag when it
     * is initialized, without a loop, so that each site still always yields the same instance.
     */
    private static final class Group {

        private final ClassDesc generated;
        private final List<Site> members;
        /** The captured types and the tag, to the functional interface. */
        private final MethodTypeDesc createType;

        private Group(List<Site> members) {
            this.generated = members.get(0).generated;
            this.members = members;
            List<ClassDesc> parameters = new ArrayList<>(members.get(0).factoryType.parameterList());
            parameters.add(ConstantDescs.CD_int);
            this.createType = MethodTypeDesc.of(members.get(0).factoryType.returnType(), parameters);
        }

        private byte[] generate() {
            Site first = members.get(0);
            MethodTypeDesc factoryType = first.factoryType;
            int captured = factoryType.parameterCount();
            int count = members.size();
            MethodTypeDesc constructorType = MethodTypeDesc.of(ConstantDescs.CD_void, createType.parameterList());
            return GENERATOR.build(generated, builder -> {
                header(builder, first.home, factoryType.returnType());
                if (captured == 0) {
                    builder.withField(INSTANCES_FIELD, generated.arrayType(),
                            ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL | SYNTHETIC);
                    builder.withMethodBody(ConstantDescs.CLASS_INIT_NAME, ConstantDescs.MTD_void,
                            ClassFile.ACC_STATIC | SYNTHETIC, code -> {
                                code.loadConstant(count).anewarray(generated);
                                for (int i = 0; i < count; i++) {
                                    code.dup().loadConstant(i).new_(generated).dup().loadConstant(i)
                                            .invokespecial(generated, CONSTRUCTOR, constructorType).aastore();
                                }
                                code.putstatic(generated, INSTANCES_FIELD, generated.arrayType()).return_();
                            });
                }
                capturedFields(builder, factoryType);
                builder.withField(TAG_FIELD, ConstantDescs.CD_int,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL | SYNTHETIC);
                builder.withMethodBody(CONSTRUCTOR, constructorType, ClassFile.ACC_PRIVATE | SYNTHETIC, code -> {
                    code.aload(0).invokespecial(ConstantDescs.CD_Object, CONSTRUCTOR, ConstantDescs.MTD_void);
                    int slot = storeCaptured(code, generated, factoryType);
                    code.aload(0).iload(slot).putfield(generated, TAG_FIELD, ConstantDescs.CD_int).return_();
                });
                builder.withMethodBody(FACTORY_METHOD, createType, ClassFile.ACC_STATIC | SYNTHETIC, code -> {
                    if (captured == 0) {
                        code.getstatic(generated, INSTANCES_FIELD, generated.arrayType()).iload(0).aaload().areturn();
                        return;
                    }
                    code.new_(generated).dup();
                    loadParameters(code, createType, 0);
                    code.invokespecial(generated, CONSTRUCTOR, constructorType).areturn();
                });
                builder.withMethodBody(first.samName, first.samType, ClassFile.ACC_PUBLIC | SYNTHETIC,
                        this::dispatch);
                trailer(builder, first.home);
            });
        }

        /**
         * The interface method: a {@code tableswitch} on the tag, whose last case doubles as the default, and one
         * case per site, each starting from the method's entry frame, which is stated explicitly.
         */
        private void dispatch(CodeBuilder code) {
            Site first = members.get(0);
            int count = members.size();
            List<Label> labels = new ArrayList<>(count);
            List<SwitchCase> cases = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Label label = code.newLabel();
                labels.add(label);
                cases.add(SwitchCase.of(i, label));
            }
            code.aload(0).getfield(generated, TAG_FIELD, ConstantDescs.CD_int)
                    .tableswitch(0, count - 1, labels.get(count - 1), cases);
            List<StackMapFrameInfo.VerificationTypeInfo> locals = new ArrayList<>();
            locals.add(StackMapFrameInfo.ObjectVerificationTypeInfo.of(generated));
            for (ClassDesc parameter : first.samType.parameterList()) {
                locals.add(verificationType(parameter));
            }
            List<StackMapFrameInfo> frames = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                code.labelBinding(labels.get(i));
                frames.add(StackMapFrameInfo.of(labels.get(i), locals, List.of()));
                members.get(i).forward(code, generated);
            }
            code.with(StackMapTableAttribute.of(frames));
        }
    }

    /**
     * The argument and return conversions of {@code LambdaMetafactory}, from
     * {@code java.lang.invoke.TypeConvertingMethodAdapter}, over type descriptors instead of loaded classes.
     */
    private static final class Conversions {

        private Conversions() {
        }

        /**
         * Converts the value on top of the stack.
         *
         * @param code       where to emit
         * @param argument   the type the value has
         * @param target     the type it must get
         * @param functional the type the site says it has, which is cast to first when it is more specific
         */
        static void convert(CodeBuilder code, ClassDesc argument, ClassDesc target, ClassDesc functional) {
            if (argument.equals(target) && argument.equals(functional)) {
                return;
            }
            if (isVoid(argument) || isVoid(target)) {
                return;
            }
            if (argument.isPrimitive()) {
                if (target.isPrimitive()) {
                    widen(code, TypeKind.from(argument), TypeKind.from(target));
                    return;
                }
                TypeKind unwrapped = unwrapped(target);
                if (unwrapped != null) {
                    widen(code, TypeKind.from(argument), unwrapped);
                    box(code, unwrapped);
                } else {
                    box(code, TypeKind.from(argument));
                    cast(code, target);
                }
                return;
            }
            ClassDesc source;
            if (argument.equals(functional) || functional.isPrimitive()) {
                source = argument;
            } else {
                source = functional;
                cast(code, functional);
            }
            if (!target.isPrimitive()) {
                if (!source.equals(target)) {
                    cast(code, target);
                }
                return;
            }
            TypeKind kind = TypeKind.from(target);
            TypeKind unwrapped = unwrapped(source);
            if (unwrapped != null) {
                unbox(code, wrapper(unwrapped), unwrapped);
                widen(code, unwrapped, kind);
            } else if (kind == TypeKind.BOOLEAN || kind == TypeKind.CHAR) {
                code.checkcast(wrapper(kind));
                unbox(code, wrapper(kind), kind);
            } else {
                code.checkcast(ConstantDescs.CD_Number);
                unbox(code, ConstantDescs.CD_Number, kind);
            }
        }

        /**
         * Whether {@link #convert} has a legal conversion for a value, as far as descriptors alone can tell. Two
         * reference types are taken to be related, as {@code javac} guarantees.
         */
        static boolean convertible(ClassDesc argument, ClassDesc target, ClassDesc functional) {
            if (isVoid(argument) || isVoid(target) || isVoid(functional)) {
                return false;
            }
            if (argument.isPrimitive()) {
                if (target.isPrimitive()) {
                    return widens(TypeKind.from(argument), TypeKind.from(target));
                }
                TypeKind unwrapped = unwrapped(target);
                return unwrapped == null || widens(TypeKind.from(argument), unwrapped);
            }
            if (!target.isPrimitive()) {
                return true;
            }
            ClassDesc source = argument.equals(functional) || functional.isPrimitive() ? argument : functional;
            TypeKind unwrapped = unwrapped(source);
            return unwrapped == null || widens(unwrapped, TypeKind.from(target));
        }

        private static boolean widens(TypeKind from, TypeKind to) {
            if (from == to) {
                return true;
            }
            return switch (from) {
                case BYTE -> to == TypeKind.SHORT || to == TypeKind.INT || to == TypeKind.LONG
                        || to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case SHORT, CHAR -> to == TypeKind.INT || to == TypeKind.LONG || to == TypeKind.FLOAT
                        || to == TypeKind.DOUBLE;
                case INT -> to == TypeKind.LONG || to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case LONG -> to == TypeKind.FLOAT || to == TypeKind.DOUBLE;
                case FLOAT -> to == TypeKind.DOUBLE;
                default -> false;
            };
        }

        private static void widen(CodeBuilder code, TypeKind from, TypeKind to) {
            TypeKind source = from.asLoadable();
            TypeKind destination = to.asLoadable();
            if (source == destination) {
                return;
            }
            switch (source) {
                case INT -> {
                    switch (destination) {
                        case LONG -> code.i2l();
                        case FLOAT -> code.i2f();
                        case DOUBLE -> code.i2d();
                        default -> throw new IllegalStateException("No widening from int to " + destination);
                    }
                }
                case LONG -> {
                    switch (destination) {
                        case FLOAT -> code.l2f();
                        case DOUBLE -> code.l2d();
                        default -> throw new IllegalStateException("No widening from long to " + destination);
                    }
                }
                case FLOAT -> {
                    if (destination != TypeKind.DOUBLE) {
                        throw new IllegalStateException("No widening from float to " + destination);
                    }
                    code.f2d();
                }
                default -> throw new IllegalStateException("No widening from " + source + " to " + destination);
            }
        }

        private static void cast(CodeBuilder code, ClassDesc target) {
            if (!target.equals(ConstantDescs.CD_Object)) {
                code.checkcast(target);
            }
        }

        private static void box(CodeBuilder code, TypeKind kind) {
            ClassDesc wrapper = wrapper(kind);
            code.invokestatic(wrapper, "valueOf", MethodTypeDesc.of(wrapper, primitive(kind)));
        }

        private static void unbox(CodeBuilder code, ClassDesc owner, TypeKind kind) {
            ClassDesc primitive = primitive(kind);
            code.invokevirtual(owner, primitive.displayName() + "Value", MethodTypeDesc.of(primitive));
        }

        /** The primitive a wrapper class wraps, or {@code null} for any other type. */
        private static TypeKind unwrapped(ClassDesc type) {
            return switch (type.descriptorString()) {
                case "Ljava/lang/Boolean;" -> TypeKind.BOOLEAN;
                case "Ljava/lang/Byte;" -> TypeKind.BYTE;
                case "Ljava/lang/Short;" -> TypeKind.SHORT;
                case "Ljava/lang/Character;" -> TypeKind.CHAR;
                case "Ljava/lang/Integer;" -> TypeKind.INT;
                case "Ljava/lang/Long;" -> TypeKind.LONG;
                case "Ljava/lang/Float;" -> TypeKind.FLOAT;
                case "Ljava/lang/Double;" -> TypeKind.DOUBLE;
                default -> null;
            };
        }

        private static ClassDesc wrapper(TypeKind kind) {
            return switch (kind) {
                case BOOLEAN -> ConstantDescs.CD_Boolean;
                case BYTE -> ConstantDescs.CD_Byte;
                case SHORT -> ConstantDescs.CD_Short;
                case CHAR -> ConstantDescs.CD_Character;
                case INT -> ConstantDescs.CD_Integer;
                case LONG -> ConstantDescs.CD_Long;
                case FLOAT -> ConstantDescs.CD_Float;
                case DOUBLE -> ConstantDescs.CD_Double;
                default -> throw new IllegalStateException("No wrapper for " + kind);
            };
        }

        private static ClassDesc primitive(TypeKind kind) {
            return switch (kind) {
                case BOOLEAN -> ConstantDescs.CD_boolean;
                case BYTE -> ConstantDescs.CD_byte;
                case SHORT -> ConstantDescs.CD_short;
                case CHAR -> ConstantDescs.CD_char;
                case INT -> ConstantDescs.CD_int;
                case LONG -> ConstantDescs.CD_long;
                case FLOAT -> ConstantDescs.CD_float;
                case DOUBLE -> ConstantDescs.CD_double;
                default -> throw new IllegalStateException("No primitive for " + kind);
            };
        }
    }
}
