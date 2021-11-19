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
package io.micronaut.aot.core.context;

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
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
public final class DefaultSourceGenerationContext implements AOTContext {
    private final String packageName;
    private final ApplicationContextAnalyzer analyzer;
    private final Set<String> excludedResources = new TreeSet<>();
    private final Map<String, List<String>> diagnostics = new HashMap<>();
    private final Set<Class<?>> classesRequiredAtCompilation = new HashSet<>();
    private final Configuration configuration;
    private final Map<Class<?>, Object> context = new HashMap<>();
    private final List<JavaFile> generatedJavaFiles = new ArrayList<>();
    private final List<MethodSpec> initializers = new ArrayList<>();
    private final Path generatedResourcesDirectory;

    public DefaultSourceGenerationContext(String packageName,
                                          ApplicationContextAnalyzer analyzer,
                                          Configuration configuration,
                                          Path generatedResourcesDirectory) {
        this.packageName = packageName;
        this.analyzer = analyzer;
        this.configuration = configuration;
        this.generatedResourcesDirectory = generatedResourcesDirectory;
    }

    @NonNull
    @Override
    public String getPackageName() {
        return packageName;
    }

    @NonNull
    @Override
    public Runtime getRuntime() {
        return configuration.getRuntime();
    }

    @NonNull
    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @NonNull
    @Override
    public ApplicationContextAnalyzer getAnalyzer() {
        return analyzer;
    }

    @Override
    public void registerExcludedResource(@NonNull String path) {
        excludedResources.add(path);
    }

    @Override
    public void registerClassNeededAtCompileTime(@NonNull Class<?> clazz) {
        classesRequiredAtCompilation.add(clazz);
    }

    @Override
    public void registerGeneratedSourceFile(@NonNull JavaFile javaFile) {
        generatedJavaFiles.add(javaFile);
    }

    public List<JavaFile> getGeneratedJavaFiles() {
        return Collections.unmodifiableList(generatedJavaFiles);
    }

    @Override
    public void registerStaticInitializer(MethodSpec staticInitializer) {
        initializers.add(staticInitializer);
    }

    public List<MethodSpec> getGeneratedStaticInitializers() {
        return initializers;
    }

    @Override
    public void registerGeneratedResource(@NonNull String path, Consumer<? super File> consumer) {
        Path relative = generatedResourcesDirectory.resolve(path);
        File resourceFile = relative.toFile();
        File parent = resourceFile.getParentFile();
        if (parent.exists() || parent.mkdirs()) {
            consumer.accept(resourceFile);
        } else {
            throw new RuntimeException("Unable to create parent file " + parent + " for resource " + path);
        }
    }

    @NonNull
    public List<File> getExtraClasspath() {
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
     * @return the list of resources registered to be excluded.
     * @see AOTContext#registerExcludedResource
     */
    @NonNull
    public Set<String> getExcludedResources() {
        return excludedResources;
    }

    @NonNull
    @Override
    public JavaFile javaFile(TypeSpec typeSpec) {
        return JavaFile.builder(packageName, typeSpec).build();
    }

    @Override
    public void addDiagnostics(String category, String message) {
        diagnostics.computeIfAbsent(category, c -> new ArrayList<>()).add(message);
    }

    @Override
    public <T> void put(@NonNull Class<T> type, @NonNull T value) {
        context.put(type, value);
    }

    @NonNull
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(@NonNull Class<T> type) {
        T o = (T) context.get(type);
        return Optional.ofNullable(o);
    }

    @NonNull
    @Override
    public Map<String, List<String>> getDiagnostics() {
        return diagnostics;
    }
}
