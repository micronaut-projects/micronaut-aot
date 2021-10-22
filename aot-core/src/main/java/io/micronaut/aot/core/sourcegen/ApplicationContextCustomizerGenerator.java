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

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTSourceGenerator;
import io.micronaut.context.ApplicationContextCustomizer;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * The "optimized" entry point generator is the main source generator:
 * it is responsible for generating a new entry point, which delegates
 * to the original entry point of the application, and injects a number
 * of optimizations before starting the application.
 *
 * The list of optimizations to inject is determined by the list of
 * delegate optimizers which are passed to this generator.
 */
public class ApplicationContextCustomizerGenerator extends AbstractSourceGenerator {
    public static final String ID = "application.context.customizer";

    public static final String CUSTOMIZER_CLASS_NAME = "AOTApplicationContextCustomizer";

    private final List<AOTSourceGenerator> sourceGenerators;

    public ApplicationContextCustomizerGenerator(List<AOTSourceGenerator> sourceGenerators) {
        this.sourceGenerators = sourceGenerators;
    }

    @Override
    @NonNull
    public String getId() {
        return ID;
    }

    @Override
    @NonNull
    public List<JavaFile> generateSourceFiles() {
        List<JavaFile> allFiles = new ArrayList<>();
        for (AOTSourceGenerator sourceGenerator : sourceGenerators) {
            allFiles.addAll(sourceGenerator.generateSourceFiles());
        }
        TypeSpec.Builder optimizedEntryPoint = TypeSpec.classBuilder(CUSTOMIZER_CLASS_NAME)
                .addSuperinterface(ApplicationContextCustomizer.class)
                .addModifiers(PUBLIC);
        CodeBlock.Builder staticInitializer = CodeBlock.builder();
        for (AOTSourceGenerator sourceGenerator : sourceGenerators) {
            sourceGenerator.generateStaticInit().ifPresent(method -> appendInitializer(optimizedEntryPoint, staticInitializer, method));
        }
        optimizedEntryPoint.addStaticBlock(staticInitializer.build());
        allFiles.add(getContext().javaFile(optimizedEntryPoint.build()));
        return allFiles;
    }

    @Override
    public void generateResourceFiles(@NonNull File targetDirectory) {
        for (AOTSourceGenerator sourceGenerator : sourceGenerators) {
            sourceGenerator.generateResourceFiles(targetDirectory);
        }
        writeServiceFile(targetDirectory, ApplicationContextCustomizer.class, CUSTOMIZER_CLASS_NAME);
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
