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
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Environments;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.io.service.SoftServiceLoader;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A specialized version of service loader generation which is aimed at
 * executing in native images, where classloading is basically free.
 */
@AOTModule(
        id = NativeStaticServiceLoaderSourceGenerator.ID,
        description = AbstractStaticServiceLoaderSourceGenerator.DESCRIPTION,
        options = {
                @Option(
                        key = "service.types",
                        description = "The list of service types to be scanned (comma separated)",
                        sampleValue = "io.micronaut.Service1,io.micronaut.Service2"
                ),
                @Option(
                        key = "serviceloading.rejected.impls",
                        description = "A list of implementation types which shouldn't be included in the final application (comma separated)",
                        sampleValue = "com.Misc,org.Bar"
                ),
                @Option(
                        key = "serviceloading.force.include.impls",
                        description = "A list of implementation types to include even if they don't match bean requirements (comma separated)",
                        sampleValue = "com.Misc,org.Bar"
                ),
                @Option(
                        key = Environments.POSSIBLE_ENVIRONMENTS_NAMES,
                        description = Environments.POSSIBLE_ENVIRONMENTS_DESCRIPTION,
                        sampleValue = Environments.POSSIBLE_ENVIRONMENTS_SAMPLE
                )
        },
        enabledOn = Runtime.NATIVE,
        subgenerators = {YamlPropertySourceGenerator.class}
)
public class NativeStaticServiceLoaderSourceGenerator extends AbstractStaticServiceLoaderSourceGenerator {
    public static final String ID = "serviceloading.native";

    protected final void generateFindAllMethod(Stream<Class<?>> serviceClasses,
                                               String serviceName,
                                               Class<?> serviceType,
                                               TypeSpec.Builder factory) {
        class Service {
            final String name;
            final CodeBlock codeBlock;

            Service(String name, CodeBlock codeBlock) {
                this.name = name;
                this.codeBlock = codeBlock;
            }
        }
        List<Service> initializers = serviceClasses.map(clazz -> {
                    for (Method method : clazz.getDeclaredMethods()) {
                        if ("provider".equals(method.getName()) && Modifier.isStatic(method.getModifiers())) {
                            return new Service(clazz.getName(), CodeBlock.of("$T::provider", clazz));
                        }
                    }
                    for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                        if (constructor.getParameterCount() == 0 && Modifier.isPublic(constructor.getModifiers())) {
                            return new Service(clazz.getName(), CodeBlock.of("$T::new", clazz));
                        }
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(s -> s.name))
                .collect(Collectors.toList());
        ParameterizedTypeName staticDefinitionType = ParameterizedTypeName.get(SoftServiceLoader.StaticDefinition.class, serviceType);

        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addModifiers(PUBLIC)
                .addParameter(ParameterizedTypeName.get(Predicate.class, String.class), "predicate")
                .returns(ParameterizedTypeName.get(ClassName.get(Stream.class), staticDefinitionType));
        if (initializers.size() == 0) {
            method.addStatement("return $T.empty()", Stream.class);
        } else {
            method.addStatement("$T list = new $T<>()", ParameterizedTypeName.get(ClassName.get(List.class), staticDefinitionType), ArrayList.class);
            for (Service initializer : initializers) {
                method.beginControlFlow("if (predicate.test($S))", initializer.name);
                method.addStatement("list.add($T.of($S, $L))", SoftServiceLoader.StaticDefinition.class, initializer.name, initializer.codeBlock);
                method.endControlFlow();
            }
            method.addStatement("return list.stream()");
        }
        factory.addMethod(method.build());
    }

}
