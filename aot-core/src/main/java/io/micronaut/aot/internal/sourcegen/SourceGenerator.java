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
package io.micronaut.aot.internal.sourcegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
public interface SourceGenerator {
    default void init() {
    }

    /**
     * Registers a static method which would have to be called
     * at initialization time of the optimized entry point.
     *
     * @return a method spec, if any
     */
    default Optional<MethodSpec> generateStaticInit() {
        return Optional.empty();
    }

    /**
     * Generates source files.
     *
     * @return the list of generated source files, never null.
     */
    default List<JavaFile> generateSourceFiles() {
        return Collections.emptyList();
    }

    /**
     * Generates resource files in the target directory.
     * @param targetDirectory the directory where to generate resources
     */
    default void generateResourceFiles(File targetDirectory) {
    }
}
