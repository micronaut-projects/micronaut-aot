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

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.hosted.ServiceLoaderFeature;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.core.annotation.Generated;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

import javax.lang.model.element.Modifier;
import java.util.List;

/**
 * Generates the GraalVM feature class which is going to configure
 * the native image code generation, typically asking to initialize
 * the optimized entry point at build time.
 */
public class GraalVMOptimizationFeatureSourceGenerator extends AbstractSingleClassFileGenerator {

    public static final String MICRONAUT_AOT_FEATURE_CLASS_NAME = "MicronautAOTFeature";

    private final String optimizedEntryPoint;
    private final List<String> serviceTypes;

    public GraalVMOptimizationFeatureSourceGenerator(SourceGenerationContext context,
                                                     String optimizedEntryPoint,
                                                     List<String> serviceTypes) {
        super(context);
        this.optimizedEntryPoint = optimizedEntryPoint;
        this.serviceTypes = serviceTypes;
    }

    @Override
    protected void doInit() throws Exception {
        getContext().registerClassNeededAtCompileTime(Feature.class);
        getContext().registerClassNeededAtCompileTime(AutomaticFeature.class);
    }

    @Override
    protected JavaFile generate() {
        return getContext().javaFile(createFeatureType());
    }

    private TypeSpec createFeatureType() {
        CodeBlock.Builder staticInit = CodeBlock.builder();
        for (String serviceType : serviceTypes) {
            staticInit.addStatement(
                    CodeBlock.of("$T.ServiceLoaderFeatureExcludeServices.getValue()" +
                            ".valueUpdate($S)", ServiceLoaderFeature.Options.class, serviceType)
            );
        }
        return TypeSpec.classBuilder(MICRONAUT_AOT_FEATURE_CLASS_NAME)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Generated.class)
                .addAnnotation(AutomaticFeature.class)
                .addSuperinterface(Feature.class)
                .addMethod(MethodSpec.methodBuilder("beforeAnalysis")
                        .addModifiers(Modifier.PUBLIC)
                        .addParameter(Feature.BeforeAnalysisAccess.class, "access")
                        .addStatement(
                                CodeBlock.of("$T.initializeAtBuildTime($T.class)", RuntimeClassInitialization.class, ClassName.bestGuess(optimizedEntryPoint))
                        )
                        .build())
                .addStaticBlock(staticInit.build())
                .build();
    }

}
