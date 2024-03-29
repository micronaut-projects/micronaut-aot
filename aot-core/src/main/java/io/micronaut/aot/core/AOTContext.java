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
package io.micronaut.aot.core;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The source generation context is used by source generators
 * to get configuration details. It is also used as the communication
 * medium between source generators when one depends on the other.
 */
public interface AOTContext {
    /**
     * The package which should be used for generated classes.
     *
     * @return the package name
     */
    @NonNull
    String getPackageName();

    /**
     * Returns the source generators configuration.
     * @return the configuration
     */
    @NonNull
    Configuration getConfiguration();

    /**
     * Returns the application context analyzer.
     * @return the application context analyzer
     */
    @NonNull
    ApplicationContextAnalyzer getAnalyzer();

    /**
     * Registers a generated source file.
     * @param javaFile the file to be added.
     */
    void registerGeneratedSourceFile(@NonNull JavaFile javaFile);

    /**
     * Registers a code block to be executed statically when
     * the optimized binary is loaded.
     * @param staticInitializer the static initializer method
     */
    void registerStaticInitializer(MethodSpec staticInitializer);

    /**
     * Registers a static optimization method. This will automatically
     * create a class which implements the {@link io.micronaut.core.optim.StaticOptimizations}
     * service type. The consumer should create a body which returns
     * an instance of the optimization type.
     * @param className the name of the class to generate
     * @param optimizationKind the type of the optimization
     * @param bodyBuilder the builder of the body of the load() method
     * @param <T> the type class of the optimization
     */
    <T> void registerStaticOptimization(String className, Class<T> optimizationKind, Consumer<? super CodeBlock.Builder> bodyBuilder);

    /**
     * Registers a generated service type.
     * @param serviceType the type of the service
     * @param simpleServiceName the simple name of the generated type
     */
    void registerServiceImplementation(Class<?> serviceType, String simpleServiceName);

    /**
     * Registers a new generated resource.
     * @param path the relative path to the resource (including file name)
     * @param consumer the consumer to be called when the resource is generated.
     */
    void registerGeneratedResource(@NonNull String path, Consumer<? super File> consumer);

    /**
     * Registers a resource path as excluded.
     * Excluded resources should be removed, as much as possible,
     * from the final binary/deliverable since they are either
     * not used or substituted with code.
     *
     * @param path the path of the resource to exclude
     */
    void registerExcludedResource(@NonNull String path);

    /**
     * Registers a class as needed at compile time (where compile time
     * is the compile time of generated classes).
     * This will typically be used when source generators need classes
     * which are not on the application classpath.
     *
     * @param clazz a class
     */
    void registerClassNeededAtCompileTime(@NonNull Class<?> clazz);

    /**
     * Registers a type as a requiring initialization at build time.
     * @param className the type
     */
    void registerBuildTimeInit(@NonNull String className);

    /**
     * Generates a java file spec.
     * @param typeSpec the type spec of the main class
     * @return a java file
     */
    @NonNull
    JavaFile javaFile(TypeSpec typeSpec);

    /**
     * Adds a diagnostic message, which is going to be written
     * in a log file.
     *
     * @param category a category for the message, typically corresponding
     * to the source generator type
     * @param message a message to log
     */
    @NonNull
    void addDiagnostics(String category, String message);

    /**
     * Stores an entry in the context. The entry may be read by other
     * processors, as long as they are executed in the proper order.
     * @param type the class of the value to store
     * @param value the value to store
     * @param <T> the type of the value
     */
    <T> void put(@NonNull Class<T> type, @NonNull T value);

    /**
     * Reads an entry from the context.
     * @param type the class of the entry
     * @param <T> the type of the entry
     * @return an empty value if absent
     */
    @NonNull
    <T> Optional<T> get(@NonNull Class<T> type);

    /**
     * Returns the diagnostics map.
     * @return the diagnostics
     */
    @NonNull
    Map<String, List<String>> getDiagnostics();

    /**
     * Returns the target runtime environment.
     * @return target runtime
     */
    @NonNull
    Runtime getRuntime();

    /**
     * Returns the set of classes which require build time initialization
     * @return the set of classes needing build time init
     */
    Set<String> getBuildTimeInitClasses();

    /**
     * Performs actions which have to be done as late as possible during
     * source generation.
     */
    void finish();
}
