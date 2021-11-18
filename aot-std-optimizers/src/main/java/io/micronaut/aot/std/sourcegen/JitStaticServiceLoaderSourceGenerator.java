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
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.io.service.SoftServiceLoader;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * A specialized version of static service loader generator aimed
 * at execution in JIT mode.
 */
@AOTModule(
        id = JitStaticServiceLoaderSourceGenerator.ID,
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
                )
        },
        enabledOn = Runtime.JIT,
        subgenerators = { YamlPropertySourceGenerator.class }
)
public class JitStaticServiceLoaderSourceGenerator extends AbstractStaticServiceLoaderSourceGenerator {
    public static final String ID = "serviceloading.jit";

    protected final void generateFindAllMethod(Predicate<String> rejectedClasses,
                                               String serviceName,
                                               Class<?> serviceType,
                                               TypeSpec.Builder factory) {
        List<String> initializers = collectServiceImplementations(
                serviceName,
                (clazz, provider) -> clazz.getName()
        );
        Collections.sort(initializers);
        ParameterizedTypeName staticDefinitionType = ParameterizedTypeName.get(SoftServiceLoader.StaticDefinition.class, serviceType);
        ParameterizedTypeName serviceTypeClassType = ParameterizedTypeName.get(Class.class, serviceType);

        CodeBlock.Builder fieldInit = CodeBlock.builder()
                .beginControlFlow("new String[]");
        for (int i = 0; i < initializers.size(); i++) {
            String initializer = initializers.get(i);
            fieldInit.add("$S", initializer);
            if (i < initializers.size() - 1) {
                fieldInit.add(",\n");
            }
        }
        fieldInit.endControlFlow();

        factory.addField(FieldSpec.builder(String[].class, "SERVICE_TYPES")
                .addModifiers(PRIVATE, STATIC, FINAL)
                .initializer(fieldInit.build())
                .build());
        CodeBlock.Builder init = CodeBlock.builder()
                .addStatement("$T cl = $T.class.getClassLoader()", ClassLoader.class, serviceType)
                .addStatement("$T pool = $T.commonPool()", ForkJoinPool.class, ForkJoinPool.class);
        for (String initializer : initializers) {
            init.addStatement("pool.submit(() -> loadClass(cl, $S))", initializer);
        }
        factory.addStaticBlock(init.build());

        factory.addMethod(MethodSpec.methodBuilder("loadClass")
                .addModifiers(PRIVATE, STATIC)
                .returns(serviceTypeClassType)
                .addParameter(ClassLoader.class, "cl")
                .addParameter(String.class, "name")
                .beginControlFlow("try")
                .addStatement("return ($T) cl.loadClass(name)", serviceTypeClassType)
                .endControlFlow()
                .beginControlFlow("catch (Exception e)")
                .addStatement("return null")
                .endControlFlow()
                .build());

        MethodSpec.Builder method = MethodSpec.methodBuilder("findAll")
                .addModifiers(PUBLIC)
                .addParameter(ParameterizedTypeName.get(Predicate.class, String.class), "predicate")
                .returns(ParameterizedTypeName.get(ClassName.get(Stream.class), staticDefinitionType));
        method.addStatement("$T cl = $T.class.getClassLoader()", ClassLoader.class, serviceType);
        method.addStatement("return $T.stream(SERVICE_TYPES)\n" +
                        ".parallel()\n" +
                        ".filter(predicate::test)\n" +
                        ".map(s -> loadClass(cl, s))\n" +
                        ".filter($T::nonNull)\n" +
                        ".map(c -> $T.of(c.getName(), c))",
                Arrays.class, Objects.class, SoftServiceLoader.StaticDefinition.class);
        factory.addMethod(method.build());
    }

}
