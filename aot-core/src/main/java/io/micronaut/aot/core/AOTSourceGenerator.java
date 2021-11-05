/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.aot.core;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A source generator is the main entity of the AOT project.
 * It is responsible for generating sources, or resources, at
 * build time, but unlike annotation processors, source generators
 * can use a variety of different inputs, and can even execute
 * application code at build time to determine what optimizations
 * to generate.
 *
 * <ul>
 *     <li>a static initializer, which is going to be included in the
 *     optimized entry point generated class</li>
 *     <li>one or more source files</li>
 *     <li>one or more resource files</li>
 * </ul>
 */
public interface AOTSourceGenerator {
    /**
     * A unique identifier for this source generator.
     * @return the id
     */
    @NonNull
    String getId();

    /**
     * Returns a description for this source generator.
     * Description is optional because some code generators
     * are purely internal and not exposed to users.
     * @return a description or an empty options
     */
    @NonNull
    default Optional<String> getDescription() {
        return Optional.empty();
    }

    /**
     * Determines if this source generator should be enabled
     * when targetting a particular runtime.
     * @param runtime the target runtime
     * @return true if the source generator should be enabled
     */
    @NonNull
    default boolean isEnabledOn(@NonNull Runtime runtime) {
        return true;
    }

    /**
     * Returns the identifiers of source generators which must
     * be executed before this generator is called.
     * @return the list of ids
     */
    @NonNull
    default Set<String> getDependencies() {
        return Collections.emptySet();
    }

    /**
     * Returns a list of generators which are directly managed (or instantiated_
     * by this source generator. Such optimizers are typically not registered as
     * services because they make no sense in isolation.
     * This method should be used for introspection only.
     *
     * @return the list of sub features
     */
    @NonNull
    default List<AOTSourceGenerator> getSubGenerators() {
        return Collections.emptyList();
    }

    /**
     * Returns the set of configuration keys which affect
     * the configuration of this source generator.
     * @return a set of configuration keys
     */
    @NonNull
    default Set<Option> getConfigurationOptions() {
        return Collections.emptySet();
    }

    /**
     * Initializes the source generator.
     * @param context the context to be injected
     */
    default void init(@NonNull SourceGenerationContext context) {
    }

    /**
     * Registers a static method which would have to be called
     * at initialization time of the optimized entry point.
     *
     * @return a method spec, if any
     */
    @NonNull
    default Optional<MethodSpec> generateStaticInit() {
        return Optional.empty();
    }

    /**
     * Generates source files.
     *
     * @return the list of generated source files, never null.
     */
    @NonNull
    default List<JavaFile> generateSourceFiles() {
        return Collections.emptyList();
    }

    /**
     * Generates resource files in the target directory.
     * @param targetDirectory the directory where to generate resources
     */
    default void generateResourceFiles(@NonNull File targetDirectory) {
    }
}
