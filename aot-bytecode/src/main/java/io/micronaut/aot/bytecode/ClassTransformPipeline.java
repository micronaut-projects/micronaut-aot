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
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rewrites the classes of a class path entry: it parses each class once, runs the enabled steps in a fixed order,
 * verifies the result and hands it back once.
 *
 * <h2>Steps</h2>
 * <p>A {@link Step} supplies a pre-filter that sees the entry name and the bytes as read from the entry, a check of
 * whether it changes a parsed class, and the {@link ClassTransform} that makes the change. Enabled steps run in a
 * fixed order: {@linkplain LambdaDesugarer desugaring lambdas} first, then
 * {@linkplain LocalVariableStripper stripping}; their transforms are composed with
 * {@link ClassTransform#andThen(ClassTransform)}, so a class is parsed and written once whatever runs.</p>
 *
 * <h2>Planned nests</h2>
 * <p>Desugaring changes several classes together: a host, its nest host and the classes it generates. It therefore
 * {@linkplain JarRun#plan(JarClasses) plans} a whole entry before any of it is written, because a nest host may come
 * after its members, and the pipeline treats each planned nest as a unit. Every enabled step runs over the unit's
 * existing classes, the generated classes skip the later steps, and the gate verifies every class of the unit. The
 * accepted bytes are kept until the entry loop reaches them ({@link JarRun#planned(String)}), and a host's generated
 * classes are written right after it.</p>
 *
 * <h2>Rules the pipeline owns</h2>
 * <ol type="a">
 *     <li><b>Pool.</b> {@code NEW_POOL}, {@code DROP_DEBUG} and {@code PASS_LINE_NUMBERS} apply only to a class
 *     that a {@linkplain Step#rebuildsConstantPool() pool-rebuilding} step, stripping, actually rewrites. Every
 *     other rewritten class keeps {@code SHARED_POOL} for every step: a rebuilt pool would silently break the raw
 *     pool indexes of an attribute the JDK does not know, which stripping declines and another step would
 *     not.</li>
 *     <li><b>Frames.</b> Every rewritten class is written with {@code DROP_STACK_MAPS}, and each method's original
 *     frames are attached again, by label, after the last step ({@link OriginalFrames}). Steps keep their edits
 *     length- and stack-neutral.</li>
 *     <li><b>Size.</b> A step that {@linkplain Step#skipsLargerOutput() skips a larger output} is skipped when its
 *     output is not smaller only when it is the only step that changed the class.</li>
 *     <li><b>Signed jars.</b> No step applies to any entry of a signed jar.</li>
 * </ol>
 *
 * <h2>The gate</h2>
 * <p>Every rewritten class is verified with {@link ClassFile#verify(byte[])} against the class path model. A class
 * whose rewritten form verifies cleanly is accepted at once. Otherwise the original is verified too, and the
 * rewrite is accepted only when its errors are among the original's: a dependency can already fail to verify,
 * typically because it references an optional dependency that is not on the class path, or because another jar's
 * copy of a class it uses wins. Two errors are the same when their messages differ at most in the bytecode offset
 * they name ({@link #grown(List, List)}): a rebuilt constant pool moves the instructions of a method, and an error
 * the class already had moves with them. A generated class has no original, so it must verify cleanly.</p>
 *
 * <p>The fallback rule is the same for every step. When step S throws, or the rewrite verifies worse, the class
 * starts again from its original bytes without S (for a verification failure, without the earliest step that
 * changed the class, which is desugaring whenever it did) and is gated again. Only if that attempt fails too is the
 * class written as it was. One note names the class, the dropped step and the first error; a false positive costs
 * one step on one class. A planned nest falls back as a whole: without desugaring it is no unit any more, and its
 * classes go through the entry loop for the other steps one by one; without another step, the nest is desugared
 * again and gated again.</p>
 *
 * <p>The pipeline never logs. Each entry's {@link JarRun} counts and notes what happened, and hands its
 * {@link JarReport} back to the caller, which reports them in class-path order. The output does not depend on the
 * thread count, and a pipeline is safe to share between threads: it holds immutable ClassFile contexts and the
 * class path model, and all per-entry state lives in the entry's run.</p>
 */
final class ClassTransformPipeline {

    /**
     * The largest class the pipeline reads into memory; a larger one is copied as it is, so a worker's memory
     * stays bounded. The largest class of a typical Micronaut application is under 200 KB.
     */
    static final int MAX_CLASS_SIZE = 8 * 1024 * 1024;

    /** The entry suffix of a class. */
    private static final String CLASS_SUFFIX = ".class";

    /** The longest first error a note quotes. */
    private static final int MAX_NOTE_ERROR = 300;

    /** The bytecode offset in a verifier message, such as the {@code @41} of {@code in Foo::bar() @41}. */
    private static final Pattern BYTECODE_OFFSET = Pattern.compile("@\\d+");

    private final List<Step> steps;
    /** The step that plans whole nests, and its position among the steps; {@code null} and -1 without one. */
    private final LambdaDesugarer desugarer;
    private final int desugarIndex;
    private final ClassFile rebuilt;
    private final ClassFile shared;
    private final Function<byte[], List<String>> verifier;

    /**
     * A pipeline that verifies against a class hierarchy.
     *
     * @param steps     the enabled steps, in the order they run
     * @param hierarchy the class hierarchy of the runtime class path, normally the {@link ClassPathModel}
     */
    ClassTransformPipeline(List<Step> steps, ClassHierarchyResolver hierarchy) {
        this(steps, hierarchy, verifierOf(hierarchy));
    }

    /**
     * A pipeline with its own verifier, which tests use to see what is verified.
     *
     * @param steps     the enabled steps, in the order they run
     * @param hierarchy the class hierarchy of the runtime class path
     * @param verifier  returns the verification errors of a class, as messages
     */
    ClassTransformPipeline(List<Step> steps, ClassHierarchyResolver hierarchy,
                           Function<byte[], List<String>> verifier) {
        this.steps = List.copyOf(steps);
        if (this.steps.isEmpty()) {
            throw new IllegalArgumentException("A class transform pipeline needs at least one step");
        }
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        LambdaDesugarer planning = null;
        int planningIndex = -1;
        for (int i = 0; i < this.steps.size(); i++) {
            if (this.steps.get(i) instanceof LambdaDesugarer found) {
                planning = found;
                planningIndex = i;
            }
        }
        this.desugarer = planning;
        this.desugarIndex = planningIndex;
        ClassFile.ClassHierarchyResolverOption resolver =
                ClassFile.ClassHierarchyResolverOption.of(Objects.requireNonNull(hierarchy, "hierarchy"));
        List<ClassFile.Option> rebuiltOptions = new ArrayList<>(LocalVariableStripper.OPTIONS);
        rebuiltOptions.add(ClassFile.StackMapsOption.DROP_STACK_MAPS);
        rebuiltOptions.add(resolver);
        this.rebuilt = ClassFile.of(rebuiltOptions.toArray(ClassFile.Option[]::new));
        this.shared = ClassFile.of(ClassFile.ConstantPoolSharingOption.SHARED_POOL,
                ClassFile.DebugElementsOption.PASS_DEBUG,
                ClassFile.LineNumbersOption.PASS_LINE_NUMBERS,
                ClassFile.AttributesProcessingOption.PASS_ALL_ATTRIBUTES,
                ClassFile.StackMapsOption.DROP_STACK_MAPS,
                resolver);
    }

    /**
     * The verifier the gate uses: {@link ClassFile#verify(byte[])} with the given class hierarchy, reduced to the
     * errors' messages so that the errors of two classes can be compared.
     *
     * @param hierarchy the class hierarchy
     * @return the verifier
     */
    static Function<byte[], List<String>> verifierOf(ClassHierarchyResolver hierarchy) {
        ClassFile context = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(hierarchy));
        return bytes -> {
            List<VerifyError> errors = context.verify(bytes);
            List<String> messages = new ArrayList<>(errors.size());
            for (VerifyError error : errors) {
                messages.add(String.valueOf(error.getMessage()));
            }
            return messages;
        };
    }

    /**
     * The first verification error of a rewritten class that its original does not have.
     *
     * <p>The verifier names the failing instruction by its bytecode offset, {@code @41}, and a rebuilt constant pool
     * moves offsets: it chooses {@code ldc} or {@code ldc_w} afresh, and switch padding follows. An error the
     * original already had therefore comes back at another offset, and is not growth, so two messages are the same
     * error when they are equal without their offsets. Each error of the original accounts for one error of the
     * rewrite, so a second error with the same text is growth.</p>
     *
     * @param rewritten the errors of the rewritten class, as {@link #verifierOf(ClassHierarchyResolver)} gives them
     * @param original  the errors of the class it replaces
     * @return the first error of {@code rewritten} that {@code original} does not account for, as the verifier
     * worded it, or {@code null} when the rewrite verifies no worse
     */
    static String grown(List<String> rewritten, List<String> original) {
        Map<String, Integer> known = new HashMap<>();
        for (String error : original) {
            known.merge(withoutOffset(error), 1, Integer::sum);
        }
        for (String error : rewritten) {
            String key = withoutOffset(error);
            Integer left = known.get(key);
            if (left == null || left == 0) {
                return error;
            }
            known.put(key, left - 1);
        }
        return null;
    }

    /**
     * Whether an entry name is a class the pipeline considers: named {@code *.class}, and not a directory.
     *
     * @param entryName the entry name
     * @return whether it is a class
     */
    static boolean isClass(String entryName) {
        return entryName.endsWith(CLASS_SUFFIX) && !entryName.endsWith("/");
    }

    /**
     * Starts the run over one class path entry. The run is confined to the thread that rewrites the entry.
     *
     * @param layer the entry
     * @return its run
     */
    JarRun start(Layer layer) {
        return new JarRun(layer);
    }

    /**
     * Adds up the reports of every entry, one total per step, in step order.
     *
     * @param reports one report per entry, in class-path order
     * @return the totals, each named after its step
     */
    List<StepCount> totals(List<JarReport> reports) {
        List<StepCount> totals = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            totals.add(total(i, reports));
        }
        return totals;
    }

    /**
     * The line the caller logs for each step once every entry is rewritten, in step order. Each step is told about
     * the entries it ran over only.
     *
     * @param reports one report per entry, in class-path order
     * @return one line per step
     */
    List<String> summaries(List<JarReport> reports) {
        List<String> lines = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            List<JarReport> ran = new ArrayList<>(reports.size());
            for (JarReport report : reports) {
                if (report.ran().get(i)) {
                    ran.add(report);
                }
            }
            lines.add(steps.get(i).summary(total(i, ran), ran));
        }
        return lines;
    }

    private StepCount total(int step, List<JarReport> reports) {
        int rewritten = 0;
        int unchanged = 0;
        int fallbacks = 0;
        long saved = 0;
        for (JarReport report : reports) {
            StepCount count = report.counts().get(step);
            rewritten += count.rewritten();
            unchanged += count.unchanged();
            fallbacks += count.fallbacks();
            saved += count.bytesSaved();
        }
        return new StepCount(steps.get(step).name(), rewritten, unchanged, fallbacks, saved);
    }

    private static String withoutOffset(String error) {
        return BYTECODE_OFFSET.matcher(error).replaceAll("@");
    }

    /**
     * A failure in a note or a message: its simple class name, then its message when it has one.
     *
     * @param failure the failure
     * @return for example {@code ZipException: invalid END header}, or {@code EOFException}
     */
    static String describe(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    private static String oneLine(String text) {
        String line = text.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ').strip();
        return line.length() > MAX_NOTE_ERROR ? line.substring(0, MAX_NOTE_ERROR) + "..." : line;
    }

    private static boolean rebuildsPool(List<Step> steps) {
        for (Step step : steps) {
            if (step.rebuildsConstantPool()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One step of the pipeline.
     */
    interface Step {

        /**
         * The step's name, which reports and notes use.
         *
         * @return the name
         */
        String name();

        /**
         * Whether the step runs over the classes of a class path entry at all. Its classes are then counted for the
         * step, and rewritten unless the entry is a signed jar, which no step rewrites.
         *
         * @param layer the entry
         * @return whether it applies
         */
        boolean appliesTo(Layer layer);

        /**
         * A cheap pre-filter on the bytes as read from the entry. A class no enabled step's filter matches is
         * written byte for byte, without being parsed.
         *
         * @param entryName the entry name
         * @param bytes     the class bytes
         * @return whether the step might change the class
         */
        boolean matches(String entryName, byte[] bytes);

        /**
         * Whether the step changes a class. It declines here: returning {@code false} leaves the class to the other
         * steps.
         *
         * @param model the class, parsed with the options the pipeline chose
         * @return whether the step changes it
         */
        boolean changes(ClassModel model);

        /**
         * The transform that makes the step's change. It must keep every edit length- and stack-neutral, because
         * the class's original frames are attached again afterwards.
         *
         * @param model the class, parsed with the options the pipeline chose
         * @return the transform
         */
        ClassTransform transform(ClassModel model);

        /**
         * Whether a class the step rewrites gets a rebuilt constant pool, without its debug elements but with its
         * line numbers (rule a).
         *
         * @return whether the step rebuilds the pool
         */
        default boolean rebuildsConstantPool() {
            return false;
        }

        /**
         * Whether the class is left alone when the step is the only change and its output is not smaller (rule c).
         *
         * @return whether a larger output is skipped
         */
        default boolean skipsLargerOutput() {
            return false;
        }

        /**
         * The line the caller logs for the step once every entry is rewritten.
         *
         * @param total   what the step did, over every entry it ran over
         * @param reports the reports of those entries, in class-path order
         * @return the line
         */
        String summary(StepCount total, List<JarReport> reports);
    }

    /**
     * The class path entry a run works on.
     *
     * @param name       what notes and reports call it: the class path entry as the caller gave it
     * @param index      its position in the class path, which the class path model numbers its entries by
     * @param signed     whether it is a jar that carries signature files
     * @param thirdParty whether the caller declared it a third-party jar, which the strip step applies to
     */
    record Layer(String name, int index, boolean signed, boolean thirdParty) {
    }

    /**
     * The classes of one class path entry, as a planning step reads them before any entry is written.
     */
    interface JarClasses {

        /**
         * Every class entry, in entry order; a name the entry carries twice is listed once.
         *
         * @return the entries
         */
        List<ClassEntry> classes();

        /**
         * The uncompressed size of an entry.
         *
         * @param entryName the entry name
         * @return its size, or {@code -1} when the entry has no class of that name
         */
        long size(String entryName);

        /**
         * Whether the entry's {@code META-INF/versions/N/} variants count: a jar whose manifest says
         * {@code Multi-Release: true}.
         *
         * @return whether it is multi-release
         */
        boolean multiRelease();

        /**
         * Whether the entry is written as it is whatever the steps would do, such as a jar that holds an entry name
         * twice, so that none of its classes can be rewritten.
         *
         * @return whether the entry is kept whole
         */
        boolean kept();

        /**
         * Reads a class, checked against its recorded size and CRC-32 where the source records them.
         *
         * @param entryName the entry name
         * @return its bytes
         * @throws IOException if it cannot be read
         */
        byte[] read(String entryName) throws IOException;
    }

    /**
     * One class entry of a class path entry.
     *
     * @param name its entry name
     * @param size its uncompressed size
     */
    record ClassEntry(String name, long size) {
    }

    /**
     * A class a step generated. It holds its bytes as they are, so it has no value equality.
     */
    static final class Generated {

        private final String name;
        private final byte[] bytes;

        /**
         * A generated class.
         *
         * @param name  its entry name
         * @param bytes its content
         */
        Generated(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }

        /**
         * The class's entry name.
         *
         * @return the entry name
         */
        String name() {
            return name;
        }

        /**
         * The class's content.
         *
         * @return the bytes
         */
        byte[] bytes() {
            return bytes;
        }
    }

    /**
     * What is written for a class of a planned nest. It holds the class's bytes as they are, so it has no value
     * equality.
     */
    static final class Planned {

        private final byte[] bytes;
        private final List<Generated> generated;
        private final boolean rewritten;

        private Planned(byte[] bytes, List<Generated> generated, boolean rewritten) {
            this.bytes = bytes;
            this.generated = generated;
            this.rewritten = rewritten;
        }

        /**
         * The class's accepted bytes.
         *
         * @return the bytes, the original array when nothing changed the class
         */
        byte[] bytes() {
            return bytes;
        }

        /**
         * The classes written right after it.
         *
         * @return the generated classes, in order
         */
        List<Generated> generated() {
            return generated;
        }

        /**
         * Whether a step changed the class.
         *
         * @return whether {@link #bytes()} are not the original's
         */
        boolean rewritten() {
            return rewritten;
        }
    }

    /**
     * What one step did to one class path entry.
     *
     * @param step       the step's name
     * @param rewritten  the classes it rewrote
     * @param unchanged  the classes it left alone
     * @param fallbacks  the classes it gave up on
     * @param bytesSaved how much smaller the classes it rewrote became; for desugaring, less the size of the
     *                   classes it generated
     */
    record StepCount(String step, int rewritten, int unchanged, int fallbacks, long bytesSaved) {

        /**
         * The classes the step considered.
         *
         * @return the classes it rewrote, left alone or gave up on
         */
        int classes() {
            return rewritten + unchanged + fallbacks;
        }
    }

    /**
     * What desugaring did to the lambda call sites of one class path entry.
     *
     * @param sites         the sites rewritten
     * @param generated     the classes generated
     * @param bridges       the bridge methods added
     * @param nestFallbacks the planned nests that fell back to their original classes
     * @param left          the sites left as {@code invokedynamic}, by reason, without the reasons that have none
     */
    record Desugared(int sites, int generated, int bridges, int nestFallbacks,
                     Map<LambdaDesugarer.Reason, Integer> left) {

        /**
         * Makes the map immutable, in the order of the reasons.
         *
         * @param sites         the sites rewritten
         * @param generated     the classes generated
         * @param bridges       the bridges added
         * @param nestFallbacks the nests that fell back
         * @param left          the sites left, by reason
         */
        Desugared {
            Map<LambdaDesugarer.Reason, Integer> ordered = new EnumMap<>(LambdaDesugarer.Reason.class);
            ordered.putAll(left);
            left = Collections.unmodifiableMap(ordered);
        }

        /**
         * The number of sites left as {@code invokedynamic}.
         *
         * @return the sum over every reason
         */
        int leftTotal() {
            int total = 0;
            for (int count : left.values()) {
                total += count;
            }
            return total;
        }
    }

    /**
     * What the pipeline did to one class path entry.
     *
     * @param jar       what the run calls the entry
     * @param counts    one count per enabled step, in step order; zero for a step that did not run over the entry
     * @param ran       for each step, in step order, whether it ran over the entry
     * @param notes     one line per fallback, tab-separated: the entry, the class or {@code the nest of} its nest
     *                  host, the dropped step and the first error
     * @param desugared what desugaring did to the entry's lambda call sites, which in a signed jar is only to count
     *                  them, or {@code null} when the pipeline does not desugar
     */
    record JarReport(String jar, List<StepCount> counts, List<Boolean> ran, List<String> notes,
                     Desugared desugared) {

        /**
         * Makes the lists immutable.
         *
         * @param jar       the entry
         * @param counts    the counts
         * @param ran       which steps ran over it
         * @param notes     the notes
         * @param desugared what desugaring did
         */
        JarReport {
            counts = List.copyOf(counts);
            ran = List.copyOf(ran);
            notes = List.copyOf(notes);
        }
    }

    /**
     * The pipeline's work on one class path entry. It is confined to one thread: it holds the entry's counters, its
     * notes and the accepted classes of its planned nests.
     */
    final class JarRun {

        private final Layer layer;
        private final boolean[] applies;
        /** Whether a step counts this entry's classes, because it runs over the entry. */
        private final boolean[] counted;
        /** Whether a step other than the planning one applies, so classes are worth reading in the entry loop. */
        private final boolean any;
        private final int[] rewritten;
        private final int[] unchanged;
        private final int[] fallbacks;
        private final long[] saved;
        private final List<String> notes = new ArrayList<>();
        /** The accepted classes of the planned nests, by entry name, until the entry loop takes them. */
        private final Map<String, Planned> planned = new HashMap<>();
        /** The classes of the nests that fell back from desugaring, which the entry loop processes as usual. */
        private final Set<String> declined = new HashSet<>();
        private final int[] left = new int[LambdaDesugarer.Reason.values().length];
        private int sites;
        private int generated;
        private int bridges;
        private int nestFallbacks;

        private JarRun(Layer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            int count = steps.size();
            applies = new boolean[count];
            counted = new boolean[count];
            boolean applicable = false;
            for (int i = 0; i < count; i++) {
                counted[i] = steps.get(i).appliesTo(layer);
                // Rule d: nothing in a signed jar is rewritten, whatever the step says.
                applies[i] = !layer.signed() && counted[i];
                applicable |= applies[i] && i != desugarIndex;
            }
            any = applicable;
            rewritten = new int[count];
            unchanged = new int[count];
            fallbacks = new int[count];
            saved = new long[count];
        }

        /**
         * Whether a step other than the planning one applies to this entry, so that its classes are worth reading
         * one by one at all.
         *
         * @return whether such a step applies
         */
        boolean applies() {
            return any;
        }

        /**
         * Whether the entry may change: a step other than the planning one applies, or a planned nest was accepted.
         * An entry that may not change need not be copied.
         *
         * @return whether a class of the entry may be rewritten
         */
        boolean rewrites() {
            return any || !planned.isEmpty();
        }

        /**
         * Whether a class of this size is read into memory and given to {@link #process(String, byte[])}. A class
         * that is not must be passed to {@link #pass(String)} instead. A class of a planned nest is neither:
         * {@link #planned(String)} hands back what to write for it.
         *
         * @param size the class's uncompressed size
         * @return whether the class goes through the pipeline
         */
        boolean reads(long size) {
            return any && size >= 0 && size <= MAX_CLASS_SIZE;
        }

        /**
         * Counts a class that is written without being read: it is too large, no step applies to the entry, or no
         * step but the planning one does and the class is not part of an accepted nest. A class whose nest fell back
         * from desugaring counts as a fallback of that step.
         *
         * @param entryName the class's entry name
         */
        void pass(String entryName) {
            for (int i = 0; i < unchanged.length; i++) {
                if (!counted[i]) {
                    continue;
                }
                if (i == desugarIndex && declined.contains(entryName)) {
                    fallbacks[i]++;
                } else {
                    unchanged[i]++;
                }
            }
        }

        /**
         * Plans the entry before any of its classes is written, when a step plans whole nests: each planned nest is
         * rewritten, verified and either accepted or given up on here, and its classes wait for
         * {@link #planned(String)}. In a signed jar, which no step rewrites, the planning step only counts the
         * lambda call sites it leaves.
         *
         * @param classes the entry's classes
         * @throws IOException if a class cannot be read
         */
        void plan(JarClasses classes) throws IOException {
            if (desugarer == null) {
                return;
            }
            LambdaDesugarer.JarPlan plan = desugarer.plan(layer, classes);
            for (int reason = 0; reason < left.length; reason++) {
                left[reason] += plan.left()[reason];
            }
            if (!applies[desugarIndex]) {
                // Rule d: whatever the plan says, nothing of a signed jar is rewritten.
                return;
            }
            for (LambdaDesugarer.Unit unit : plan.units()) {
                run(unit);
            }
        }

        /**
         * Whether this run plans nests, or counts the lambda call sites of a signed jar, so that
         * {@link #plan(JarClasses)} has work to do.
         *
         * @return whether the pipeline has a planning step
         */
        boolean plans() {
            return desugarer != null;
        }

        /**
         * Takes what is written for a class of an accepted nest. Each class is handed out once.
         *
         * @param entryName the class's entry name
         * @return the class's bytes and the generated classes that follow it, or {@code null} when the class is not
         * part of an accepted nest
         */
        Planned planned(String entryName) {
            return planned.isEmpty() ? null : planned.remove(entryName);
        }

        /**
         * Runs the enabled steps over one class.
         *
         * @param entryName the class's entry name
         * @param original  its bytes, as read from the entry
         * @return the bytes to write: {@code original} itself when no step changed the class
         */
        byte[] process(String entryName, byte[] original) {
            // The planning step is no candidate here: it changes a class only as part of a planned nest.
            List<Step> candidates = candidates(entryName, original, null);
            if (candidates.isEmpty()) {
                pass(entryName);
                return original;
            }
            Attempt first = attempt(original, candidates, null);
            if (first.failed == null) {
                return settle(entryName, original, first, null, null);
            }
            List<Step> remaining = new ArrayList<>(candidates);
            remaining.remove(first.failed);
            Attempt second = remaining.isEmpty() ? Attempt.UNCHANGED : attempt(original, remaining, null);
            String error = first.error;
            if (second.failed != null) {
                error = oneLine(error + "; the class was kept as it was because " + second.failed.name()
                        + " failed too: " + second.error);
            }
            notes.add(String.join("\t", oneLine(layer.name()), oneLine(entryName), first.failed.name(), error));
            return settle(entryName, original, second.failed == null ? second : Attempt.UNCHANGED, first.failed,
                    second.failed);
        }

        /**
         * What the pipeline did to this entry.
         *
         * @return the report
         */
        JarReport report() {
            List<StepCount> counts = new ArrayList<>(steps.size());
            List<Boolean> ran = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                counts.add(new StepCount(steps.get(i).name(), rewritten[i], unchanged[i], fallbacks[i], saved[i]));
                ran.add(counted[i]);
            }
            Desugared desugared = null;
            if (plans()) {
                Map<LambdaDesugarer.Reason, Integer> reasons = new EnumMap<>(LambdaDesugarer.Reason.class);
                for (LambdaDesugarer.Reason reason : LambdaDesugarer.Reason.values()) {
                    if (left[reason.ordinal()] > 0) {
                        reasons.put(reason, left[reason.ordinal()]);
                    }
                }
                desugared = new Desugared(sites, generated, bridges, nestFallbacks, reasons);
            }
            return new JarReport(layer.name(), counts, ran, notes, desugared);
        }

        /**
         * Rewrites one planned nest as a unit, under the fallback rule.
         */
        private void run(LambdaDesugarer.Unit unit) {
            UnitAttempt first = attempt(unit, null);
            if (first.failed == null) {
                accept(unit, first, null, null);
                return;
            }
            String error = first.error;
            UnitAttempt second = null;
            if (first.failed != desugarer) {
                // Without desugaring the nest is no unit any more: the entry loop processes its classes one by one.
                // Without another step, the nest is desugared again, and gated again.
                second = attempt(unit, first.failed);
                if (second.failed != null) {
                    error = oneLine(error + "; the nest was kept as it was because " + second.failed.name()
                            + " failed too: " + second.error);
                }
            }
            notes.add(String.join("\t", oneLine(layer.name()), "the nest of " + oneLine(unit.nestHostEntry()),
                    first.failed.name(), error));
            if (first.failed == desugarer) {
                declined.addAll(unit.classes().keySet());
                abandon(unit);
            } else if (second.failed == null) {
                accept(unit, second, first.failed, null);
            } else {
                accept(unit, null, first.failed, second.failed);
                abandon(unit);
            }
        }

        private void abandon(LambdaDesugarer.Unit unit) {
            nestFallbacks++;
            left[LambdaDesugarer.Reason.NEST_FALLBACK.ordinal()] += unit.sites();
        }

        /**
         * Runs every enabled step but one over the existing classes of a nest, generates its classes and verifies
         * them all.
         *
         * @param without the step to leave out, or {@code null}
         */
        private UnitAttempt attempt(LambdaDesugarer.Unit unit, Step without) {
            Map<String, Attempt> results = new HashMap<>();
            for (Map.Entry<String, LambdaDesugarer.ClassPlan> entry : unit.classes().entrySet()) {
                byte[] original = entry.getValue().original();
                List<Step> candidates = candidates(entry.getKey(), original, without);
                candidates.add(0, desugarer);
                Attempt result = attempt(original, candidates, entry.getValue());
                if (result.failed != null) {
                    return UnitAttempt.failed(result.failed, entry.getKey() + ": " + result.error);
                }
                results.put(entry.getKey(), result);
            }
            Map<String, List<Generated>> generatedClasses;
            try {
                generatedClasses = unit.generate();
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                return UnitAttempt.failed(desugarer, unit.nestHostEntry() + ": " + describe(failure));
            }
            for (List<Generated> classes : generatedClasses.values()) {
                for (Generated generatedClass : classes) {
                    // A generated class has no original: any error in it is growth.
                    List<String> errors = verifier.apply(generatedClass.bytes());
                    if (!errors.isEmpty()) {
                        return UnitAttempt.failed(desugarer, generatedClass.name() + ": verification: "
                                + errors.get(0));
                    }
                }
            }
            return new UnitAttempt(results, generatedClasses, null, null);
        }

        /**
         * Keeps a nest's classes for the entry loop and counts them.
         *
         * @param accepted the attempt that was accepted, or {@code null} to keep every class as it was
         * @param first    the step the nest dropped, or {@code null}
         * @param second   the second step it dropped, or {@code null}
         */
        private void accept(LambdaDesugarer.Unit unit, UnitAttempt accepted, Step first, Step second) {
            for (Map.Entry<String, LambdaDesugarer.ClassPlan> entry : unit.classes().entrySet()) {
                String entryName = entry.getKey();
                byte[] original = entry.getValue().original();
                Attempt result = accepted == null ? Attempt.UNCHANGED : accepted.results.get(entryName);
                byte[] output = result.bytes == null ? original : result.bytes;
                countNestClass(entryName, original, output, result, first, second);
                List<Generated> following = accepted == null ? List.of()
                        : accepted.generated.getOrDefault(entryName, List.of());
                for (Generated generatedClass : following) {
                    saved[desugarIndex] -= generatedClass.bytes().length;
                }
                generated += following.size();
                planned.put(entryName, new Planned(output, following, output != original));
            }
            if (accepted != null) {
                sites += unit.sites();
                bridges += unit.bridges();
            }
        }

        /**
         * Counts one class of a nest for every counted step.
         */
        private void countNestClass(String entryName, byte[] original, byte[] output, Attempt result, Step first,
                                    Step second) {
            for (int i = 0; i < steps.size(); i++) {
                if (counted[i]) {
                    Step step = steps.get(i);
                    count(i, dropped(i, step, entryName, original, first, second), result.active.contains(step),
                            original.length - output.length);
                }
            }
        }

        /**
         * Whether a step that a nest dropped would have run over one of its classes: desugaring always, another
         * step when it applies to the entry and its pre-filter matches.
         */
        private boolean dropped(int index, Step step, String entryName, byte[] original, Step first, Step second) {
            return (step == first || step == second)
                    && (step == desugarer || applies[index] && step.matches(entryName, original));
        }

        /**
         * Counts one class for one step: as a fallback, as rewritten with what it saved, or as unchanged.
         */
        private void count(int index, boolean fallback, boolean active, int bytesSaved) {
            if (fallback) {
                fallbacks[index]++;
            } else if (active) {
                rewritten[index]++;
                saved[index] += bytesSaved;
            } else {
                unchanged[index]++;
            }
        }

        /**
         * The steps, other than the planning one and {@code without}, whose pre-filter matches a class.
         */
        private List<Step> candidates(String entryName, byte[] original, Step without) {
            List<Step> candidates = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (applies[i] && i != desugarIndex && step != without && step.matches(entryName, original)) {
                    candidates.add(step);
                }
            }
            return candidates;
        }

        /**
         * Counts what happened to one class and returns the bytes to write.
         */
        private byte[] settle(String entryName, byte[] original, Attempt accepted, Step firstFailure,
                              Step secondFailure) {
            byte[] output = accepted.bytes == null ? original : accepted.bytes;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (!counted[i]) {
                    continue;
                }
                if (step == firstFailure || step == secondFailure
                        || i == desugarIndex && declined.contains(entryName)) {
                    fallbacks[i]++;
                } else if (accepted.active.contains(step)) {
                    rewritten[i]++;
                    saved[i] += original.length - output.length;
                } else {
                    unchanged[i]++;
                }
            }
            return output;
        }

        /**
         * Parses, transforms and gates one class with some steps.
         *
         * @param nest what the planning step does to the class as part of its nest, or {@code null} when the class
         *             is processed on its own
         */
        private Attempt attempt(byte[] original, List<Step> candidates, LambdaDesugarer.ClassPlan nest) {
            Attribution attribution = new Attribution();
            attribution.blame = candidates.get(0);
            try {
                return transformed(original, candidates, nest, attribution);
            } catch (Gate.Failure failure) {
                return Attempt.failed(failure.step == null ? attribution.blame : failure.step,
                        describe(failure.getCause()));
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                Step step = attribution.current == null ? attribution.blame : attribution.current;
                return Attempt.failed(step, describe(failure));
            }
        }

        /**
         * The body of {@link #attempt(byte[], List, LambdaDesugarer.ClassPlan)}, which keeps the step to blame up to
         * date as it asks and runs each one.
         */
        private Attempt transformed(byte[] original, List<Step> candidates, LambdaDesugarer.ClassPlan nest,
                                    Attribution attribution) {
            boolean rebuild = rebuildsPool(candidates);
            ClassFile context = rebuild ? rebuilt : shared;
            ClassModel model = context.parse(original);
            List<Step> active = new ArrayList<>(candidates.size());
            for (Step step : candidates) {
                attribution.blame = step;
                if (step == desugarer ? nest != null : step.changes(model)) {
                    active.add(step);
                }
            }
            if (active.isEmpty()) {
                return Attempt.UNCHANGED;
            }
            attribution.blame = active.get(0);
            if (rebuild && !rebuildsPool(active)) {
                // Rule a: the step that rebuilds the pool declined, so every other step keeps it shared, and the
                // class must be parsed again with its debug elements.
                context = shared;
                model = context.parse(original);
            }
            ClassTransform transform = null;
            for (Step step : active) {
                attribution.blame = step;
                ClassTransform own = step == desugarer ? nest.transform() : step.transform(model);
                ClassTransform gated = new Gate(step, attribution).andThen(own);
                transform = transform == null ? gated : transform.andThen(gated);
            }
            attribution.blame = active.get(0);
            transform = transform.andThen(new Gate(null, attribution))
                    .andThen(OriginalFrames.of(model).reattaching());
            byte[] output = context.transformClass(model, transform);
            attribution.current = null;
            return gated(original, output, active);
        }

        /**
         * Accepts the output of the steps that changed a class, unless it is not smaller and the only step skips
         * such an output, or it verifies worse than the original.
         */
        private Attempt gated(byte[] original, byte[] output, List<Step> active) {
            if (active.size() == 1 && active.get(0).skipsLargerOutput() && output.length >= original.length) {
                return Attempt.UNCHANGED;
            }
            List<String> errors = verifier.apply(output);
            if (!errors.isEmpty()) {
                String grown = grown(errors, verifier.apply(original));
                if (grown != null) {
                    return Attempt.failed(active.get(0), "verification: " + grown);
                }
            }
            return new Attempt(output, active, null, null);
        }
    }

    /**
     * The outcome of one attempt at a planned nest: every class's attempt and the generated classes of each host, or
     * the step to drop and why.
     */
    private static final class UnitAttempt {

        private final Map<String, Attempt> results;
        private final Map<String, List<Generated>> generated;
        private final Step failed;
        private final String error;

        private UnitAttempt(Map<String, Attempt> results, Map<String, List<Generated>> generated, Step failed,
                            String error) {
            this.results = results;
            this.generated = generated;
            this.failed = failed;
            this.error = error;
        }

        private static UnitAttempt failed(Step step, String error) {
            return new UnitAttempt(Map.of(), Map.of(), step, oneLine(error));
        }
    }

    /**
     * The outcome of one attempt at a class: accepted bytes and the steps that changed them, no change at all, or
     * the step to drop and why.
     */
    private static final class Attempt {

        private static final Attempt UNCHANGED = new Attempt(null, List.of(), null, null);

        private final byte[] bytes;
        private final List<Step> active;
        private final Step failed;
        private final String error;

        private Attempt(byte[] bytes, List<Step> active, Step failed, String error) {
            this.bytes = bytes;
            this.active = active;
            this.failed = failed;
            this.error = error;
        }

        private static Attempt failed(Step step, String error) {
            return new Attempt(null, List.of(), step, oneLine(error));
        }
    }

    /**
     * Which step's start or end handler is running, for a failure that no gate sees: the handlers of a chain run
     * one after the other, not inside each other.
     */
    private static final class Attribution {
        /** The step whose start or end handler is running, set by the gates. */
        private Step current;
        /** The step to blame for a failure outside the gates: the one being asked or set up, or the first. */
        private Step blame;
    }

    /**
     * Placed in front of each step, and in front of the frame re-attachment, to tell which one failed.
     *
     * <p>Transforms composed with {@code andThen} push each element downstream from inside the upstream transform's
     * own call, so a failure in a step unwinds through the gate in front of it before it reaches any gate further
     * up; the innermost gate names the step and the others pass the failure on. A gate is an ordinary transform
     * that forwards every element, which keeps the steps' own transforms, resolved or not, untouched.</p>
     */
    private static final class Gate implements ClassTransform {

        private final Step step;
        private final Attribution attribution;

        private Gate(Step step, Attribution attribution) {
            this.step = step;
            this.attribution = attribution;
        }

        @Override
        public void accept(ClassBuilder builder, ClassElement element) {
            try {
                builder.with(element);
            } catch (Failure failure) {
                throw failure;
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                throw new Failure(step, failure);
            }
        }

        @Override
        public void atStart(ClassBuilder builder) {
            attribution.current = step;
        }

        @Override
        public void atEnd(ClassBuilder builder) {
            attribution.current = step;
        }

        /**
         * A failure a gate attributed; a {@code null} step means the frame re-attachment.
         */
        private static final class Failure extends RuntimeException {

            private final transient Step step;

            private Failure(Step step, Throwable cause) {
                super(cause.getMessage(), cause, false, false);
                this.step = step;
            }
        }
    }
}
