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
package io.micronaut.aot.core.codegen;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTCodeGenerator;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * The "optimized" entry point generator is the main source generator:
 * it is responsible for generating a new entry point, which delegates
 * to the original entry point of the application, and injects a number
 * of optimizations before starting the application.
 * <p>
 * The list of optimizations to inject is determined by the list of
 * delegate optimizers which are passed to this generator.
 */
public class ApplicationContextConfigurerGenerator extends AbstractCodeGenerator {
    public static final String ID = "application.context.customizer";

    public static final String CUSTOMIZER_CLASS_NAME = "AOTApplicationContextConfigurer";

    private final List<AOTCodeGenerator> sourceGenerators;

    public ApplicationContextConfigurerGenerator(List<AOTCodeGenerator> sourceGenerators) {
        this.sourceGenerators = sourceGenerators;
    }

    @Override
    public void generate(@NonNull AOTContext context) {
        TypeSpec.Builder optimizedEntryPoint = TypeSpec.classBuilder(CUSTOMIZER_CLASS_NAME)
            .addSuperinterface(ApplicationContextConfigurer.class)
            .addModifiers(PUBLIC);
        CodeBlock.Builder staticInitializer = CodeBlock.builder();
        var capturer = new StaticInitializerCapturingContext(context, optimizedEntryPoint, staticInitializer);
        for (AOTCodeGenerator sourceGenerator : sourceGenerators) {
            sourceGenerator.generate(capturer);
        }
        addDiagnostics(context, optimizedEntryPoint);
        optimizedEntryPoint.addStaticBlock(staticInitializer.build());
        context.registerGeneratedSourceFile(context.javaFile(optimizedEntryPoint.build()));
        context.registerServiceImplementation(ApplicationContextConfigurer.class, CUSTOMIZER_CLASS_NAME);
        context.finish();
    }

    private void addDiagnostics(AOTContext context, TypeSpec.Builder optimizedEntryPoint) {
        MethodSpec.Builder configure = MethodSpec.methodBuilder("configure")
            .addModifiers(PUBLIC)
            .addAnnotation(Override.class)
            .addParameter(ApplicationContextBuilder.class, "builder");
        var mapGenerator = new MapGenerator();
        CodeBlock mapBlock = mapGenerator.generateMap(optimizedEntryPoint, createDiagnosticsMap(context));
        configure.addStatement("builder.properties($L)", mapBlock);
        optimizedEntryPoint.addMethod(configure.build());
    }

    private Map<String, Object> createDiagnosticsMap(AOTContext context) {
        var values = CollectionUtils.<String, Object>newLinkedHashMap(3);
        values.put("micronaut.aot.enabled", true);
        values.put("micronaut.aot.runtime", context.getRuntime().toString());
        values.put("micronaut.aot.optimizations", buildOptimizationList());
        return values;
    }

    private List<String> buildOptimizationList() {
        return sourceGenerators.stream()
            .map(Object::getClass)
            .map(MetadataUtils::findMetadata)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(AOTModule::id)
            .collect(Collectors.toList());
    }

    private static final class StaticInitializerCapturingContext extends DelegatingSourceGenerationContext {
        private final AOTContext delegate;
        private final TypeSpec.Builder optimizedEntryPoint;
        private final CodeBlock.Builder initializer;

        private StaticInitializerCapturingContext(AOTContext delegate, TypeSpec.Builder optimizedEntryPoint, CodeBlock.Builder initializer) {
            super(delegate);
            this.delegate = delegate;
            this.optimizedEntryPoint = optimizedEntryPoint;
            this.initializer = initializer;
        }

        @Override
        public void registerStaticInitializer(MethodSpec staticInitializer) {
            delegate.registerStaticInitializer(staticInitializer);
            appendInitializer(optimizedEntryPoint, initializer, staticInitializer);
        }

        private static void appendInitializer(TypeSpec.Builder optimizedEntryPoint, CodeBlock.Builder staticInitializer, MethodSpec method) {
            optimizedEntryPoint.addMethod(method);
            if (method.returnType.equals(TypeName.VOID)) {
                staticInitializer.addStatement("$L()", method.name);
            } else {
                staticInitializer.addStatement("$T _$L = $L()", method.returnType, method.name, method.name);
            }
        }
    }

}
