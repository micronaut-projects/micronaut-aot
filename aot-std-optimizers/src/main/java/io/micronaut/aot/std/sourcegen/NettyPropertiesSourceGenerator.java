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

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.core.annotation.NonNull;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * A code generator which is responsible for setting up Netty properties.
 */
@AOTModule(
        id = NettyPropertiesSourceGenerator.ID,
        description = NettyPropertiesSourceGenerator.DESCRIPTION,
        options = {
                @Option(
                        key = NettyPropertiesSourceGenerator.MACHINE_ID,
                        description = NettyPropertiesSourceGenerator.MACHINE_ID_DESCRIPTION,
                        sampleValue = "random"
                ),
                @Option(
                        key = NettyPropertiesSourceGenerator.PROCESS_ID,
                        description = NettyPropertiesSourceGenerator.PROCESS_ID_DESCRIPTION,
                        sampleValue = "random"
                )
        }
)
public class NettyPropertiesSourceGenerator extends AbstractCodeGenerator {
    public static final String GENERATED_CLASS = "NettyPropertiesAOTContextConfigurer";

    public static final String ID = "netty.properties";
    public static final String DESCRIPTION = "Defines some Netty system properties when starting the application which optimize startup times.";

    public static final String MACHINE_ID = "netty.machine.id";
    public static final String MACHINE_ID_DESCRIPTION = "The machine id used by Netty. By default, generates a random value at runtime. Set it to a fixed MAC address to override, or use the value 'netty' to disable the optimization and get it at runtime.";

    public static final String PROCESS_ID = "netty.process.id";
    public static final String PROCESS_ID_DESCRIPTION = "The process id to use for Netty. Defaults to a random PID at runtime. Set it to a fixed value (not recommended) or use the value 'netty' to disable the optimization and get it at runtime.";

    private static final String RANDOM_VALUE = "random";
    private static final String DEFAULT_NETTY_BEHAVIOR = "netty";

    @Override
    public void generate(@NonNull AOTContext context) {
        context.registerGeneratedSourceFile(context.javaFile(buildConfigurer(context)));
        context.registerServiceImplementation(ApplicationContextConfigurer.class, GENERATED_CLASS);
    }

    private TypeSpec buildConfigurer(AOTContext context) {
        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(GENERATED_CLASS)
                .addSuperinterface(ApplicationContextConfigurer.class)
                .addModifiers(PUBLIC);
        MethodSpec.Builder configure = MethodSpec.methodBuilder("configure")
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(ApplicationContextBuilder.class, "builder");
        ifOptimizationEnabled(machineIdOptionOf(context), machineId ->
                defineSystemProperty(classBuilder, configure, "io.netty.machineId", machineId, () -> MethodSpec.methodBuilder("randomMacAddress")
                        .addModifiers(PRIVATE, STATIC)
                        .returns(String.class)
                        .addCode(CodeBlock.builder()
                                .addStatement("$T rnd = new $T()", Random.class, Random.class)
                                .addStatement("$T sb = new $T()", StringBuilder.class, StringBuilder.class)
                                .beginControlFlow("for (int i = 0; i < 6; i++)")
                                .addStatement("sb.append(String.format(\"%02x\", rnd.nextInt(256)))")
                                .beginControlFlow("if (i < 5)")
                                .addStatement("sb.append(\":\")")
                                .endControlFlow()
                                .endControlFlow()
                                .addStatement("return sb.toString()")
                                .build()
                        ).build())
        );

        ifOptimizationEnabled(pidOf(context), pid ->
                defineSystemProperty(classBuilder, configure, "io.netty.processId", pid, () -> MethodSpec.methodBuilder("randomPid")
                        .addModifiers(PRIVATE, STATIC)
                        .returns(String.class)
                        .addStatement("return String.valueOf(new Random().nextInt(65536))")
                        .build())
        );
        return classBuilder.addMethod(configure.build())
                .build();
    }

    private static void ifOptimizationEnabled(String option, Consumer<? super String> consumer) {
        if (!DEFAULT_NETTY_BEHAVIOR.equals(option)) {
            consumer.accept(option);
        }
    }

    private static void defineSystemProperty(TypeSpec.Builder clazz, MethodSpec.Builder configure, String name, String value, Supplier<MethodSpec> randomizer) {
        if (RANDOM_VALUE.equals(value)) {
            MethodSpec methodSpec = randomizer.get();
            clazz.addMethod(methodSpec);
            configure.addStatement("System.setProperty($S, $L)", name, methodSpec.name + "()");
        } else {
            configure.addStatement("System.setProperty($S, $S)", name, value);
        }
    }

    private static String pidOf(AOTContext context) {
        return context.getConfiguration().optionalString(PROCESS_ID, RANDOM_VALUE);
    }

    private static String machineIdOptionOf(AOTContext context) {
        return context.getConfiguration().optionalString(MACHINE_ID, RANDOM_VALUE);
    }

}
