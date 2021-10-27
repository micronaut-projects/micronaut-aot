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

import java.util.Optional;

/**
 * Describes a configuration option of a source generator.
 */
public final class Option {
    private final String key;
    private final String description;
    private final String sampleValue;

    private Option(@NonNull String key,
                  @Nullable String description,
                  @Nullable String sampleValue) {
        this.key = key;
        this.description = description;
        this.sampleValue = sampleValue;
    }

    /**
     * Returns the key used to configure this option.
     * @return the key, as found in properties
     */
    @NonNull
    public String getKey() {
        return key;
    }

    /**
     * Returns a description of this configuration option.
     * @return the description
     */
    @NonNull
    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns a sample value for this option, if any.
     * @return a sample value
     */
    @NonNull
    public Optional<String> getSampleValue() {
        return Optional.ofNullable(sampleValue);
    }

    /**
     * Returns a string representation of the option, for
     * use in properties files.
     * @return a sample
     */
    public String toPropertiesSample() {
        StringBuilder sb = new StringBuilder();
        if (description != null) {
            sb.append("# ").append(description).append("\n");
        }
        sb.append(key).append(" = ");
        if (sampleValue != null) {
            sb.append(sampleValue);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Option option = (Option) o;

        return key.equals(option.key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    /**
     * A simple option without description nor sample.
     * @param key the key
     * @return an option
     */
    public static Option of(@NonNull String key) {
        return new Option(key, null, null);
    }

    /**
     * A simple option without sample.
     * @param key the key
     * @param description the description
     * @return an option
     */
    public static Option of(@NonNull String key, @NonNull String description) {
        return new Option(key, description, null);
    }

    /**
     * Builds an option descriptor.
     * @param key the key
     * @param description the description
     * @param sampleValue a sample value for this option
     * @return an option
     */
    public static Option of(@NonNull String key, @NonNull String description, @NonNull String sampleValue) {
        return new Option(key, description, sampleValue);
    }
}
