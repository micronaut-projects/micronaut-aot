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
package io.micronaut.aot.serviceindex;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Generates micronaut-core's service index for a packaged application: a {@code StaticOptimizations.Loader} that
 * registers a {@code ServiceIndex} of the application's class path, so that service loading at run time does not
 * scan {@code META-INF/services} and {@code META-INF/micronaut}.
 *
 * <p>It is the engine the Micronaut Gradle and Maven plugins and Micronaut Runner share: they call
 * {@link #generate(Request)} in-process with plain paths, once the class path is final, and get class files back. It
 * is internal to them, not an API for applications or other build tools, and may change in any release.</p>
 *
 * <p>The index is built by the application's own micronaut-core, with its {@code ServiceIndexBuilder}, in an
 * isolated class loader of the class path, whose parent is the platform class loader. The generated loader is
 * emitted with the ClassFile API, verified, and then run in that micronaut-core, which must rebuild exactly the
 * builder's index before anything is returned. No class of the application is loaded or initialised, and nothing of
 * the caller's class path is used.</p>
 *
 * @since 3.2.0
 */
@Internal
public final class ServiceIndexGenerator {

    /** The package of the generated classes, the same in every packaging. */
    static final String GENERATED_PACKAGE = "io.micronaut.aot.serviceindex.generated";

    /** The binary name of the generated loader. */
    static final String LOADER_CLASS = GENERATED_PACKAGE + ".ServiceIndexLoader";

    /** The entry names of the generated package. */
    static final String GENERATED_PATH = GENERATED_PACKAGE.replace('.', '/') + '/';

    /** The service file that registers the loaders of micronaut-core's static optimizations. */
    static final String LOADER_SERVICE = "META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader";

    /**
     * The service types that micronaut-core looks up at every start, which usually have neither a
     * {@code META-INF/services} file nor a {@code META-INF/micronaut} directory. Indexing them with no names saves
     * their scans; on a class path that holds no file of theirs, which is what the index describes, that is exact.
     */
    static final List<String> ALWAYS_INDEXED = List.of(
            "io.micronaut.context.ApplicationContextConfigurer",
            "io.micronaut.context.env.PropertyExpressionResolver");

    private ServiceIndexGenerator() {
    }

    /**
     * Generates the service index of a class path, or says why it does not.
     *
     * <p>It never throws for anything about the inputs: a missing entry, an unreadable file and any failure of
     * micronaut-core or of the emitter, {@link Error}s included, are a {@link Result} that generated nothing. Only a
     * {@link VirtualMachineError} propagates. It keeps no state, so calls are independent of each other; before it
     * returns, the class loaders it opened are closed and the caller's context class loader is restored.</p>
     *
     * @param request the class path and the entry that will hold the index
     * @return what was generated, or why nothing was
     */
    public static Result generate(Request request) {
        Objects.requireNonNull(request, "request");
        return Result.standDown("not implemented");
    }

    /**
     * The class path an index is generated for: plain paths, no build tool type. Instances are immutable.
     *
     * @since 3.2.0
     */
    @Internal
    public static final class Request {

        private final List<Path> files;
        private final @Nullable List<String> runtimeNames;
        private final int holder;

        private Request(Builder builder) {
            if (builder.files.isEmpty()) {
                throw new IllegalStateException("the class path is required");
            }
            Set<Path> seen = new HashSet<>();
            for (Path file : builder.files) {
                if (!seen.add(normalized(file))) {
                    throw new IllegalStateException(file + " is twice on the class path");
                }
            }
            int named = 0;
            for (String name : builder.names) {
                if (name != null) {
                    named++;
                }
            }
            if (named != 0 && named != builder.names.size()) {
                throw new IllegalStateException("give every class path entry its runtime name, or none");
            }
            if (builder.holder == null) {
                throw new IllegalStateException("the holder is required");
            }
            int index = builder.files.stream().map(Request::normalized).toList().indexOf(normalized(builder.holder));
            if (index < 0) {
                throw new IllegalStateException("the holder " + builder.holder + " is not an entry of the class path");
            }
            this.files = List.copyOf(builder.files);
            this.runtimeNames = named == 0 ? null : List.copyOf(builder.names);
            this.holder = index;
        }

        /**
         * Starts a request.
         *
         * @return a builder with nothing set
         */
        public static Builder builder() {
            return new Builder();
        }

        /**
         * @return the class path entries, as files of the machine that builds, in class path order
         */
        List<Path> files() {
            return files;
        }

        /**
         * @return the file name of each entry when the application runs, or {@code null} for an index without a class
         *         path
         */
        @Nullable List<String> runtimeNames() {
            return runtimeNames;
        }

        /**
         * @return the position of the holder in {@link #files()}
         */
        int holder() {
            return holder;
        }

        private static Path normalized(Path path) {
            return path.toAbsolutePath().normalize();
        }

        /**
         * Builds a {@link Request}. At least one {@link #entry(Path) entry} and the {@link #holder(Path) holder} are
         * required.
         *
         * @since 3.2.0
         */
        @Internal
        public static final class Builder {

            private final List<Path> files = new ArrayList<>();
            private final List<@Nullable String> names = new ArrayList<>();
            private @Nullable Path holder;

            private Builder() {
            }

            /**
             * Adds the next entry of the class path the application runs with, without a runtime name: the index then
             * lists no class path, and micronaut-core does not compare it at run time. That is for a packaging that
             * cannot know the names its files have when the application runs, such as Micronaut Runner's.
             *
             * <p>Give the entries in the order of the class path at run time, as files of the machine that builds,
             * once nothing changes them any more: for {@code java -jar}, the jar, then the entries of its manifest
             * {@code Class-Path} that exist when the application runs. The class path cannot mix entries with and
             * without a runtime name.</p>
             *
             * @param file a jar or a directory
             * @return this builder
             */
            public Builder entry(Path file) {
                return add(file, null);
            }

            /**
             * Adds the next entry of the class path the application runs with, and the file name it has when the
             * application runs, which micronaut-core compares at run time: see {@link #entry(Path)}.
             *
             * @param file        a jar or a directory
             * @param runtimeName the entry's file name when the application runs, such as {@code application.jar}
             * @return this builder
             */
            public Builder entry(Path file, String runtimeName) {
                Objects.requireNonNull(runtimeName, "runtimeName");
                if (runtimeName.isBlank() || runtimeName.indexOf('/') >= 0 || runtimeName.indexOf('\\') >= 0) {
                    throw new IllegalArgumentException("not a file name: '" + runtimeName + "'");
                }
                return add(file, runtimeName);
            }

            /**
             * The entry of the class path that the caller writes the {@link Result} into: the application's jar, or
             * its class directory. Required.
             *
             * @param entry one of the {@linkplain #entry(Path) entries}
             * @return this builder
             */
            public Builder holder(Path entry) {
                this.holder = Objects.requireNonNull(entry, "entry");
                return this;
            }

            /**
             * Builds the request.
             *
             * @return the request
             * @throws IllegalStateException if there is no entry or no holder, the holder is not an entry, an entry
             *                               is given twice, or only some entries have a runtime name
             */
            public Request build() {
                return new Request(this);
            }

            private Builder add(Path file, @Nullable String runtimeName) {
                files.add(Objects.requireNonNull(file, "file"));
                names.add(runtimeName);
                return this;
            }
        }
    }

    /**
     * The outcome of one generation: its {@link #status()}, one {@link #message()} for the caller to log, the
     * {@link #entries()} to write into the holder and the {@link #removals()} to delete from it.
     *
     * @since 3.2.0
     */
    @Internal
    public static final class Result {

        private final Status status;
        private final String message;
        private final Map<String, byte[]> entries;
        private final Set<String> removals;

        private Result(Status status, String message, Map<String, byte[]> entries, SortedSet<String> removals) {
            this.status = status;
            this.message = message;
            this.entries = Collections.unmodifiableMap(entries);
            this.removals = Collections.unmodifiableSortedSet(removals);
        }

        /**
         * What happened.
         *
         * @return the status
         */
        public Status status() {
            return status;
        }

        /**
         * One line for the caller to log: what was generated, or why nothing was. It is informational for
         * {@link Status#GENERATED} and {@link Status#STOOD_DOWN}, and worth a warning for {@link Status#FAILED}.
         *
         * @return the line
         */
        public String message() {
            return message;
        }

        /**
         * The entries to write into the holder, by entry name, in order: the classes of the package
         * {@code io.micronaut.aot.serviceindex.generated}, and the whole new
         * {@code META-INF/services/io.micronaut.core.optim.StaticOptimizations$Loader} file, which names the generated
         * loader first and then keeps the lines the holder had. Each replaces an entry of the same name.
         *
         * @return the entries, unmodifiable; empty unless {@link #status()} is {@link Status#GENERATED}
         */
        public Map<String, byte[]> entries() {
            return entries;
        }

        /**
         * The entries to delete from the holder: what an earlier generation left in the package
         * {@code io.micronaut.aot.serviceindex.generated} and {@link #entries()} does not replace. A JVM can register
         * one index only, so the earlier one has to go.
         *
         * @return the entry names, unmodifiable, sorted; empty unless {@link #status()} is {@link Status#GENERATED}
         */
        public Set<String> removals() {
            return removals;
        }

        private static Result standDown(String reason) {
            return new Result(Status.STOOD_DOWN, "No service index was generated because " + reason
                    + "; the application scans its class path for services at run time", Map.of(), new TreeSet<>());
        }
    }

    /**
     * What a generation did.
     *
     * @since 3.2.0
     */
    @Internal
    public enum Status {

        /** An index was generated. */
        GENERATED,

        /**
         * Nothing was generated because the class path is not one the index can describe, which is expected for some
         * applications: for example, its micronaut-core has no service index. The application scans its class path
         * at run time, as without this step.
         */
        STOOD_DOWN,

        /**
         * Nothing was generated because something went wrong: an entry is missing or cannot be read, a generated
         * name is taken, or micronaut-core, the emitter or the check of the generated loader failed.
         */
        FAILED
    }
}
