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

import io.micronaut.aot.core.AOTSourceGenerator;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.SourceGenerationContext;
import io.micronaut.core.annotation.NonNull;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.StreamSupport.stream;

/**
 * This class is responsible for loading the source generators
 * for a particular target runtime, and sort them in execution
 * order according to their dependencies.
 */
public class SourceGeneratorLoader {
    private static final Comparator<AOTSourceGenerator> EXECUTION_ORDER = (first, second) -> {
        if (first.getDependencies().contains(second.getId())) {
            return 1;
        }
        if (second.getDependencies().contains(first.getId())) {
            return -1;
        }
        return first.getId().compareTo(second.getId());
    };

    @NonNull
    public static List<AOTSourceGenerator> load(Runtime runtime, SourceGenerationContext context) {
        Configuration configuration = context.getConfiguration();
        return sourceGeneratorStream()
                .filter(sg -> sg.isEnabledOn(runtime))
                .filter(sg -> configuration.booleanValue(sg.getId() + ".enabled", true))
                .sorted(EXECUTION_ORDER)
                .peek(sg -> sg.init(context))
                .collect(Collectors.toList());
    }

    @NonNull
    public static List<AOTSourceGenerator> list(Runtime runtime) {
        return sourceGeneratorStream()
                .filter(sg -> sg.isEnabledOn(runtime))
                .sorted(EXECUTION_ORDER)
                .collect(Collectors.toList());
    }

    private static Stream<AOTSourceGenerator> sourceGeneratorStream() {
        return stream(ServiceLoader.load(AOTSourceGenerator.class).spliterator(), false);
    }

}
