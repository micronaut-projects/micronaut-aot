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
package io.micronaut.aot.std.sourcegen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.core.util.EnvironmentProperties;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static javax.lang.model.element.Modifier.PRIVATE;

/**
 * A code generator which is responsible for precomputing the Micronaut property
 * names from environment variable names at build time.
 */
@AOTModule(
    id = EnvironmentPropertiesSourceGenerator.ID,
    description = EnvironmentPropertiesSourceGenerator.DESCRIPTION
)
public class EnvironmentPropertiesSourceGenerator extends AbstractCodeGenerator {

    public static final String ID = "precompute.environment.properties";
    public static final String DESCRIPTION = "Precomputes Micronaut configuration property keys from the current environment variables";

    private static final int MAX_METHOD_SIZE = 30_000;

    private final Map<String, String> env;

    public EnvironmentPropertiesSourceGenerator(Map<String, String> env) {
        this.env = env;
    }

    public EnvironmentPropertiesSourceGenerator() {
        this(CachedEnvironment.getenv());
    }

    @Override
    public void generate(@NonNull AOTContext context) {

        String className = "EnvironmentPropertiesOptimizationLoader";

        EnvironmentProperties props = EnvironmentProperties.empty();
        env.keySet().forEach(props::findPropertyNamesForEnvironmentVariable);

        MethodSpec.Builder mainLoadMethodBuilder = MethodSpec.methodBuilder("load")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(EnvironmentProperties.class)
            .addStatement("$T env = new $T()",
                ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ParameterizedTypeName.get(List.class, String.class)),
                ParameterizedTypeName.get(ClassName.get(HashMap.class), ClassName.get(String.class), ParameterizedTypeName.get(List.class, String.class)));

        TypeSpec.Builder generatedTypeBuilder = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(StaticOptimizations.Loader.class, EnvironmentProperties.class));

        int fixedLineSize = 37;
        int methodSize = 0;
        int index = 0;

        MethodSpec.Builder loadMethodBuilder = createMethodBuilder(index);

        for (Map.Entry<String, List<String>> entry : props.asMap().entrySet()) {
            String values = entry.getValue().stream().map(e -> "\"" + e + "\"").collect(Collectors.joining(", "));
            methodSize += fixedLineSize + entry.getKey().length() + values.length() + 2;
            if (methodSize > MAX_METHOD_SIZE) {
                generatedTypeBuilder.addMethod(loadMethodBuilder.build());
                mainLoadMethodBuilder.addCode("load" + index + "(env);\n");
                methodSize = 0;
                index++;
                loadMethodBuilder = createMethodBuilder(index);
            }
            loadMethodBuilder.addStatement("env.put($S, $T.asList($L))", entry.getKey(), Arrays.class, values);
        }

        generatedTypeBuilder.addMethod(loadMethodBuilder.build());
        mainLoadMethodBuilder.addCode("load" + index + "(env);\n")
            .addStatement("return $T.of(env)", EnvironmentProperties.class);
        generatedTypeBuilder.addMethod(mainLoadMethodBuilder.build());

        context.registerGeneratedSourceFile(context.javaFile(generatedTypeBuilder.build()));
        context.registerServiceImplementation(StaticOptimizations.Loader.class, className);
    }

    /**
     * Creates default inner loader method.
     *
     * @param index number on loader method
     * @return method builder
     */
    MethodSpec.Builder createMethodBuilder(int index) {
        return MethodSpec.methodBuilder("load" + index)
            .addParameter(ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), ParameterizedTypeName.get(List.class, String.class)),
                "env")
            .addModifiers(PRIVATE);
    }
}
