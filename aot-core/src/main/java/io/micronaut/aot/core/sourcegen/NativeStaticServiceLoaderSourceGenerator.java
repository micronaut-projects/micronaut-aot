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
import com.squareup.javapoet.TypeSpec;
import io.micronaut.core.io.service.SoftServiceLoader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A specialized version of service loader generation which is aimed at
 * executing in native images, where classloading is basically free.
 */
public class NativeStaticServiceLoaderSourceGenerator extends AbstractStaticServiceLoaderSourceGenerator {
    public NativeStaticServiceLoaderSourceGenerator(SourceGenerationContext context,
                                                    Predicate<Object> applicationContextAnalyzer,
                                                    List<String> serviceNames,
                                                    Predicate<String> rejectedClasses,
                                                    Map<String, AbstractSingleClassFileGenerator> substitutions) {
        super(context, applicationContextAnalyzer, serviceNames, rejectedClasses, substitutions);
    }

    protected final void generateFindAllMethod(Predicate<String> rejectedClasses,
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
        List<Service> initializers = collectServiceImplementations(
                serviceName,
                (clazz, provider) -> new Service(clazz.getName(), provider ? CodeBlock.of("$T::provider", clazz) : CodeBlock.of("$T::new", clazz))
        );
        initializers.sort(Comparator.comparing(s -> s.name));
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
