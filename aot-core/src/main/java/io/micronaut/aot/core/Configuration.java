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
import io.micronaut.core.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read-only configuration for source generators.
 */
public interface Configuration {
    /**
     * Returns true if the configuration contains an entry
     * for the specified key.
     * @param key the key to look for
     * @return true if the configuration contains an entry for the key
     */
    boolean containsKey(String key);

    /**
     * Returns the value of the configuration for the requested
     * key or fails if not available.
     *
     * @param key the key to look for
     * @return the value for the requested key
     */
    @NonNull
    String mandatoryValue(String key);

    /**
     * Returns the value for the requested key.
     *
     * @param key the configuration key
     * @param producer a function called to generate a transformed value
     * @param <T> the type of the return value
     * @return a configured value
     */
    @Nullable
    <T> T optionalValue(String key, Function<Optional<String>, T> producer);

    /**
     * Returns the string for the specified configuration key, or the
     * default value when missing.
     *
     * @param key the key
     * @param defaultValue the default value
     * @return a configured string
     */
    @Nullable
    default String optionalString(@NonNull String key, @Nullable String defaultValue) {
        return optionalValue(key, opt -> opt.orElse(defaultValue));
    }

    /**
     * Returns a list of strings from a configuration entry.
     * The value is assumed to be of type String and will be splitted
     * using the "," and ";" separators
     *
     * @param key the configuration key
     * @return a list of strings, or an empty list when missing
     */
    @NonNull
    default List<String> stringList(@NonNull String key) {
        return stringList(key, "[,;]\\s*");
    }

    /**
     * Returns a list of strings from a configuration entry.
     * The value is assumed to be of type String and will be split.
     * using the "," and ":" separators
     *
     * @param key the key to look for
     * @param separator a separator regular expression
     * @return the list of values, or an empty list
     */
    @SuppressWarnings("ConstantConditions")
    @NonNull
    default List<String> stringList(@NonNull String key, @NonNull String separator) {
        return optionalValue(key, opt -> opt.map(string ->
                Arrays.stream(string.split(separator))
                        .filter(Objects::nonNull)
                        .filter(s -> !s.trim().isEmpty())
                        .collect(Collectors.toList())
        ).orElse(Collections.emptyList()));
    }

    /**
     * Returns the boolean value for the required key. If the value isn't
     * found, returns the default value
     *
     * @param key the key to look for
     * @param defaultValue the default value when missing
     * @return the boolean value
     */
    default boolean booleanValue(@NonNull String key, boolean defaultValue) {
        return optionalValue(key, s -> s.map(Boolean::parseBoolean).orElse(defaultValue));
    }

    /**
     * Returns true if a particular optimizer is enabled, independently
     * of the runtime. All features need to be explicitly enabled by
     * configuration.
     *
     * @param featureId the feature id
     * @return true if the feature is enabled
     */
    default boolean isFeatureEnabled(@NonNull String featureId) {
        return booleanValue(featureId + ".enabled", false);
    }

    /**
     * Returns the target runtime for optimizations.
     * @return the target runtime
     */
    @NonNull
    Runtime getRuntime();
}
