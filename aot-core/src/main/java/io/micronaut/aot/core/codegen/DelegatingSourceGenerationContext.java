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
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Base class for source generation context which need to delegate
 * to another one.
 */
public abstract class DelegatingSourceGenerationContext implements AOTContext {
    private final AOTContext delegate;

    public DelegatingSourceGenerationContext(AOTContext delegate) {
        this.delegate = delegate;
    }

    @Override
    @NonNull
    public String getPackageName() {
        return delegate.getPackageName();
    }

    @Override
    @NonNull
    public Configuration getConfiguration() {
        return delegate.getConfiguration();
    }

    @Override
    @NonNull
    public ApplicationContextAnalyzer getAnalyzer() {
        return delegate.getAnalyzer();
    }

    @Override
    public void registerGeneratedSourceFile(@NonNull JavaFile javaFile) {
        delegate.registerGeneratedSourceFile(javaFile);
    }

    @Override
    public void registerStaticInitializer(MethodSpec staticInitializer) {
        delegate.registerStaticInitializer(staticInitializer);
    }

    /**
     * Registers a static optimization method. This will automatically
     * create a class which implements the {@link io.micronaut.core.optim.StaticOptimizations}
     * service type. The consumer should create a body which returns
     * an instance of the optimization type.
     *
     * @param className the name of the class to generate
     * @param optimizationKind the type of the optimization
     * @param bodyBuilder the builder of the body of the load() method
     */
    @Override
    public <T> void registerStaticOptimization(String className, Class<T> optimizationKind, Consumer<? super CodeBlock.Builder> bodyBuilder) {
        delegate.registerStaticOptimization(className, optimizationKind, bodyBuilder);
    }

    /**
     * Registers a generated service type.
     *
     * @param serviceType the type of the service
     * @param simpleServiceName the simple name of the generated type
     */
    @Override
    public void registerServiceImplementation(Class<?> serviceType, String simpleServiceName) {
        delegate.registerServiceImplementation(serviceType, simpleServiceName);
    }

    @Override
    public void registerGeneratedResource(@NonNull String path, Consumer<? super File> consumer) {
        delegate.registerGeneratedResource(path, consumer);
    }

    @Override
    public void registerExcludedResource(@NonNull String path) {
        delegate.registerExcludedResource(path);
    }

    @Override
    public void registerClassNeededAtCompileTime(@NonNull Class<?> clazz) {
        delegate.registerClassNeededAtCompileTime(clazz);
    }

    @Override
    public void registerBuildTimeInit(String className) {
        delegate.registerBuildTimeInit(className);
    }

    @Override
    @NonNull
    public JavaFile javaFile(TypeSpec typeSpec) {
        return delegate.javaFile(typeSpec);
    }

    @Override
    @NonNull
    public void addDiagnostics(String category, String message) {
        delegate.addDiagnostics(category, message);
    }

    @Override
    public <T> void put(@NonNull Class<T> type, @NonNull T value) {
        delegate.put(type, value);
    }

    @Override
    @NonNull
    public <T> Optional<T> get(@NonNull Class<T> type) {
        return delegate.get(type);
    }

    @Override
    @NonNull
    public Map<String, List<String>> getDiagnostics() {
        return delegate.getDiagnostics();
    }

    @Override
    @NonNull
    public Runtime getRuntime() {
        return delegate.getRuntime();
    }

    @Override
    public Set<String> getBuildTimeInitClasses() {
        return delegate.getBuildTimeInitClasses();
    }

    @Override
    public void finish() {
        delegate.finish();
    }
}
