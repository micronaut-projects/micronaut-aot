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
package io.micronaut.aot.core.config;

import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.AOTCodeGenerator;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;

import java.util.Arrays;
import java.util.Optional;

/**
 * Runtime reflection utils for analyzing AOT modules.
 */
public class MetadataUtils {
    /**
     * Returns the AOT module annotation for a class, if present.
     * @param clazz the class to look for
     * @return the module annotation.
     */
    public static Optional<AOTModule> findMetadata(Class<?> clazz) {
        return Optional.ofNullable(clazz.getAnnotation(AOTModule.class));
    }

    /**
     * Returns the option with the corresponding name. If
     * the supplied class is not annotated with {@link AOTModule}
     * invocation will fail.
     * @param clazz the AOT module class
     * @param name the name of the option
     * @return the corresponding option
     */
    public static Option findOption(Class<?> clazz, String name) {
        return findMetadata(clazz).flatMap(aotModule -> Arrays.stream(aotModule.options()).filter(option -> option.key().equals(name)).findFirst()).orElseThrow(() -> new IllegalArgumentException("No option found with name " + name + " on " + clazz));
    }

    /**
     * Returns a string representation of the option, for
     * use in properties files.
     * @param option the option to convert to sample text.
     * @return a sample
     */
    public static String toPropertiesSample(Option option) {
        StringBuilder sb = new StringBuilder();
        String description = option.description();
        if (!description.isEmpty()) {
            sb.append("# ").append(description).append("\n");
        }
        String key = option.key();
        sb.append(key).append(" = ");
        String sampleValue = option.sampleValue();
        sb.append(sampleValue);
        return sb.toString();
    }

    public static boolean isEnabledOn(Runtime runtime, AOTCodeGenerator module) {
        return findMetadata(module.getClass())
                .map(aotModule -> isEnabledOn(runtime, aotModule))
                .orElse(false);
    }

    public static boolean isEnabledOn(Runtime runtime, AOTModule module) {
        return Arrays.asList(module.enabledOn()).contains(runtime);
    }
}
