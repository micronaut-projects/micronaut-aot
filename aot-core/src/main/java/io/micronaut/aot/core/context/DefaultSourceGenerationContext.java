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

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.optim.StaticOptimizations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The source generation context.
 * <p>
 * Typically, provides access to the application classloader or the name of
 * the package of classes which are going to be generated.
 * <p>
 * In addition, the context can be used to register resources which will need
 * to be excluded from the final binary (e.g. if a configuration file is replaced
 * with a class at build time, we need a way to explain that the resource file
 * needs to be excluded from the binary).
 * <p>
 * Last but not least, this context can be used to send diagnostic messages
 * which are written to log files during code generation.
 */
public final class DefaultSourceGenerationContext implements AOTContext {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultSourceGenerationContext.class);

    private final String packageName;
    private final ApplicationContextAnalyzer analyzer;
    private final Set<String> excludedResources = new TreeSet<>();
    private final Map<String, List<String>> diagnostics = new ConcurrentHashMap<>();
    private final Set<Class<?>> classesRequiredAtCompilation = new HashSet<>();
    private final Configuration configuration;
    private final Map<Class<?>, Object> context = new HashMap<>();
    private final List<JavaFile> generatedJavaFiles = new ArrayList<>();
    private final List<MethodSpec> initializers = new ArrayList<>();
    private final Path generatedResourcesDirectory;
    private final Set<String> buildTimeInitClasses = new HashSet<>();
    private final List<Runnable> deferredOperations = new ArrayList<>();

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
        LOGGER.debug("Registering excluded resource: {}", path);
        excludedResources.add(path);
    }

    @Override
    public void registerClassNeededAtCompileTime(@NonNull Class<?> clazz) {
        classesRequiredAtCompilation.add(clazz);
    }

    @Override
    public void registerGeneratedSourceFile(@NonNull JavaFile javaFile) {
        LOGGER.debug("Registering generated source file: {}.{}", javaFile.packageName, javaFile.typeSpec.name);
        generatedJavaFiles.add(javaFile);
    }

    public List<JavaFile> getGeneratedJavaFiles() {
        return Collections.unmodifiableList(generatedJavaFiles);
    }

    @Override
    public void registerStaticInitializer(MethodSpec staticInitializer) {
        initializers.add(staticInitializer);
    }

    /**
     * Registers a static optimization method. This will automatically
     * create a class which implements the {@link StaticOptimizations}
     * service type. The consumer should create a body which returns
     * an instance of the optimization type.
     *
     * @param className the name of the class to generate
     * @param optimizationKind the type of the optimization
     * @param bodyBuilder the builder of the body of the load() method
     */
    @Override
    public <T> void registerStaticOptimization(String className, Class<T> optimizationKind, Consumer<? super CodeBlock.Builder> bodyBuilder) {
        CodeBlock.Builder body = CodeBlock.builder();
        bodyBuilder.accept(body);
        MethodSpec method = MethodSpec.methodBuilder("load")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(Override.class)
            .returns(optimizationKind)
            .addCode(body.build())
            .build();
        TypeSpec generatedType = TypeSpec.classBuilder(className)
            .addModifiers(Modifier.PUBLIC)
            .addSuperinterface(ParameterizedTypeName.get(StaticOptimizations.Loader.class, optimizationKind))
            .addMethod(method)
            .build();
        registerBuildTimeInit(optimizationKind.getName());
        registerGeneratedSourceFile(javaFile(generatedType));
        registerServiceImplementation(StaticOptimizations.Loader.class, className);
    }

    /**
     * Registers a generated service type.
     *
     * @param serviceType the type of the service
     * @param simpleServiceName the simple name of the generated type
     */
    @Override
    public void registerServiceImplementation(Class<?> serviceType, String simpleServiceName) {
        registerGeneratedResource("META-INF/services/" + serviceType.getName(), serviceFile -> {
            try (var wrt = new PrintWriter(new FileWriter(serviceFile, true))) {
                wrt.println(getPackageName() + "." + simpleServiceName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public List<MethodSpec> getGeneratedStaticInitializers() {
        return initializers;
    }

    @Override
    public void registerGeneratedResource(@NonNull String path, Consumer<? super File> consumer) {
        LOGGER.debug("Registering generated resource file: {}", path);
        deferredOperations.add(() -> {
            Path relative = generatedResourcesDirectory.resolve(path);
            File resourceFile = relative.toFile();
            File parent = resourceFile.getParentFile();
            if (parent.exists() || parent.mkdirs()) {
                consumer.accept(resourceFile);
            } else {
                throw new RuntimeException("Unable to create parent file " + parent + " for resource " + path);
            }
        });
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
            .toList();
    }

    @Override
    public void registerBuildTimeInit(String className) {
        buildTimeInitClasses.add(className);
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
        return Collections.unmodifiableSet(excludedResources);
    }

    @NonNull
    @Override
    public JavaFile javaFile(TypeSpec typeSpec) {
        return JavaFile.builder(packageName, typeSpec).build();
    }

    @Override
    public void addDiagnostics(String category, String message) {
        diagnostics.computeIfAbsent(category, c -> Collections.synchronizedList(new ArrayList<>())).add(message);
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

    @Override
    public Set<String> getBuildTimeInitClasses() {
        return Collections.unmodifiableSet(buildTimeInitClasses);
    }

    @Override
    public void finish() {
        deferredOperations.forEach(Runnable::run);
    }
}
