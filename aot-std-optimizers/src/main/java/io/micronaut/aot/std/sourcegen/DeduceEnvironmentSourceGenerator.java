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

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.BeanContextConfiguration;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * This code generator is responsible for taking the result
 * of the environment deduction, which is the set of active
 * environment names and the package names, and inject is
 * via a custom application context configurer. The resulting
 * class will effectively disable environment deduction, so
 * it will be done at build time instead of run time.
 */
@AOTModule(
        id = DeduceEnvironmentSourceGenerator.ID,
        description = DeduceEnvironmentSourceGenerator.DESCRIPTION
)
public class DeduceEnvironmentSourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "deduce.environment";
    public static final String DESCRIPTION = "Deduces the environment at build time instead of runtime";
    public static final String DEDUCED_ENVIRONMENT_CONFIGURER = "DeducedEnvironmentConfigurer";

    @Override
    public void generate(AOTContext context) {
        ApplicationContextAnalyzer analyzer = context.getAnalyzer();
        Set<String> environmentNames = analyzer.getEnvironmentNames();
        BeanContextConfiguration contextConfiguration = analyzer.getApplicationContext().getContextConfiguration();
        if (contextConfiguration instanceof ApplicationContextConfiguration) {
            ((ApplicationContextConfiguration) contextConfiguration).getDeduceEnvironments().ifPresent(deduceEnvironments -> {
                if (deduceEnvironments) {
                    Collection<String> packages = analyzer.getApplicationContext().getEnvironment().getPackages();
                    context.registerGeneratedSourceFile(
                            context.javaFile(buildApplicationContextConfigurer(environmentNames, packages))
                    );
                    writeServiceFile(context, ApplicationContextConfigurer.class, DEDUCED_ENVIRONMENT_CONFIGURER);
                }
            });
        }
    }

    private TypeSpec buildApplicationContextConfigurer(Set<String> environmentNames, Collection<String> packages) {
        MethodSpec.Builder bodyBuilder = MethodSpec.methodBuilder("configure")
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(ApplicationContextBuilder.class, "builder");
        bodyBuilder.addStatement("builder.deduceEnvironment(false)");
        if (!environmentNames.isEmpty()) {
            bodyBuilder.addStatement("builder.defaultEnvironments($L)", toQuotedStringList(environmentNames));
        }
        if (!packages.isEmpty()) {
            bodyBuilder.addStatement("builder.packages($L)", toQuotedStringList(packages));
        }
        return TypeSpec.classBuilder(DEDUCED_ENVIRONMENT_CONFIGURER)
                .addSuperinterface(ApplicationContextConfigurer.class)
                .addModifiers(PUBLIC)
                .addMethod(bodyBuilder.build())
                .addMethod(MethodSpec.methodBuilder("getOrder")
                        .addModifiers(PUBLIC)
                        .addAnnotation(Override.class)
                        .returns(int.class)
                        .addStatement("return LOWEST_PRECEDENCE")
                        .build()
                )
                .build();
    }

    private static String toQuotedStringList(Collection<String> elements) {
        return elements.stream().map(e -> '"' + e + '"').collect(Collectors.joining(", "));
    }
}
