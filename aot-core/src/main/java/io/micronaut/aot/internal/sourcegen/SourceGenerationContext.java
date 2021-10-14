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
package io.micronaut.aot.internal.sourcegen;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.TypeSpec;

import java.io.File;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The source generation context.
 *
 * Typically provides access to the application classloader or the name of
 * the package of classes which are going to be generated.
 *
 * In addition, the context can be used to register resources which will need
 * to be excluded from the final binary (e.g if a configuration file is replaced
 * with a class at build time, we need a way to explain that the resource file
 * needs to be excluded from the binary).
 *
 * Last but not least, this context can be used to send diagnostic messages
 * which are written to log files during code generation.
 */
public class SourceGenerationContext {
    private final URLClassLoader classloader;
    private final String packageName;
    private final Set<String> excludedResources = new TreeSet<>();
    private final Map<String, List<String>> diagnostics = new HashMap<>();
    private final Set<Class<?>> classesRequiredAtCompilation = new HashSet<>();

    public SourceGenerationContext(URLClassLoader classloader, String packageName) {
        this.classloader = classloader;
        this.packageName = packageName;
    }

    /**
     * Returns the application classloader, which contains the
     * full application runtime classpath as known at build time.
     * @return a classloader which can be used to laod application classes
     */
    public URLClassLoader getClassloader() {
        return classloader;
    }

    /**
     * The package which should be used for generated classes.
     * @return the package name
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Registers a resource path as excluded.
     * Excluded resources should be removed, as much as possible,
     * from the final binary/deliverable since they are either
     * not used or substituted with code.
     *
     * @param path the path of the resource to exclude
     */
    public void registerExcludedResource(String path) {
        excludedResources.add(path);
    }

    /**
     * Registers a class as needed at compile time (where compile time
     * is the compile time of generated classes).
     * This will typically be used when source generators need classes
     * which are not on the application classpath.
     *
     * @param clazz a class
     */
    public void registerClassNeededAtCompileTime(Class<?> clazz) {
        classesRequiredAtCompilation.add(clazz);
    }

    public final List<File> getExtraClasspath() {
        return classesRequiredAtCompilation.stream()
                .map(Class::getProtectionDomain)
                .map(ProtectionDomain::getCodeSource)
                .map(CodeSource::getLocation)
                .map(url -> {
                    try {
                        return new File(url.toURI());
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Returns the list of resources to be excluded from
     * the binary.
     *
     * @see SourceGenerationContext#registerExcludedResource
     *
     * @return the list of resources registered to be excluded.
     */
    public Set<String> getExcludedResources() {
        return excludedResources;
    }

    public final JavaFile javaFile(TypeSpec typeSpec) {
        return JavaFile.builder(packageName, typeSpec).build();
    }

    /**
     * Adds a diagnostic message, which is going to be written
     * in a log file.
     * @param category a category for the message, typically corresponding
     * to the source generator type
     * @param message a message to log
     */
    public void addDiagnostics(String category, String message) {
        diagnostics.computeIfAbsent(category, c -> new ArrayList<>()).add(message);
    }

    public final Map<String, List<String>> getDiagnostics() {
        return diagnostics;
    }
}
