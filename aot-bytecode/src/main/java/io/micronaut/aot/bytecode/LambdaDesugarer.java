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
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.attribute.NestMembersAttribute;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.InvokeDynamicEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The desugar step: replaces the lambda and method-reference call sites of a class with calls to classes it
 * generates at build time.
 *
 * <p>A lambda compiles to an {@code invokedynamic} whose bootstrap, {@code LambdaMetafactory.metafactory}, spins a
 * hidden class the first time the site runs. Without a JDK AOT cache, every start pays for that again. This step
 * writes such a class ahead of time, as an ordinary class next to its host, and points the site at it, so that the
 * runtime links nothing. {@link LambdaClasses} says what the generated classes and the rewritten sites look
 * like.</p>
 *
 * <h2>What stays {@code invokedynamic}</h2>
 * <p>A site is rewritten only when the generated class provably resolves what the site resolved; every
 * {@link Reason} names one way it does not. Nothing of a signed jar, of a {@code META-INF/versions/} directory or
 * of {@code module-info} is rewritten, and neither is any bootstrap other than {@code metafactory}: string
 * concatenation, {@code ObjectMethods}, {@code altMetafactory} (serializable and marker-interface lambdas) and the
 * rest keep their call sites. The sites of a signed jar's classes and of the versioned variants a multi-release jar
 * holds for the running JDK are still counted, so the report covers the lambda call sites the runtime may link.</p>
 *
 * <h2>An open class path</h2>
 * <p>A fat JAR or an image does not tell which copy of a duplicated name loads: Shadow or Shade merges duplicates
 * by its own strategy, and a merged manifest decides whether {@code META-INF/versions/} counts. So a name is
 * certain only when exactly one entry of the class path holds it, no entry holds a {@code META-INF/versions/} copy
 * of it, it is not in a {@linkplain ClassPathTransform.Request.Builder#foreignPackages(java.util.Set) foreign
 * package}, and it is not in a package of the JDK that the class path also holds ({@link JdkClasses}). An uncertain
 * name is never a host, a nest host or an owner, nor a type whose kind or supertypes a rule relies on, such as the
 * functional interface or a captured receiver's type.</p>
 *
 * <h2>The nest is the unit</h2>
 * <p>{@link #plan(ClassTransformPipeline.Layer, ClassTransformPipeline.JarClasses)} plans a whole entry before any
 * of it is written, because a nest host may come after its members. The pipeline then rewrites, verifies and
 * accepts each {@link Unit}, a nest host with its rewritten members and their generated classes, or gives up on it
 * as a whole. The step never logs and never fails a build: a class it cannot parse is not planned, and a unit it
 * cannot rewrite falls back.</p>
 *
 * <p>The output is deterministic for a given JDK build: sites are numbered per host in method order and then
 * bytecode order, and nothing depends on the thread that rewrites the entry. Ported from Micronaut Runner's
 * {@code LambdaDesugarer}.</p>
 */
final class LambdaDesugarer implements ClassTransformPipeline.Step {

    /** The name of the step, which reports and notes use. */
    static final String NAME = "desugarLambdas";

    /** The constant pool string every class with a lambda call site holds. */
    private static final byte[] MARKER = "java/lang/invoke/LambdaMetafactory".getBytes(StandardCharsets.UTF_8);

    private static final String METAFACTORY_OWNER = "java/lang/invoke/LambdaMetafactory";

    private static final String METAFACTORY = "metafactory";

    private static final String ALT_METAFACTORY = "altMetafactory";

    private static final String CLASS_SUFFIX = ".class";

    private static final String META_INF = "META-INF/";

    private static final String SERIALIZABLE = "java/io/Serializable";

    private static final String SERIAL_VERSION_UID = "serialVersionUID";

    /** The first class-file version with {@code invokedynamic}. */
    private static final int INDY_MAJOR = 51;

    /** The first class-file version with nestmates. */
    private static final int NESTMATE_MAJOR = 55;

    private static final int CLASS_MAGIC = 0xCAFEBABE;

    /** What {@link #kindOf(String)} answers for a name no class answers to. */
    private static final int UNRESOLVED = 0;

    /** What {@link #kindOf(String)} answers for a class. */
    private static final int CLASS = 1;

    /** What {@link #kindOf(String)} answers for an interface. */
    private static final int INTERFACE = 2;

    /** What {@link #kindOf(String)} answers for a name the class path does not settle. */
    private static final int UNCERTAIN = 3;

    private static final ClassFile PARSER = ClassFile.of();

    private final ClassPathModel model;

    /** The foreign packages, as internal names, such as {@code io/micronaut/runner}. */
    private final Set<String> foreignPackages;

    /**
     * A desugarer over a class path.
     *
     * @param model           the class path model, built with member tables
     * @param foreignPackages the packages, with their subpackages, that the runtime may resolve from outside the class
     *                        path, as internal names such as {@code io/micronaut/runner}
     * @throws IllegalArgumentException if the model has no member tables
     */
    LambdaDesugarer(ClassPathModel model, Set<String> foreignPackages) {
        if (!model.hasMembers()) {
            throw new IllegalArgumentException("Desugaring lambdas needs a class path model with member tables");
        }
        this.model = model;
        this.foreignPackages = Set.copyOf(foreignPackages);
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Always: every entry of the class path is desugared, the application's own output included.
     */
    @Override
    public boolean appliesTo(ClassTransformPipeline.Layer layer) {
        return true;
    }

    /**
     * The pre-filter: a class outside {@code META-INF/}, other than {@code module-info}, of a class-file version that
     * has {@code invokedynamic}, whose bytes name {@code LambdaMetafactory}.
     */
    @Override
    public boolean matches(String entryName, byte[] bytes) {
        return isCandidate(entryName) && matchesBytes(bytes);
    }

    /**
     * The pre-filter's test of the bytes alone: a class file of a version that has {@code invokedynamic} that names
     * {@code LambdaMetafactory}. The class path scan applies it to every class it reads.
     *
     * @param bytes the class bytes
     * @return whether the class may hold a lambda call site
     */
    static boolean matchesBytes(byte[] bytes) {
        return bytes.length > 8 && u4(bytes, 0) == CLASS_MAGIC && u2(bytes, 6) >= INDY_MAJOR
                && LocalVariableStripper.contains(bytes, MARKER);
    }

    /**
     * Never: a class is desugared only as part of a planned {@link Unit}, never on its own.
     */
    @Override
    public boolean changes(ClassModel model) {
        return false;
    }

    @Override
    public ClassTransform transform(ClassModel model) {
        throw new IllegalStateException("A class is desugared through its nest's plan");
    }

    @Override
    public String summary(ClassTransformPipeline.StepCount total, List<ClassTransformPipeline.JarReport> reports) {
        int sites = 0;
        int generated = 0;
        int bridges = 0;
        int left = 0;
        int nestFallbacks = 0;
        int entries = 0;
        for (ClassTransformPipeline.JarReport report : reports) {
            ClassTransformPipeline.Desugared desugared = report.desugared();
            sites += desugared.sites();
            generated += desugared.generated();
            bridges += desugared.bridges();
            left += desugared.leftTotal();
            nestFallbacks += desugared.nestFallbacks();
            if (desugared.sites() > 0) {
                entries++;
            }
        }
        return "Desugared " + sites + " lambda call sites into " + generated + " generated classes in " + entries
                + " class path entries (" + total.rewritten() + " classes rewritten, " + bridges + " bridges, " + left
                + " sites left as invokedynamic, " + nestFallbacks + " nest fallbacks)";
    }

    /**
     * Whether an entry may hold a host: a class outside {@code META-INF/}, which is where versioned variants live,
     * other than {@code module-info}.
     *
     * @param entryName the entry name
     * @return whether the entry is considered
     */
    static boolean isCandidate(String entryName) {
        return entryName.endsWith(CLASS_SUFFIX) && !entryName.startsWith(META_INF)
                && !LocalVariableStripper.isModuleInfo(entryName);
    }

    /**
     * Plans one class path entry: reads the classes that pass the pre-filter, and the nest hosts they need, decides
     * which sites are rewritten and allocates the generated and bridge names.
     *
     * <p>The class path scan has already applied the pre-filter to every class, so only the classes it marked are
     * read again. A class that is never rewritten, a {@code META-INF/versions/N/} variant that the running JDK would
     * load from a multi-release jar, any class of a signed jar, or any class of an entry that is kept whole, has its
     * sites counted and nothing else: {@link Reason#MULTI_RELEASE}, {@link Reason#SIGNED_JAR} or
     * {@link Reason#SHADOWED_OR_UNCERTAIN}.</p>
     *
     * @param layer   the entry
     * @param classes its classes
     * @return the plan, without any unit for a signed jar or an entry kept whole
     * @throws IOException if a class cannot be read
     */
    JarPlan plan(ClassTransformPipeline.Layer layer, ClassTransformPipeline.JarClasses classes) throws IOException {
        Planner planner = new Planner(layer, classes);
        Map<String, Integer> variants = classes.multiRelease() ? loadedVariants(classes) : Map.of();
        for (ClassTransformPipeline.ClassEntry entry : classes.classes()) {
            String name = entry.name();
            int version = ClassPathModel.versionOf(name);
            String path = version == 0 ? name : ClassPathModel.pathOf(name);
            if (!isCandidate(path) || entry.size() < 0 || entry.size() > ClassTransformPipeline.MAX_CLASS_SIZE
                    || !model.lambdas(layer.index(), name)
                    || version != 0 && !Integer.valueOf(version).equals(variants.get(path))) {
                continue;
            }
            byte[] bytes = planner.read(name);
            if (layer.signed()) {
                planner.count(bytes, Reason.SIGNED_JAR);
            } else if (version != 0) {
                planner.count(bytes, Reason.MULTI_RELEASE);
            } else if (classes.kept()) {
                planner.count(bytes, Reason.SHADOWED_OR_UNCERTAIN);
            } else if (matches(name, bytes)) {
                planner.host(name, bytes);
            }
        }
        return planner.finish();
    }

    /**
     * The version of each versioned class that the running JDK loads from a multi-release jar: the highest that is
     * not above its feature version.
     */
    private static Map<String, Integer> loadedVariants(ClassTransformPipeline.JarClasses classes) {
        Map<String, Integer> variants = new HashMap<>();
        for (ClassTransformPipeline.ClassEntry entry : classes.classes()) {
            int version = ClassPathModel.versionOf(entry.name());
            if (version != 0 && version <= ClassPathModel.RUNTIME_FEATURE) {
                variants.merge(ClassPathModel.pathOf(entry.name()), version, Math::max);
            }
        }
        return variants;
    }

    private static int u2(byte[] bytes, int position) {
        return (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
    }

    private static int u4(byte[] bytes, int position) {
        return u2(bytes, position) << 16 | u2(bytes, position + 2);
    }

    static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    static String internalName(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1);
    }

    private static String key(MethodModel method) {
        return method.methodName().stringValue() + method.methodType().stringValue();
    }

    /**
     * Whether the class path settles which class a name is: exactly one entry holds it, as a base copy only, and
     * neither a foreign package nor a package of the JDK that the class path also holds contains it.
     *
     * @param internalName the class
     * @return whether the name is certain
     */
    boolean certain(String internalName) {
        String packageName = packageOf(internalName);
        return model.holders(internalName) <= 1 && !model.versioned(internalName) && !foreign(packageName)
                && !(JdkClasses.owns(packageName) && model.holdsPackage(packageName));
    }

    private boolean foreign(String internalPackage) {
        for (String foreign : foreignPackages) {
            if (internalPackage.equals(foreign) || internalPackage.startsWith(foreign)
                    && internalPackage.charAt(foreign.length()) == '/') {
                return true;
            }
        }
        return false;
    }

    /**
     * What a name resolves to at run time, as far as the build can tell: for a package of the JDK that the class path
     * does not hold, the JDK's class; otherwise the class path's copy, when the name is certain.
     *
     * @return {@link #UNRESOLVED}, {@link #CLASS}, {@link #INTERFACE} or {@link #UNCERTAIN}
     */
    private int kindOf(String internalName) {
        String packageName = packageOf(internalName);
        int flags;
        if (JdkClasses.owns(packageName)) {
            if (model.holdsPackage(packageName)) {
                return UNCERTAIN;
            }
            JdkClasses.JdkClass jdk = JdkClasses.find(internalName);
            if (jdk == null) {
                return UNRESOLVED;
            }
            flags = jdk.flags();
        } else {
            if (!certain(internalName)) {
                return UNCERTAIN;
            }
            Optional<ClassPathModel.Copy> copy = model.winner(internalName);
            if (copy.isEmpty()) {
                return UNRESOLVED;
            }
            flags = copy.get().flags();
        }
        return (flags & ClassFile.ACC_INTERFACE) != 0 ? INTERFACE : CLASS;
    }

    /**
     * Whether a type a generated class casts to resolves: a primitive, {@code void}, or a class, possibly the
     * element type of an array, that {@link #kindOf(String)} finds, certain or not.
     */
    private boolean resolvesType(ClassDesc type) {
        ClassDesc element = type;
        while (element.isArray()) {
            element = element.componentType();
        }
        return element.isPrimitive() || kindOf(internalName(element)) != UNRESOLVED;
    }

    /**
     * Whether a class is, or may be, serializable: it is {@code java.io.Serializable}, a supertype of it is, or a
     * supertype cannot be found or is uncertain, in which case it may well be.
     */
    private boolean serializable(String internalName, Set<String> seen) {
        if (internalName.equals(SERIALIZABLE)) {
            return true;
        }
        if (!seen.add(internalName)) {
            return false;
        }
        String superName;
        List<String> interfaces;
        String packageName = packageOf(internalName);
        if (JdkClasses.owns(packageName) && !model.holdsPackage(packageName)) {
            JdkClasses.JdkClass jdk = JdkClasses.find(internalName);
            if (jdk == null) {
                return true;
            }
            superName = jdk.superName();
            interfaces = jdk.interfaces();
        } else {
            Optional<ClassPathModel.Copy> copy = model.winner(internalName);
            if (copy.isEmpty() || !certain(internalName)) {
                return true;
            }
            superName = copy.get().superName();
            interfaces = copy.get().interfaces();
        }
        if (superName != null && serializable(superName, seen)) {
            return true;
        }
        for (String implemented : interfaces) {
            if (serializable(implemented, seen)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any {@code invokedynamic} constant of a class is bootstrapped by {@code LambdaMetafactory}: the marker
     * alone may be a string the class merely mentions.
     */
    private static boolean usesMetafactory(ClassModel model) {
        for (PoolEntry entry : model.constantPool()) {
            if (entry instanceof InvokeDynamicEntry indy) {
                MemberRefEntry bootstrap = indy.bootstrap().bootstrapMethod().reference();
                if (bootstrap.owner().name().equalsString(METAFACTORY_OWNER)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The {@code LambdaMetafactory} bootstrap a site uses.
     *
     * @return the bootstrap method's name, or {@code null} for any other bootstrap
     */
    private static String bootstrap(InvokeDynamicInstruction indy) {
        MemberRefEntry bootstrap = indy.invokedynamic().bootstrap().bootstrapMethod().reference();
        if (!bootstrap.owner().name().equalsString(METAFACTORY_OWNER)) {
            return null;
        }
        return bootstrap.name().stringValue();
    }

    private static List<ClassDesc> nestMembers(ClassModel model) {
        Optional<NestMembersAttribute> attribute = model.findAttribute(Attributes.nestMembers());
        if (attribute.isEmpty()) {
            return List.of();
        }
        List<ClassDesc> members = new ArrayList<>(attribute.get().nestMembers().size());
        for (ClassEntry member : attribute.get().nestMembers()) {
            members.add(member.asSymbol());
        }
        return members;
    }

    /**
     * Why a {@code metafactory} site stays {@code invokedynamic}.
     */
    enum Reason {

        /** The bootstrap is {@code altMetafactory}: a serializable, marker-interface or bridged lambda. */
        ALT_METAFACTORY("altMetafactory"),

        /**
         * The host, its nest host, the implementation's owner, the functional interface or a captured receiver's
         * type is not certain: two entries hold the name, an entry holds a {@code META-INF/versions/} copy of it, it
         * is in a foreign package or in a package of the JDK that the class path also holds; or the entry is kept
         * whole, such as a jar that holds an entry name twice.
         */
        SHADOWED_OR_UNCERTAIN("shadowedOrUncertain"),

        /**
         * The host is a {@code META-INF/versions/N/} variant that the running JDK would load from a multi-release
         * jar, which this step never rewrites.
         */
        MULTI_RELEASE("multiRelease"),

        /** The host is in a signed jar, whose manifest holds a digest of every entry. */
        SIGNED_JAR("signedJar"),

        /** The host's nest host is not a class of the same entry that lists the host as a member. */
        NEST("nest"),

        /**
         * The implementation is an {@code invokespecial} that stays non-virtual: on another class, such as
         * {@code super::method}, or of a member of the host that is not private.
         */
        SUPER_CALL("superCall"),

        /**
         * The implementation's owner does not declare the member with that descriptor, or the generated class could
         * not access it.
         */
        OWNER_ACCESS("ownerAccess"),

        /** The implementation is a caller-sensitive method of the JDK. */
        CALLER_SENSITIVE("callerSensitive"),

        /** A class the generated class would name does not resolve on the class path or in the JDK. */
        UNRESOLVED_TYPE("unresolvedType"),

        /** A class or a member already has the name the generated class or the bridge would take. */
        NAME_TAKEN("nameTaken"),

        /** The host is an interface below class-file version 55 whose implementation is private. */
        JAVA8_INTERFACE("java8Interface"),

        /**
         * The host is a serializable class below class-file version 55 without a {@code serialVersionUID}, whose
         * default would change with a bridge.
         */
        SERIAL_VERSION_UID("serialVersionUid"),

        /** The site has a shape {@code LambdaMetafactory} would reject, or one this step does not generate. */
        SHAPE("shape"),

        /** The site's nest was planned and then fell back. */
        NEST_FALLBACK("nestFallback");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        /**
         * The reason as reports spell it.
         *
         * @return the label
         */
        String label() {
            return label;
        }
    }

    /**
     * The plan of one class path entry.
     */
    static final class JarPlan {

        private final List<Unit> units;
        private final int[] left;

        private JarPlan(List<Unit> units, int[] left) {
            this.units = units;
            this.left = left;
        }

        /**
         * The nests to rewrite, in the order their first host appears in the entry.
         *
         * @return the units
         */
        List<Unit> units() {
            return units;
        }

        /**
         * The sites that stay {@code invokedynamic}, counted by {@link Reason#ordinal()}.
         *
         * @return the counts
         */
        int[] left() {
            return left;
        }
    }

    /**
     * One nest to rewrite: the nest host, the members that hold rewritten sites and their generated classes. Below
     * class-file version 55 it is one host and its generated classes.
     */
    static final class Unit {

        private final String nestHostEntry;
        private final Map<String, ClassPlan> classes = new LinkedHashMap<>();

        private Unit(String nestHostEntry) {
            this.nestHostEntry = nestHostEntry;
        }

        /**
         * The entry of the nest host, which names the unit.
         *
         * @return the entry name
         */
        String nestHostEntry() {
            return nestHostEntry;
        }

        /**
         * The existing classes the unit rewrites, keyed by entry name.
         *
         * @return the classes
         */
        Map<String, ClassPlan> classes() {
            return classes;
        }

        /**
         * The number of sites the unit rewrites.
         *
         * @return the site count
         */
        int sites() {
            int sites = 0;
            for (ClassPlan plan : classes.values()) {
                sites += plan.sites.size();
            }
            return sites;
        }

        /**
         * The number of bridge methods the unit adds.
         *
         * @return the bridge count
         */
        int bridges() {
            int bridges = 0;
            for (ClassPlan plan : classes.values()) {
                for (LambdaClasses.Site site : plan.sites) {
                    if (site.bridged()) {
                        bridges++;
                    }
                }
            }
            return bridges;
        }

        /**
         * The internal names of the classes the unit generates.
         *
         * @return the names, in the order of their first site
         */
        List<ClassDesc> generatedClasses() {
            List<ClassDesc> names = new ArrayList<>();
            for (ClassPlan plan : classes.values()) {
                for (LambdaClasses.Site site : plan.sites) {
                    if (site.firstOfClass()) {
                        names.add(site.generatedClass());
                    }
                }
            }
            return names;
        }

        /**
         * Generates the unit's classes.
         *
         * @return the generated classes of each host, keyed by the host's entry name, in the order of their first
         * site
         */
        Map<String, List<ClassTransformPipeline.Generated>> generate() {
            Map<String, List<ClassTransformPipeline.Generated>> generated = new LinkedHashMap<>();
            for (ClassPlan plan : classes.values()) {
                List<ClassTransformPipeline.Generated> own = new ArrayList<>();
                for (LambdaClasses.Site site : plan.sites) {
                    if (site.firstOfClass()) {
                        own.add(new ClassTransformPipeline.Generated(
                                internalName(site.generatedClass()) + CLASS_SUFFIX, site.generate()));
                    }
                }
                if (!own.isEmpty()) {
                    generated.put(plan.entryName, own);
                }
            }
            return generated;
        }
    }

    /**
     * What the step does to one existing class: the sites it rewrites, the bridges it adds and, for a nest host,
     * the members it gains.
     */
    static final class ClassPlan {

        private final String entryName;
        private final byte[] original;
        private final Map<String, LambdaClasses.Site[]> sitesByMethod;
        private final List<LambdaClasses.Site> sites;
        private List<ClassDesc> nestMembers = List.of();

        private ClassPlan(String entryName, byte[] original, Map<String, LambdaClasses.Site[]> sitesByMethod,
                          List<LambdaClasses.Site> sites) {
            this.entryName = entryName;
            this.original = original;
            this.sitesByMethod = sitesByMethod;
            this.sites = sites;
        }

        /**
         * The class as the entry holds it.
         *
         * @return its bytes
         */
        byte[] original() {
            return original;
        }

        /**
         * The transform that rewrites the class. Every edit keeps its length and its stack effect, as the pipeline
         * requires.
         *
         * @return a fresh transform
         */
        ClassTransform transform() {
            return new ClassTransform() {
                @Override
                public void accept(ClassBuilder builder, ClassElement element) {
                    if (element instanceof NestMembersAttribute && !nestMembers.isEmpty()) {
                        // Written again at the end, with the generated classes.
                        return;
                    }
                    if (element instanceof MethodModel method && method.code().isPresent()) {
                        LambdaClasses.Site[] planned = sitesByMethod.get(key(method));
                        if (planned != null) {
                            builder.transformMethod(method, MethodTransform.transformingCode(new Rewriter(planned)));
                            return;
                        }
                    }
                    builder.with(element);
                }

                @Override
                public void atEnd(ClassBuilder builder) {
                    for (LambdaClasses.Site site : sites) {
                        site.addBridge(builder);
                    }
                    if (!nestMembers.isEmpty()) {
                        builder.with(NestMembersAttribute.ofSymbols(nestMembers));
                    }
                }
            };
        }
    }

    /**
     * Rewrites the planned sites of one method. A site is found by its position among the method's
     * {@code invokedynamic} instructions, which no pipeline option changes.
     */
    private static final class Rewriter implements CodeTransform {

        private final LambdaClasses.Site[] planned;
        private int ordinal;

        private Rewriter(LambdaClasses.Site[] planned) {
            this.planned = planned;
        }

        @Override
        public void atStart(CodeBuilder builder) {
            // The ClassFile API may run a code handler again, for instance to widen a jump.
            ordinal = 0;
        }

        @Override
        public void accept(CodeBuilder builder, CodeElement element) {
            if (element instanceof InvokeDynamicInstruction indy) {
                LambdaClasses.Site site = ordinal < planned.length ? planned[ordinal] : null;
                ordinal++;
                if (site != null) {
                    site.replace(builder, indy);
                    return;
                }
            }
            builder.with(element);
        }
    }

    /**
     * A nest host, as the entry holds it.
     */
    private static final class Nest {

        private final String name;
        private final byte[] bytes;
        /** The members its {@code NestMembers} attribute lists, in order. */
        private final List<ClassDesc> members;
        private final Set<String> memberNames;

        private Nest(String name, byte[] bytes, List<ClassDesc> members) {
            this.name = name;
            this.bytes = bytes;
            this.members = members;
            this.memberNames = new HashSet<>();
            for (ClassDesc member : members) {
                memberNames.add(internalName(member));
            }
        }

        private boolean holds(String internalName) {
            return internalName.equals(name) || memberNames.contains(internalName);
        }
    }

    /**
     * One {@code metafactory} site, as the host's code holds it.
     *
     * @param method  the method that holds it, by name and descriptor
     * @param ordinal its position among the {@code invokedynamic} instructions of that method
     * @param indy    the instruction
     */
    private record Found(String method, int ordinal, InvokeDynamicInstruction indy) {
    }

    /**
     * Plans one class path entry. It is confined to the thread that rewrites the entry.
     */
    private final class Planner {

        private final ClassTransformPipeline.Layer layer;
        private final ClassTransformPipeline.JarClasses classes;
        /** The bytes of the classes the plan holds on to: planned hosts and nest hosts. */
        private final Map<String, byte[]> retained = new HashMap<>();
        /** The nest hosts read so far, by internal name; {@code null} for one that cannot be used. */
        private final Map<String, Nest> nests = new HashMap<>();
        private final Map<String, Unit> units = new LinkedHashMap<>();
        private final Map<String, Nest> unitNests = new HashMap<>();
        private final int[] left = new int[Reason.values().length];

        private Planner(ClassTransformPipeline.Layer layer, ClassTransformPipeline.JarClasses classes) {
            this.layer = layer;
            this.classes = classes;
        }

        private byte[] read(String entryName) throws IOException {
            byte[] kept = retained.get(entryName);
            return kept != null ? kept : classes.read(entryName);
        }

        /**
         * Plans one class that passed the pre-filter. A class that cannot be parsed is not planned: the entry loop
         * writes it as it would without this step.
         */
        private void host(String entryName, byte[] bytes) throws IOException {
            HostPlan plan;
            try {
                plan = evaluate(entryName, bytes);
            } catch (RuntimeException e) {
                return;
            }
            if (plan == null) {
                return;
            }
            for (int reason = 0; reason < left.length; reason++) {
                left[reason] += plan.left[reason];
            }
            if (plan.sites.isEmpty()) {
                return;
            }
            retained.put(entryName, bytes);
            Unit unit = units.computeIfAbsent(plan.unitName, name -> new Unit(name + CLASS_SUFFIX));
            if (plan.nest != null) {
                unitNests.put(plan.unitName, plan.nest);
            }
            unit.classes.put(entryName, new ClassPlan(entryName, bytes, plan.sitesByMethod, plan.sites));
        }

        /**
         * Counts the sites of a class that this step never rewrites, as {@link #host} counts those of a host it
         * excludes: {@code metafactory} sites under the reason, {@code altMetafactory} ones under their own. A class
         * that cannot be parsed is not counted, as it would not be planned.
         */
        private void count(byte[] bytes, Reason reason) {
            int metafactory = 0;
            int alternative = 0;
            try {
                ClassModel parsed = PARSER.parse(bytes);
                if (!usesMetafactory(parsed)) {
                    return;
                }
                for (MethodModel method : parsed.methods()) {
                    Optional<CodeModel> code = method.code();
                    if (code.isEmpty()) {
                        continue;
                    }
                    for (CodeElement element : code.get()) {
                        if (element instanceof InvokeDynamicInstruction indy) {
                            String bootstrap = bootstrap(indy);
                            if (METAFACTORY.equals(bootstrap)) {
                                metafactory++;
                            } else if (ALT_METAFACTORY.equals(bootstrap)) {
                                alternative++;
                            }
                        }
                    }
                }
            } catch (RuntimeException e) {
                return;
            }
            left[reason.ordinal()] += metafactory;
            left[Reason.ALT_METAFACTORY.ordinal()] += alternative;
        }

        /**
         * Completes the plan: every nest host of version 55 or later is rewritten, even without a site of its own,
         * to list the generated classes of its nest after the members it already has.
         */
        private JarPlan finish() {
            List<Unit> planned = new ArrayList<>(units.size());
            for (Map.Entry<String, Unit> entry : units.entrySet()) {
                Unit unit = entry.getValue();
                Nest nest = unitNests.get(entry.getKey());
                if (nest != null) {
                    List<ClassDesc> members = new ArrayList<>(nest.members);
                    members.addAll(unit.generatedClasses());
                    ClassPlan host = unit.classes.get(unit.nestHostEntry);
                    if (host == null) {
                        host = new ClassPlan(unit.nestHostEntry, nest.bytes, Map.of(), List.of());
                        unit.classes.put(unit.nestHostEntry, host);
                    }
                    host.nestMembers = List.copyOf(members);
                }
                planned.add(unit);
            }
            return new JarPlan(planned, left);
        }

        /**
         * Decides what happens to each site of one class.
         *
         * @return the class's plan, or {@code null} when it has no {@code metafactory} site
         * @throws RuntimeException if the class is malformed
         */
        private HostPlan evaluate(String entryName, byte[] bytes) throws IOException {
            ClassModel host = PARSER.parse(bytes);
            if (!usesMetafactory(host)) {
                return null;
            }
            String hostName = host.thisClass().asInternalName();
            if (!entryName.equals(hostName + CLASS_SUFFIX)) {
                // No class loader defines it under this name.
                return null;
            }
            HostPlan plan = new HostPlan();
            List<Found> found = new ArrayList<>();
            Map<String, Integer> sitesPerMethod = new HashMap<>();
            for (MethodModel method : host.methods()) {
                Optional<CodeModel> code = method.code();
                if (code.isEmpty()) {
                    continue;
                }
                String methodKey = key(method);
                int ordinal = 0;
                for (CodeElement element : code.get()) {
                    if (element instanceof InvokeDynamicInstruction indy) {
                        String bootstrap = bootstrap(indy);
                        if (METAFACTORY.equals(bootstrap)) {
                            found.add(new Found(methodKey, ordinal, indy));
                        } else if (ALT_METAFACTORY.equals(bootstrap)) {
                            plan.left[Reason.ALT_METAFACTORY.ordinal()]++;
                        }
                        ordinal++;
                    }
                }
                sitesPerMethod.put(methodKey, ordinal);
            }
            if (found.isEmpty()) {
                return plan;
            }
            int major = host.majorVersion();
            Reason excluded = excluded(hostName);
            Nest nest = null;
            if (excluded == null && major >= NESTMATE_MAJOR) {
                String nestHostName = host.findAttribute(Attributes.nestHost())
                        .map(attribute -> attribute.nestHost().asInternalName()).orElse(hostName);
                if (nestHostName.equals(hostName)) {
                    nest = new Nest(hostName, bytes, nestMembers(host));
                } else {
                    excluded = excluded(nestHostName);
                    if (excluded == null) {
                        nest = nest(nestHostName);
                        if (nest == null || !nest.holds(hostName)) {
                            excluded = Reason.NEST;
                        }
                    }
                }
            }
            if (excluded != null) {
                plan.left[excluded.ordinal()] += found.size();
                return plan;
            }
            plan.nest = nest;
            plan.unitName = nest == null ? hostName : nest.name;
            String sourceFile = host.findAttribute(Attributes.sourceFile())
                    .map(attribute -> attribute.sourceFile().stringValue()).orElse(null);
            Host context = new Host(host, hostName, new LambdaClasses.Home(ClassDesc.ofInternalName(hostName), major,
                    host.minorVersion(), nest == null ? null : ClassDesc.ofInternalName(nest.name), sourceFile), nest);
            for (Found site : found) {
                Object outcome = context.site(site.indy);
                if (outcome instanceof Reason reason) {
                    plan.left[reason.ordinal()]++;
                    continue;
                }
                LambdaClasses.Site planned = (LambdaClasses.Site) outcome;
                plan.sites.add(planned);
                plan.sitesByMethod.computeIfAbsent(site.method,
                        method -> new LambdaClasses.Site[sitesPerMethod.get(method)])[site.ordinal] = planned;
            }
            LambdaClasses.share(plan.sites);
            return plan;
        }

        /**
         * Why a host or a nest host cannot be rewritten at all, or {@code null} when it can: its name must be
         * certain, and the only copy of it must be the base entry of this class path entry.
         */
        private Reason excluded(String internalName) {
            if (!certain(internalName)) {
                return Reason.SHADOWED_OR_UNCERTAIN;
            }
            Optional<ClassPathModel.Copy> winner = model.winner(internalName);
            if (winner.isEmpty() || winner.get().layer() != layer.index() || winner.get().version() != 0) {
                return Reason.SHADOWED_OR_UNCERTAIN;
            }
            return null;
        }

        /**
         * Reads a nest host that is not the class being planned.
         *
         * @return the nest, or {@code null} when the entry holds no such class of version 55 or later
         */
        private Nest nest(String nestHostName) throws IOException {
            if (nests.containsKey(nestHostName)) {
                return nests.get(nestHostName);
            }
            Nest nest = null;
            String entryName = nestHostName + CLASS_SUFFIX;
            long size = classes.size(entryName);
            if (isCandidate(entryName) && size >= 0 && size <= ClassTransformPipeline.MAX_CLASS_SIZE) {
                byte[] bytes = read(entryName);
                try {
                    ClassModel parsed = PARSER.parse(bytes);
                    if (parsed.thisClass().asInternalName().equals(nestHostName)
                            && parsed.majorVersion() >= NESTMATE_MAJOR) {
                        nest = new Nest(nestHostName, bytes, nestMembers(parsed));
                        retained.put(entryName, bytes);
                    }
                } catch (RuntimeException e) {
                    nest = null;
                }
            }
            nests.put(nestHostName, nest);
            return nest;
        }

        /**
         * One class being planned, with what its sites share.
         */
        private final class Host {

            private final ClassModel parsed;
            private final String name;
            private final String packageName;
            private final LambdaClasses.Home home;
            private final Nest nest;
            private final boolean isInterface;
            private final Set<String> memberNames = new HashSet<>();
            private int next;
            /** Why the class cannot take a bridge, computed when the first site needs one. */
            private Reason unbridgeable;
            private boolean bridgeChecked;

            private Host(ClassModel parsed, String name, LambdaClasses.Home home, Nest nest) {
                this.parsed = parsed;
                this.name = name;
                this.packageName = packageOf(name);
                this.home = home;
                this.nest = nest;
                this.isInterface = (parsed.flags().flagsMask() & ClassFile.ACC_INTERFACE) != 0;
                for (MethodModel method : parsed.methods()) {
                    memberNames.add(method.methodName().stringValue());
                }
                for (FieldModel field : parsed.fields()) {
                    memberNames.add(field.fieldName().stringValue());
                }
            }

            /**
             * Decides one site.
             *
             * @return its {@link LambdaClasses.Site}, or the {@link Reason} it stays {@code invokedynamic}
             */
            private Object site(InvokeDynamicInstruction indy) {
                List<ConstantDesc> arguments = indy.bootstrapArgs();
                if (arguments.size() != 3 || !(arguments.get(0) instanceof MethodTypeDesc samType)
                        || !(arguments.get(1) instanceof DirectMethodHandleDesc implementation)
                        || !(arguments.get(2) instanceof MethodTypeDesc instantiatedType)) {
                    return Reason.SHAPE;
                }
                MethodTypeDesc factoryType = indy.typeSymbol();
                String samName = indy.name().stringValue();
                ClassDesc functionalInterface = factoryType.returnType();
                if (!functionalInterface.isClassOrInterface() || !implementation.owner().isClassOrInterface()) {
                    return Reason.SHAPE;
                }
                String owner = internalName(implementation.owner());
                LambdaClasses.Invocation invocation;
                boolean instance = true;
                boolean special = false;
                switch (implementation.kind()) {
                    case STATIC, INTERFACE_STATIC -> {
                        invocation = LambdaClasses.Invocation.STATIC;
                        instance = false;
                    }
                    case VIRTUAL -> invocation = LambdaClasses.Invocation.VIRTUAL;
                    case INTERFACE_VIRTUAL -> invocation = LambdaClasses.Invocation.INTERFACE;
                    case SPECIAL, INTERFACE_SPECIAL -> {
                        if (!owner.equals(name)) {
                            return Reason.SUPER_CALL;
                        }
                        // As LambdaMetafactory does for a private method of the caller itself, and only for one: the
                        // member must turn out to be private below.
                        special = true;
                        invocation = implementation.isOwnerInterface() ? LambdaClasses.Invocation.INTERFACE
                                : LambdaClasses.Invocation.VIRTUAL;
                    }
                    case CONSTRUCTOR -> {
                        invocation = LambdaClasses.Invocation.CONSTRUCTOR;
                        instance = false;
                    }
                    default -> {
                        return Reason.SHAPE;
                    }
                }
                MethodTypeDesc implType = implementation.invocationType();
                MethodTypeDesc implDescriptor = MethodTypeDesc.ofDescriptor(implementation.lookupDescriptor());
                String implName = invocation == LambdaClasses.Invocation.CONSTRUCTOR ? LambdaClasses.CONSTRUCTOR
                        : implementation.methodName();
                if (!LambdaClasses.shaped(factoryType, samName, samType, instantiatedType, implType, instance)) {
                    return Reason.SHAPE;
                }

                boolean bridged = false;
                int flags;
                boolean ownerIsInterface;
                String ownerPackage = packageOf(owner);
                if (JdkClasses.owns(ownerPackage)) {
                    if (model.holdsPackage(ownerPackage)) {
                        // The class path holds a class of a package of the JDK: which copy loads depends on the
                        // loader and on the runtime image.
                        return Reason.SHADOWED_OR_UNCERTAIN;
                    }
                    JdkClasses.JdkClass jdk = JdkClasses.find(owner);
                    if (jdk == null || (jdk.flags() & ClassFile.ACC_PUBLIC) == 0 || !jdk.exported()) {
                        return Reason.OWNER_ACCESS;
                    }
                    Integer method = jdk.method(implName, implementation.lookupDescriptor());
                    if (method == null || (method & ClassFile.ACC_PUBLIC) == 0) {
                        return Reason.OWNER_ACCESS;
                    }
                    if ((method & JdkClasses.JdkClass.CALLER_SENSITIVE) != 0) {
                        return Reason.CALLER_SENSITIVE;
                    }
                    if ((method & ClassFile.ACC_NATIVE) != 0 && (method & ClassFile.ACC_VARARGS) != 0
                            && owner.startsWith("java/lang/invoke/")) {
                        // A signature-polymorphic method: its descriptor is the call site's, not the method's.
                        return Reason.SHAPE;
                    }
                    flags = method;
                    ownerIsInterface = (jdk.flags() & ClassFile.ACC_INTERFACE) != 0;
                } else {
                    if (!certain(owner)) {
                        return Reason.SHADOWED_OR_UNCERTAIN;
                    }
                    Optional<ClassPathModel.Copy> copy = model.winner(owner);
                    if (copy.isEmpty()) {
                        return Reason.OWNER_ACCESS;
                    }
                    ClassPathModel.Member member = copy.get().member(implName, implementation.lookupDescriptor());
                    if (member == null) {
                        return Reason.OWNER_ACCESS;
                    }
                    flags = member.flags();
                    ownerIsInterface = copy.get().isInterface();
                    if ((flags & ClassFile.ACC_PRIVATE) != 0) {
                        if (owner.equals(name)) {
                            bridged = home.major() < NESTMATE_MAJOR;
                        } else if (nest == null || !inNest(owner, copy.get())) {
                            return Reason.OWNER_ACCESS;
                        }
                    } else if (!model.isAccessible(owner, implName, implementation.lookupDescriptor(), packageName)) {
                        return Reason.OWNER_ACCESS;
                    }
                }
                if (special && (flags & ClassFile.ACC_PRIVATE) == 0) {
                    // LambdaMetafactory keeps an invokespecial of a member that is not private non-virtual, through
                    // the method handle; an invokevirtual would dispatch to an override in a subclass instead.
                    return Reason.SUPER_CALL;
                }
                if (((flags & ClassFile.ACC_STATIC) != 0) != (invocation == LambdaClasses.Invocation.STATIC)
                        || ownerIsInterface != implementation.isOwnerInterface()
                        || ownerIsInterface && invocation == LambdaClasses.Invocation.CONSTRUCTOR) {
                    return Reason.SHAPE;
                }
                if (bridged) {
                    Reason reason = unbridgeable();
                    if (reason != null) {
                        return reason;
                    }
                }
                Reason unresolved = resolve(factoryType, samType, instantiatedType, implType, instance);
                if (unresolved != null) {
                    return unresolved;
                }

                int number = next++;
                String generatedName = name + LambdaClasses.GENERATED_INFIX + number;
                String bridgeName = bridged ? LambdaClasses.BRIDGE_PREFIX + number : null;
                if (model.known(generatedName) || classes.size(generatedName + CLASS_SUFFIX) >= 0
                        || bridged && memberNames.contains(bridgeName)) {
                    return Reason.NAME_TAKEN;
                }
                return new LambdaClasses.Site(home, ClassDesc.ofInternalName(generatedName),
                        new LambdaClasses.Shape(factoryType, samName, samType, instantiatedType),
                        new LambdaClasses.Target(implementation.owner(), implementation.isOwnerInterface(), implName,
                                implDescriptor, implType, invocation, (flags & ClassFile.ACC_STATIC) != 0),
                        bridgeName);
            }

            /**
             * Whether a private member's owner, another class than the host, is in the host's nest: a base class of
             * this entry that names the nest host and that the nest host lists.
             */
            private boolean inNest(String owner, ClassPathModel.Copy copy) {
                if (copy.layer() != layer.index() || copy.version() != 0 || !nest.holds(owner)) {
                    return false;
                }
                return owner.equals(nest.name) || nest.name.equals(copy.nestHost());
            }

            /**
             * Why the host cannot take a bridge, or {@code null} when it can: an interface's bridge would have to be
             * public, and a bridge changes the default {@code serialVersionUID} of a serializable class that declares
             * none.
             */
            private Reason unbridgeable() {
                if (!bridgeChecked) {
                    bridgeChecked = true;
                    if (isInterface) {
                        unbridgeable = Reason.JAVA8_INTERFACE;
                    } else if (!memberNames.contains(SERIAL_VERSION_UID) && serializable()) {
                        unbridgeable = Reason.SERIAL_VERSION_UID;
                    }
                }
                return unbridgeable;
            }

            private boolean serializable() {
                Set<String> seen = new HashSet<>();
                if (parsed.superclass().isPresent()
                        && LambdaDesugarer.this.serializable(parsed.superclass().get().asInternalName(), seen)) {
                    return true;
                }
                for (ClassEntry entry : parsed.interfaces()) {
                    if (LambdaDesugarer.this.serializable(entry.asInternalName(), seen)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Why a class the generated class names does not settle, or {@code null} when every one does: the
             * functional interface must be a certain interface, a captured receiver that is not the owner itself,
             * which the verifier has to relate to the owner, must be certain, and every type it casts to must
             * resolve. The owner was resolved with its member.
             */
            private Reason resolve(MethodTypeDesc factoryType, MethodTypeDesc samType, MethodTypeDesc instantiatedType,
                                   MethodTypeDesc implType, boolean instance) {
                int functional = kindOf(internalName(factoryType.returnType()));
                if (functional == UNCERTAIN) {
                    return Reason.SHADOWED_OR_UNCERTAIN;
                }
                if (functional != INTERFACE) {
                    return Reason.UNRESOLVED_TYPE;
                }
                int captured = factoryType.parameterCount();
                if (instance && captured > 0) {
                    ClassDesc receiver = factoryType.parameterType(0);
                    while (receiver.isArray()) {
                        receiver = receiver.componentType();
                    }
                    if (!receiver.isPrimitive()) {
                        int kind = kindOf(internalName(receiver));
                        if (kind == UNCERTAIN) {
                            return Reason.SHADOWED_OR_UNCERTAIN;
                        }
                        if (kind == UNRESOLVED) {
                            return Reason.UNRESOLVED_TYPE;
                        }
                    }
                }
                for (int i = 0; i < samType.parameterCount(); i++) {
                    ClassDesc argument = samType.parameterType(i);
                    ClassDesc functionalType = instantiatedType.parameterType(i);
                    ClassDesc target = implType.parameterType(captured + i);
                    if (!argument.equals(functionalType) && !resolvesType(functionalType)
                            || !argument.equals(target) && !resolvesType(target)) {
                        return Reason.UNRESOLVED_TYPE;
                    }
                }
                ClassDesc expected = samType.returnType();
                if (implType.returnType().equals(expected) || resolvesType(expected)) {
                    return null;
                }
                return Reason.UNRESOLVED_TYPE;
            }
        }
    }

    /**
     * What one class's sites became while it is being planned.
     */
    private static final class HostPlan {
        private final int[] left = new int[Reason.values().length];
        private final List<LambdaClasses.Site> sites = new ArrayList<>();
        private final Map<String, LambdaClasses.Site[]> sitesByMethod = new HashMap<>();
        private Nest nest;
        private String unitName;
    }
}
