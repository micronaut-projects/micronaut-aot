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

import io.micronaut.aot.core.AOTCodeGenerator;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

/**
 * Describes how a discovered source generator was selected for an AOT run.
 */
@Internal
public final class SourceGeneratorSelection {
    public static final String NOT_ENABLED_ON_RUNTIME = "not_enabled_on_runtime";
    public static final String DISABLED_BY_CONFIGURATION = "disabled_by_configuration";
    public static final String MISSING_METADATA = "missing_metadata";

    private final AOTCodeGenerator generator;
    @Nullable
    private final AOTModule module;
    private final boolean enabled;
    @Nullable
    private final String disabledReason;

    public SourceGeneratorSelection(AOTCodeGenerator generator,
                                    @Nullable AOTModule module,
                                    boolean enabled,
                                    @Nullable String disabledReason) {
        this.generator = generator;
        this.module = module;
        this.enabled = enabled;
        this.disabledReason = disabledReason;
    }

    public AOTCodeGenerator getGenerator() {
        return generator;
    }

    @Nullable
    public AOTModule getModule() {
        return module;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public String getDisabledReason() {
        return disabledReason;
    }
}
