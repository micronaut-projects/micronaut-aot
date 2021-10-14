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
package io.micronaut.aot.internal.sourcegen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import io.micronaut.context.env.CachedEnvironment;
import io.micronaut.context.env.ConstantPropertySources;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.core.util.EnvironmentProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Generates a "constant" property source, that is to say a
 * {@link PropertySource} which properties are known at build time
 * (and constant).
 */
public class ConstantPropertySourcesSourceGenerator extends AbstractSourceGenerator {
    private final AbstractStaticServiceLoaderSourceGenerator serviceLoaderGenerator;

    public ConstantPropertySourcesSourceGenerator(SourceGenerationContext context,
                                                  AbstractStaticServiceLoaderSourceGenerator serviceLoaderGenerator) {
        super(context);
        this.serviceLoaderGenerator = serviceLoaderGenerator;
    }

    @Override
    public Optional<MethodSpec> generateStaticInit() {
        List<String> substitutes = serviceLoaderGenerator.findSubstitutesFor("io.micronaut.context.env.PropertySourceLoader")
                .stream()
                .map(javaFile -> javaFile.packageName + "." + javaFile.typeSpec.name)
                .collect(Collectors.toList());
        CodeBlock.Builder initializer = CodeBlock.builder();
        EnvironmentProperties env = EnvironmentProperties.empty();
        CachedEnvironment.getenv().keySet().forEach(env::findPropertyNamesForEnvironmentVariable);

        initializer.addStatement("$T propertySources = new $T()",
                ParameterizedTypeName.get(ClassName.get(List.class), ClassName.get(PropertySource.class)),
                ParameterizedTypeName.get(ClassName.get(ArrayList.class), ClassName.get(PropertySource.class))
        );
        for (String substitute : substitutes) {
            initializer.addStatement("propertySources.add(new $T())", ClassName.bestGuess(substitute));
        }
        initializer.addStatement("$T.set(new $T(propertySources))", StaticOptimizations.class, ConstantPropertySources.class);
        return staticMethodBuilder("preparePropertySources", m ->
                m.addComment("Generates pre-computed Micronaut property sources from known configuration files")
                        .addCode(initializer.build()));
    }
}
