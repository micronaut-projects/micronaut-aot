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

import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.annotation.NonNull;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Function;

/**
 * An implementation of configuration which uses properties
 * as the backing store.
 */
public class DefaultConfiguration implements Configuration {
    private final Properties config;

    public DefaultConfiguration(Properties backingProperties) {
        this.config = backingProperties;
    }

    @NonNull
    @Override
    public String mandatoryValue(String key) {
        String value = config.getProperty(key);
        if (value == null || value.isEmpty()) {
            invalidConfiguration(key, "should not be null or empty");
        }
        return value;
    }

    @Override
    public <T> T optionalValue(String key, Function<Optional<String>, T> producer) {
        String value = config.getProperty(key);
        if (value == null) {
            Object raw = config.get(key);
            if (raw != null) {
                value = String.valueOf(raw);
            }
        }
        return producer.apply(Optional.ofNullable(value));
    }

    @NonNull
    @Override
    public Runtime getRuntime() {
        return optionalValue("runtime", v -> v.map(r -> Runtime.valueOf(r.toUpperCase(Locale.ENGLISH))).orElse(Runtime.JIT));
    }

    private static void invalidConfiguration(String key, String message) {
        throw new IllegalStateException("Parameter '" + "'" + key + " " + message);
    }

}
