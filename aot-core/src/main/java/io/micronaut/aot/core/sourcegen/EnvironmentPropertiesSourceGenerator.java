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
package io.micronaut.aot.core.sourcegen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.core.util.EnvironmentProperties;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A code generator which is responsible for precomputing the Micronaut property
 * names from environment variable names at build time.
 */
public class EnvironmentPropertiesSourceGenerator extends AbstractSourceGenerator {
    public static final String ID = "precompute.environment.properties";
    public static final String DESCRIPTION = "Precomputes Micronaut configuration property keys from the current environment variables";

    private final Map<String, String> env;

    public EnvironmentPropertiesSourceGenerator(Map<String, String> env) {
        this.env = env;
    }

    public EnvironmentPropertiesSourceGenerator() {
        this(CachedEnvironment.getenv());
    }

    @Override
    @NonNull
    public String getId() {
        return ID;
    }

    @Override
    @NonNull
    public Optional<String> getDescription() {
        return Optional.of(DESCRIPTION);
    }

    @Override
    @NonNull
    public Optional<MethodSpec> generateStaticInit() {
        CodeBlock.Builder initializer = CodeBlock.builder();
        EnvironmentProperties props = EnvironmentProperties.empty();
        env.keySet().forEach(props::findPropertyNamesForEnvironmentVariable);

        initializer.addStatement("$T env = new $T()",
                ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ParameterizedTypeName.get(List.class, String.class)),
                ParameterizedTypeName.get(ClassName.get(HashMap.class), ClassName.get(String.class), ParameterizedTypeName.get(List.class, String.class)));
        for (Map.Entry<String, List<String>> entry : props.asMap().entrySet()) {
            String values = entry.getValue().stream().map(e -> "\"" + e + "\"").collect(Collectors.joining(", "));
            initializer.addStatement("env.put($S, $T.asList($L))", entry.getKey(), Arrays.class, values);
        }
        initializer.addStatement("$T.set($T.of(env))", StaticOptimizations.class, EnvironmentProperties.class);
        return staticMethodBuilder("prepareEnvironment", m ->
                m.addComment("Generates pre-computed Micronaut property names from environment variables")
                        .addCode(initializer.build()));
    }
}
