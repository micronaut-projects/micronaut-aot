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
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceGeneratorLoader.class);

    private static final Comparator<AOTModule> EXECUTION_ORDER = (first, second) -> {
        if (Arrays.asList(first.dependencies()).contains(second.id())) {
            return 1;
        }
        if (Arrays.asList(second.dependencies()).contains(first.id())) {
            return -1;
        }
        return first.id().compareTo(second.id());
    };

    @NonNull
    public static List<AOTCodeGenerator> load(Runtime runtime, AOTContext context) {
        Configuration configuration = context.getConfiguration();
        return sourceGeneratorStream()
                .map(sg -> new Object() {
                    final AOTCodeGenerator generator = sg;
                    final AOTModule module = MetadataUtils.findMetadata(sg.getClass()).orElse(null);
                })
                .filter(sg -> {
                    if (sg.module != null) {
                        boolean isEnabledOnRuntime = MetadataUtils.isEnabledOn(runtime, sg.module);
                        if (!isEnabledOnRuntime) {
                            LOGGER.debug("Skipping source generator {} as it is not enabled on runtime {}", sg.generator.getClass().getName(), runtime);
                            return false;
                        }
                        boolean isEnabledByConfiguration = configuration.isFeatureEnabled(sg.module.id());
                        if (!isEnabledByConfiguration) {
                            LOGGER.debug("Skipping source generator {} as it is not enabled by configuration", sg.generator.getClass().getName());
                            return false;
                        }
                        LOGGER.debug("Loading source generator {}", sg.generator.getClass().getName());
                        return true;
                    }
                    return false;
                })
                .sorted(Comparator.comparing(f -> f.module, EXECUTION_ORDER))
                .map(sg -> sg.generator)
                .collect(Collectors.toList());
    }

    @NonNull
    public static List<AOTModule> list(Runtime runtime) {
        return sourceGeneratorStream()
                .map(Object::getClass)
                .map(MetadataUtils::findMetadata)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(aotModule -> MetadataUtils.isEnabledOn(runtime, aotModule))
                .sorted(EXECUTION_ORDER)
                .collect(Collectors.toList());
    }

    private static Stream<AOTCodeGenerator> sourceGeneratorStream() {
        return stream(ServiceLoader.load(AOTCodeGenerator.class).spliterator(), false);
    }

}
