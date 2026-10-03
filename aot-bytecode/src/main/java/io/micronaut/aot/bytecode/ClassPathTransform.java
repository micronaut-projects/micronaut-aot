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

import io.micronaut.core.annotation.Internal;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rewrites the class files of an application's runtime class path at build time, for the build plugins to package
 * the result instead of the original jars.
 *
 * <p>The only step today strips the local-variable tables of the third-party jars a caller names
 * ({@link Request.Builder#stripLocalVariables(Collection)}). Each class is parsed, rewritten and verified once,
 * against a model of the whole class path, never against the build tool's own class path; a class whose rewrite
 * verifies worse than the original, or on which the step fails, is written as it was. A jar with a rewritten class is
 * copied whole, under its own file name, into a directory of its own below the request's output directory; every
 * other class path entry keeps its own path, its bytes and its hash. The transform never loads a class of the class
 * path, and it logs nothing: the {@link Result} carries a summary and warnings for the caller to log, and the counts
 * of each jar as numbers, for the caller to report in its own format.</p>
 *
 * <p>The rewritten bytes depend on the JDK that runs the transform: they are reproducible with the same JDK build,
 * whatever the parallelism.</p>
 *
 * @since 3.2.0
 */
@Internal
public final class ClassPathTransform {

    private static final AtomicInteger THREADS = new AtomicInteger();

    private ClassPathTransform() {
    }

    /**
     * Rewrites what it can prove safe and leaves everything else as it is. It throws only when it cannot read a
     * class path entry it rewrites, or cannot write its output, never because of a class it cannot rewrite.
     *
     * @param request what to rewrite
     * @return the class path to package, and what was done
     * @throws IOException if a jar to rewrite, or an entry of the class path, cannot be read, or a copy cannot be
     *                     written
     */
    public static Result run(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        List<Path> classPath = request.classPath;
        ExecutorService pool = request.parallelism == 1 ? null
                : Executors.newFixedThreadPool(request.parallelism, ClassPathTransform::thread);
        try {
            List<Callable<ClassPathModel.LayerScan>> scans = new ArrayList<>(classPath.size());
            for (Path entry : classPath) {
                scans.add(() -> scan(entry));
            }
            ClassPathModel model = ClassPathModel.merge(all(scans, pool));

            List<String> warnings = new ArrayList<>();
            Optional<ClassPathModel.Watched> reader = model.watched();
            if (reader.isPresent()) {
                warnings.add("No local-variable table was stripped, because the class path entry "
                        + reader.get().layer() + " contains " + reader.get().entry()
                        + ", which reads local-variable tables at run time");
                return new Result(classPath, "Stripped no local-variable table, because a library on the class"
                        + " path reads them at run time", warnings, List.of());
            }
            ClassTransformPipeline pipeline = new ClassTransformPipeline(List.of(new LocalVariableStripper()), model);
            List<Integer> positions = new ArrayList<>();
            List<Path> targets = new ArrayList<>();
            List<Callable<JarRewriter.Outcome>> rewrites = new ArrayList<>();
            for (int position = 0; position < classPath.size(); position++) {
                if (request.stripped[position]) {
                    Path source = classPath.get(position);
                    Path target = request.outputDirectory.resolve(Integer.toString(position))
                            .resolve(source.getFileName().toString());
                    positions.add(position);
                    targets.add(target);
                    rewrites.add(() -> JarRewriter.rewrite(source, target, pipeline, source.toString(), true));
                }
            }
            List<JarRewriter.Outcome> outcomes = all(rewrites, pool);

            List<Path> result = new ArrayList<>(classPath);
            List<ClassTransformPipeline.JarReport> reports = new ArrayList<>(outcomes.size());
            List<Result.Entry> entries = new ArrayList<>(outcomes.size());
            for (int i = 0; i < outcomes.size(); i++) {
                JarRewriter.Outcome outcome = outcomes.get(i);
                reports.add(outcome.report());
                entries.add(new Result.Entry(classPath.get(positions.get(i)), outcome));
                if (outcome.written()) {
                    result.set(positions.get(i), targets.get(i));
                }
            }
            return new Result(result, String.join("\n", pipeline.summaries(reports)), warnings, entries);
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
    }

    private static ClassPathModel.LayerScan scan(Path entry) throws IOException {
        try {
            return ClassPathModel.scan(entry, entry.toString(), LocalVariableStripper::isKnownReader);
        } catch (IOException e) {
            throw new IOException("Cannot read the class path entry " + entry + ": "
                    + ClassTransformPipeline.describe(e), e);
        }
    }

    /**
     * Runs tasks on the pool, or on the calling thread without one, and returns their results in order. When
     * several fail, the failure of the first one in order is thrown.
     */
    private static <T> List<T> all(List<Callable<T>> tasks, ExecutorService pool) throws IOException {
        List<T> results = new ArrayList<>(tasks.size());
        if (pool == null) {
            for (Callable<T> task : tasks) {
                results.add(call(task));
            }
            return results;
        }
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(pool.submit(task));
        }
        for (Future<T> future : futures) {
            results.add(join(future));
        }
        return results;
    }

    /**
     * Runs a task on the calling thread.
     */
    private static <T> T call(Callable<T> task) throws IOException {
        try {
            return task.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * Waits for a task on the pool and throws what it threw.
     */
    private static <T> T join(Future<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while rewriting the class path");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    private static Thread thread(Runnable task) {
        Thread thread = new Thread(task, "micronaut-aot-bytecode-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    }

    /**
     * What to rewrite. A request enables at least one step.
     */
    @Internal
    public static final class Request {

        private final List<Path> classPath;
        private final Path outputDirectory;
        private final boolean[] stripped;
        private final int parallelism;

        private Request(List<Path> classPath, Path outputDirectory, boolean[] stripped, int parallelism) {
            this.classPath = classPath;
            this.outputDirectory = outputDirectory;
            this.stripped = stripped;
            this.parallelism = parallelism;
        }

        /**
         * A builder for a request.
         *
         * @return a new builder
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * Builds a {@link Request}.
         */
        @Internal
        public static final class Builder {

            private List<Path> classPath;
            private Path outputDirectory;
            private List<Path> stripLocalVariables = List.of();
            private int parallelism = Runtime.getRuntime().availableProcessors();

            private Builder() {
            }

            /**
             * The runtime class path, in the order the application's class loader searches it, the application's own
             * output first: jars or directories. Required. Every class on it is part of the model that rewritten
             * classes are verified against; only the entries a step names are rewritten.
             *
             * @param entries the class path entries
             * @return this builder
             */
            public Builder classPath(List<Path> entries) {
                this.classPath = List.copyOf(entries);
                return this;
            }

            /**
             * Where rewritten copies are written: each under a subdirectory named by its entry's index in the class
             * path, with the entry's own file name. Required. The caller owns the directory and cleans it. It must
             * not hold an entry of the class path, such as a copy that an earlier run wrote there, because a run
             * replaces and deletes files in it.
             *
             * @param directory the output directory
             * @return this builder
             */
            public Builder outputDirectory(Path directory) {
                this.outputDirectory = Objects.requireNonNull(directory, "directory");
                return this;
            }

            /**
             * Strip the local-variable tables of the classes in these jars: third-party jars only, never the
             * application's output, a module of the application's own build or a directory. Default: none.
             *
             * <p>A non-empty collection enables the step. Each jar must be an entry of the class path. The step
             * drops {@code LocalVariableTable}, {@code LocalVariableTypeTable}, {@code CharacterRangeTable}, the type
             * annotations of code and the invisible type annotations, and keeps line numbers, source file names,
             * parameter names, signatures and every other annotation. It strips nothing, and the result carries a
             * warning, when the class path holds a library that reads local-variable tables at run time.</p>
             *
             * @param thirdPartyJars the jars whose classes are stripped
             * @return this builder
             */
            public Builder stripLocalVariables(Collection<Path> thirdPartyJars) {
                this.stripLocalVariables = List.copyOf(thirdPartyJars);
                return this;
            }

            /**
             * The number of worker threads. Default: the available processors. The output does not depend on it.
             *
             * @param threads the number of threads, at least one
             * @return this builder
             */
            public Builder parallelism(int threads) {
                if (threads < 1) {
                    throw new IllegalArgumentException("The parallelism must be at least 1: " + threads);
                }
                this.parallelism = threads;
                return this;
            }

            /**
             * Builds the request.
             *
             * @return the request
             * @throws IllegalStateException    if the class path or the output directory is missing, or no step is
             *                                  enabled
             * @throws IllegalArgumentException if a jar to strip is not an entry of the class path, or is a
             *                                  directory, or if an entry of the class path is inside the output
             *                                  directory
             */
            public Request build() {
                if (classPath == null) {
                    throw new IllegalStateException("The class path is required");
                }
                if (outputDirectory == null) {
                    throw new IllegalStateException("The output directory is required");
                }
                if (stripLocalVariables.isEmpty()) {
                    throw new IllegalStateException("No step is enabled: name the jars to strip");
                }
                Path output = real(outputDirectory);
                List<Path> normalized = new ArrayList<>(classPath.size());
                for (Path entry : classPath) {
                    normalized.add(entry.toAbsolutePath().normalize());
                    if (real(entry).startsWith(output)) {
                        throw new IllegalArgumentException("The class path entry " + entry
                                + " is inside the output directory " + outputDirectory);
                    }
                }
                boolean[] stripped = new boolean[classPath.size()];
                for (Path jar : stripLocalVariables) {
                    Path key = jar.toAbsolutePath().normalize();
                    if (Files.isDirectory(key)) {
                        throw new IllegalArgumentException("A directory is never stripped: " + jar);
                    }
                    if (!mark(normalized, key, stripped)) {
                        throw new IllegalArgumentException("The jar to strip " + jar
                                + " is not an entry of the class path");
                    }
                }
                return new Request(classPath, outputDirectory, stripped, parallelism);
            }

            /**
             * Marks every position of the class path that holds a jar.
             *
             * @return whether the jar is an entry of the class path
             */
            private static boolean mark(List<Path> normalized, Path key, boolean[] stripped) {
                boolean found = false;
                for (int position = 0; position < normalized.size(); position++) {
                    if (normalized.get(position).equals(key)) {
                        stripped[position] = true;
                        found = true;
                    }
                }
                return found;
            }

            /**
             * A path with its symbolic links resolved when it exists, so that two spellings of one file compare
             * equal; otherwise absolute and normalized, as nothing can be inside a directory that does not exist.
             */
            private static Path real(Path path) {
                Path absolute = path.toAbsolutePath().normalize();
                try {
                    return absolute.toRealPath();
                } catch (IOException e) {
                    return absolute;
                }
            }
        }
    }

    /**
     * What a run did.
     */
    @Internal
    public static final class Result {

        private final List<Path> classPath;
        private final String summary;
        private final List<String> warnings;
        private final List<Entry> entries;

        private Result(List<Path> classPath, String summary, List<String> warnings, List<Entry> entries) {
            this.classPath = List.copyOf(classPath);
            this.summary = summary;
            this.warnings = List.copyOf(warnings);
            this.entries = List.copyOf(entries);
        }

        /**
         * The class path to package: each entry of the request, or its rewritten copy, in the same order. An entry
         * with nothing rewritten keeps its own path, and so its bytes and its hash.
         *
         * @return the class path
         */
        public List<Path> classPath() {
            return classPath;
        }

        /**
         * One line per enabled step for the caller to log, such as {@code Stripped local-variable tables from 7102
         * of 8428 dependency classes in 49 jars (4868965 bytes saved, 0 fallbacks)}.
         *
         * @return the summary
         */
        public String summary() {
            return summary;
        }

        /**
         * Lines for the caller to log as warnings, each on its own, such as the library on the class path that made
         * the strip step stand down. Usually empty, never {@code null}.
         *
         * @return the warnings
         */
        public List<String> warnings() {
            return warnings;
        }

        /**
         * What the strip step did to each jar it was given, in class-path order: one element per class path entry
         * named by {@link Request.Builder#stripLocalVariables(Collection)}, whether it was rewritten or not. The
         * counts are numbers, for the caller to write its report in its own format. Empty when no step ran, because
         * a library on the class path reads local-variable tables.
         *
         * @return the entries
         */
        public List<Entry> entries() {
            return entries;
        }

        /**
         * What the strip step did to one jar. Every class of the jar is counted once, as stripped, unchanged or
         * fallen back.
         */
        @Internal
        public static final class Entry {

            private final Path path;
            private final int classesStripped;
            private final int classesUnchanged;
            private final int fallbacks;
            private final long bytesSaved;
            private final String kept;
            private final List<String> notes;

            private Entry(Path path, JarRewriter.Outcome outcome) {
                // The pipeline runs one step, the strip step, so its count is the only one.
                ClassTransformPipeline.StepCount count = outcome.report().counts().get(0);
                this.path = path;
                this.classesStripped = count.rewritten();
                this.classesUnchanged = count.unchanged();
                this.fallbacks = count.fallbacks();
                this.bytesSaved = count.bytesSaved();
                this.kept = outcome.kept();
                this.notes = outcome.report().notes();
            }

            /**
             * The jar, as the request's class path gave it.
             *
             * @return the jar
             */
            public Path path() {
                return path;
            }

            /**
             * The classes written without their local-variable tables.
             *
             * @return the number of classes stripped
             */
            public int classesStripped() {
                return classesStripped;
            }

            /**
             * The classes the step left as they were: nothing to strip, a {@code module-info}, an attribute the JDK
             * does not know, a rewrite that is not smaller, a class too large to read, or a jar left as it is
             * ({@link #kept()}).
             *
             * @return the number of classes unchanged
             */
            public int classesUnchanged() {
                return classesUnchanged;
            }

            /**
             * The classes written as they were because the step failed on them or their rewrite verified worse than
             * the original. Each has a line in {@link #notes()}.
             *
             * @return the number of fallbacks
             */
            public int fallbacks() {
                return fallbacks;
            }

            /**
             * How many bytes smaller the stripped classes became, uncompressed.
             *
             * @return the bytes saved
             */
            public long bytesSaved() {
                return bytesSaved;
            }

            /**
             * Why no class of the jar went through the step, such as {@code the jar is signed}; empty when they
             * did.
             *
             * @return the reason, if the jar was left as it is
             */
            public Optional<String> kept() {
                return Optional.ofNullable(kept);
            }

            /**
             * One line per fallback, for the caller to log or write: tab-separated, the jar, the class, the step and
             * the first error.
             *
             * @return the notes, never {@code null}
             */
            public List<String> notes() {
                return notes;
            }

            @Override
            public String toString() {
                return "Entry[path=" + path + ", classesStripped=" + classesStripped + ", classesUnchanged="
                        + classesUnchanged + ", fallbacks=" + fallbacks + ", bytesSaved=" + bytesSaved
                        + (kept == null ? "" : ", kept=" + kept) + ", notes=" + notes + "]";
            }
        }
    }
}
