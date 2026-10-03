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

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Rewrites the classes of a class path entry: it parses each class once, runs the enabled steps in a fixed order,
 * verifies the result and hands it back once.
 *
 * <h2>Steps</h2>
 * <p>A {@link Step} supplies a pre-filter that sees the entry name and the bytes as read from the jar, a check of
 * whether it changes a parsed class, and the {@link ClassTransform} that makes the change. Enabled steps run in a
 * fixed order; their transforms are composed with {@link ClassTransform#andThen(ClassTransform)}, so a class is
 * parsed and written once whatever runs.</p>
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
 * the class already had moves with them.</p>
 *
 * <p>The fallback rule is the same for every step. When step S throws, or the rewrite verifies worse, the class
 * starts again from its original bytes without S (for a verification failure, without the earliest step that
 * changed the class) and is gated again. Only if that attempt fails too is the class written as it was. One note
 * names the class, the dropped step and the first error; a false positive costs one step on one class.</p>
 *
 * <p>The pipeline never logs. Each jar's {@link JarRun} counts and notes what happened, and hands its
 * {@link JarReport} back to the caller, which reports them in class-path order. The output does not depend on the
 * thread count, and a pipeline is safe to share between threads: it holds immutable ClassFile contexts and the
 * class path model, and all per-jar state lives in the jar's run.</p>
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
     * Starts the run over one jar. The run is confined to the thread that rewrites the jar.
     *
     * @param layer the jar
     * @return its run
     */
    JarRun start(Layer layer) {
        return new JarRun(layer);
    }

    /**
     * Adds up the reports of every jar, one total per step, in step order.
     *
     * @param reports one report per jar, in class-path order
     * @return the totals, each named after its step
     */
    List<StepCount> totals(List<JarReport> reports) {
        List<StepCount> totals = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            int rewritten = 0;
            int unchanged = 0;
            int fallbacks = 0;
            long saved = 0;
            for (JarReport report : reports) {
                StepCount count = report.counts().get(i);
                rewritten += count.rewritten();
                unchanged += count.unchanged();
                fallbacks += count.fallbacks();
                saved += count.bytesSaved();
            }
            totals.add(new StepCount(steps.get(i).name(), rewritten, unchanged, fallbacks, saved));
        }
        return totals;
    }

    /**
     * The line the caller logs for each step once every jar is rewritten, in step order.
     *
     * @param reports one report per jar, in class-path order
     * @return one line per step
     */
    List<String> summaries(List<JarReport> reports) {
        List<StepCount> totals = totals(reports);
        List<String> lines = new ArrayList<>(steps.size());
        for (int i = 0; i < steps.size(); i++) {
            lines.add(steps.get(i).summary(totals.get(i), reports.size()));
        }
        return lines;
    }

    private static String withoutOffset(String error) {
        return BYTECODE_OFFSET.matcher(error).replaceAll("@");
    }

    private static String describe(Throwable failure) {
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
         * Whether the step applies to any class of a jar at all.
         *
         * @param layer the jar
         * @return whether it applies
         */
        boolean appliesTo(Layer layer);

        /**
         * A cheap pre-filter on the bytes as read from the jar. A class no enabled step's filter matches is written
         * byte for byte, without being parsed.
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
         * The line the caller logs for the step once every jar is rewritten.
         *
         * @param total what the step did, over every jar
         * @param jars  the number of jars the pipeline ran over
         * @return the line
         */
        String summary(StepCount total, int jars);
    }

    /**
     * The jar a run works on.
     *
     * @param name       what notes and reports call it: the class path entry as the caller gave it
     * @param signed     whether it carries signature files
     * @param thirdParty whether the caller declared it a third-party jar, which the strip step applies to
     */
    record Layer(String name, boolean signed, boolean thirdParty) {
    }

    /**
     * What one step did to one jar.
     *
     * @param step       the step's name
     * @param rewritten  the classes it rewrote
     * @param unchanged  the classes it left alone
     * @param fallbacks  the classes it gave up on
     * @param bytesSaved how much smaller the classes it rewrote became
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
     * What the pipeline did to one jar.
     *
     * @param jar    what the run calls the jar
     * @param counts one count per enabled step, in step order
     * @param notes  one line per fallback, tab-separated: the jar, the class, the dropped step and the first error
     */
    record JarReport(String jar, List<StepCount> counts, List<String> notes) {

        /**
         * Makes the lists immutable.
         *
         * @param jar    the jar
         * @param counts the counts
         * @param notes  the notes
         */
        JarReport {
            counts = List.copyOf(counts);
            notes = List.copyOf(notes);
        }
    }

    /**
     * The pipeline's work on one jar. It is confined to one thread: it holds the jar's counters and its notes.
     */
    final class JarRun {

        private final Layer layer;
        private final boolean[] applies;
        private final boolean any;
        private final int[] rewritten;
        private final int[] unchanged;
        private final int[] fallbacks;
        private final long[] saved;
        private final List<String> notes = new ArrayList<>();

        private JarRun(Layer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            int count = steps.size();
            applies = new boolean[count];
            boolean applicable = false;
            for (int i = 0; i < count; i++) {
                // Rule d: nothing in a signed jar is rewritten, whatever the step says.
                applies[i] = !layer.signed() && steps.get(i).appliesTo(layer);
                applicable |= applies[i];
            }
            any = applicable;
            rewritten = new int[count];
            unchanged = new int[count];
            fallbacks = new int[count];
            saved = new long[count];
        }

        /**
         * Whether any step applies to this jar, so that its classes are worth reading at all.
         *
         * @return whether a step applies
         */
        boolean applies() {
            return any;
        }

        /**
         * Whether a class of this size is read into memory and given to {@link #process(String, byte[])}. A class
         * that is not must be passed to {@link #pass()} instead.
         *
         * @param size the class's uncompressed size
         * @return whether the class goes through the pipeline
         */
        boolean reads(long size) {
            return any && size >= 0 && size <= MAX_CLASS_SIZE;
        }

        /**
         * Counts a class that is written without being read: it is too large, or no step applies to the jar.
         */
        void pass() {
            for (int i = 0; i < unchanged.length; i++) {
                unchanged[i]++;
            }
        }

        /**
         * Runs the enabled steps over one class.
         *
         * @param entryName the class's entry name
         * @param original  its bytes, as read from the jar
         * @return the bytes to write: {@code original} itself when no step changed the class
         */
        byte[] process(String entryName, byte[] original) {
            List<Step> candidates = candidates(entryName, original, null);
            if (candidates.isEmpty()) {
                pass();
                return original;
            }
            Attempt first = attempt(original, candidates);
            if (first.failed == null) {
                return settle(original, first, null, null);
            }
            List<Step> remaining = new ArrayList<>(candidates);
            remaining.remove(first.failed);
            Attempt second = remaining.isEmpty() ? Attempt.UNCHANGED : attempt(original, remaining);
            String error = first.error;
            if (second.failed != null) {
                error = oneLine(error + "; the class was kept as it was because " + second.failed.name()
                        + " failed too: " + second.error);
            }
            notes.add(String.join("\t", oneLine(layer.name()), oneLine(entryName), first.failed.name(), error));
            return settle(original, second.failed == null ? second : Attempt.UNCHANGED, first.failed,
                    second.failed);
        }

        /**
         * What the pipeline did to this jar.
         *
         * @return the report
         */
        JarReport report() {
            List<StepCount> counts = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                counts.add(new StepCount(steps.get(i).name(), rewritten[i], unchanged[i], fallbacks[i], saved[i]));
            }
            return new JarReport(layer.name(), counts, notes);
        }

        /**
         * The steps, other than {@code without}, whose pre-filter matches a class.
         */
        private List<Step> candidates(String entryName, byte[] original, Step without) {
            List<Step> candidates = new ArrayList<>(steps.size());
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (applies[i] && step != without && step.matches(entryName, original)) {
                    candidates.add(step);
                }
            }
            return candidates;
        }

        /**
         * Counts what happened to one class and returns the bytes to write.
         */
        private byte[] settle(byte[] original, Attempt accepted, Step firstFailure, Step secondFailure) {
            byte[] output = accepted.bytes == null ? original : accepted.bytes;
            for (int i = 0; i < steps.size(); i++) {
                Step step = steps.get(i);
                if (step == firstFailure || step == secondFailure) {
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
         */
        private Attempt attempt(byte[] original, List<Step> candidates) {
            Attribution attribution = new Attribution();
            Step blame = candidates.get(0);
            try {
                boolean rebuild = rebuildsPool(candidates);
                ClassFile context = rebuild ? rebuilt : shared;
                ClassModel model = context.parse(original);
                List<Step> active = new ArrayList<>(candidates.size());
                for (Step step : candidates) {
                    blame = step;
                    if (step.changes(model)) {
                        active.add(step);
                    }
                }
                if (active.isEmpty()) {
                    return Attempt.UNCHANGED;
                }
                blame = active.get(0);
                if (rebuild && !rebuildsPool(active)) {
                    // Rule a: the step that rebuilds the pool declined, so every other step keeps it shared, and the
                    // class must be parsed again with its debug elements.
                    context = shared;
                    model = context.parse(original);
                }
                ClassTransform transform = null;
                for (Step step : active) {
                    blame = step;
                    ClassTransform stepTransform = new Gate(step, attribution).andThen(step.transform(model));
                    transform = transform == null ? stepTransform : transform.andThen(stepTransform);
                }
                blame = active.get(0);
                transform = transform.andThen(new Gate(null, attribution))
                        .andThen(OriginalFrames.of(model).reattaching());
                byte[] output = context.transformClass(model, transform);
                attribution.current = null;
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
            } catch (Gate.Failure failure) {
                return Attempt.failed(failure.step == null ? blame : failure.step, describe(failure.getCause()));
            } catch (RuntimeException | LinkageError | AssertionError | StackOverflowError failure) {
                Step step = attribution.current == null ? blame : attribution.current;
                return Attempt.failed(step, describe(failure));
            }
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
        private Step current;
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
