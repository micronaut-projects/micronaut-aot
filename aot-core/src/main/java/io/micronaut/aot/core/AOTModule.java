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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation which must be present on AOT optimizers.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AOTModule {
    /**
     * A unique identifier for this source generator.
     *
     * @return the id
     */
    String id();

    /**
     * Returns a description for this source generator.
     * Description is optional because some code generators
     * are purely internal and not exposed to users.
     *
     * @return a description or an empty options
     */
    String description() default "";

    /**
     * Returns the identifiers of source generators which must
     * be executed before this generator is called.
     *
     * @return the list of ids
     */
    String[] dependencies() default {};

    /**
     * Returns a list of generators which are directly managed (or instantiated_
     * by this source generator. Such optimizers are typically not registered as
     * services because they make no sense in isolation.
     * This method should be used for introspection only.
     *
     * @return the list of sub features
     */
    Class<? extends AOTCodeGenerator>[] subgenerators() default {};

    /**
     * Returns the set of configuration keys which affect
     * the configuration of this source generator.
     *
     * @return a set of configuration keys
     */
    Option[] options() default {};

    /**
     * Returns the runtimes this module is valid for.
     *
     * @return the list of runtimes this module applies to.
     */
    Runtime[] enabledOn() default {Runtime.JIT, Runtime.NATIVE};

}
