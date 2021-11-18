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

/**
 * Describes a configuration option of a source generator.
 */
public @interface Option {
    /**
     * Returns the key used to configure this option.
     * @return the key, as found in properties
     */
    String key();

    /**
     * Returns a description of this configuration option.
     * @return the description
     */
    String description() default "";

    /**
     * Returns a sample value for this option, if any.
     * @return a sample value
     */
    String sampleValue() default "";

}
