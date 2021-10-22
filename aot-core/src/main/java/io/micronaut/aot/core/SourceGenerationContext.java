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

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The source generation context is used by source generators
 * to get configuration details. It is also used as the communication
 * medium between source generators when one depends on the other.
 */
public interface SourceGenerationContext {
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

    @NonNull
    List<File> getExtraClasspath();

    /**
     * Returns the list of resources to be excluded from
     * the binary.
     *
     * @return the list of resources registered to be excluded.
     * @see SourceGenerationContext#registerExcludedResource
     */
    @NonNull
    Set<String> getExcludedResources();

    /**
     * Generates a java file spec
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
     * Returns the diagnostics map
     * @return the diagnostics
     */
    @NonNull
    Map<String, List<String>> getDiagnostics();

    /**
     * Returns the target runtime environment
     * @return target runtime
     */
    @NonNull
    Runtime getRuntime();
}
