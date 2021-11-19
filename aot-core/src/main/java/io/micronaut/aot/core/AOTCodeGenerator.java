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

import io.micronaut.core.annotation.NonNull;

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
 *
 * Code generators must be annotated with {@link AOTModule}.
 */
public interface AOTCodeGenerator {
    void generate(@NonNull AOTContext context);
}
