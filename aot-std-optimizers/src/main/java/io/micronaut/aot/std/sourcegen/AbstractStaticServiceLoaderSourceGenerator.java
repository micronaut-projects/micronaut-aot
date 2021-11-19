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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.codegen.DelegatingSourceGenerationContext;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.optim.StaticOptimizations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.micronaut.aot.core.config.MetadataUtils.findOption;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * Base class for generation of service loader classes. Because service loading
 * has a very different behavior in JIT (regular JVM) mode and native mode, we
 * have dedicated implementations for both (see subclasses).
 */
public abstract class AbstractStaticServiceLoaderSourceGenerator extends AbstractCodeGenerator {
    public static final String SERVICE_LOADING_CATEGORY = "serviceloading";
    public static final String DESCRIPTION = "Scans for service types ahead-of-time, avoiding classpath scanning at startup";
    public static final String SERVICE_TYPES = "service.types";
    public static final String REJECTED_CLASSES = "serviceloading.rejected.impls";

    protected AOTContext context;

    private Predicate<AnnotationMetadataProvider> metadataProviderPredicate;
    private List<String> serviceNames;
    private Predicate<String> rejectedClasses;
    private Map<String, AbstractCodeGenerator> substitutions;
    private final Substitutes substitutes = new Substitutes();
    private Map<String, TypeSpec> staticServiceClasses;

    @Override
    public void generate(@NonNull AOTContext context) {
        this.context = context;
        if (serviceNames == null) {
            serviceNames = context.getConfiguration().stringList(findOption(this.getClass(), SERVICE_TYPES).key());
        }
        if (substitutions == null) {
            Set<String> resourceNames = new LinkedHashSet<>();
            resourceNames.add("application");
            context.getAnalyzer().getEnvironmentNames()
                    .stream()
                    .map(env -> "application-" + env)
                    .forEach(resourceNames::add);
            substitutions = new HashMap<>();
            if (context.getConfiguration().isFeatureEnabled(YamlPropertySourceGenerator.ID)) {
                YamlPropertySourceGenerator yaml = new YamlPropertySourceGenerator(resourceNames);
                yaml.generate(context);
                if (MetadataUtils.isEnabledOn(context.getRuntime(), yaml)) {
                    substitutions.put(YamlPropertySourceLoader.class.getName(), yaml);
                }
            }
        }
        if (metadataProviderPredicate == null) {
            metadataProviderPredicate = context.getAnalyzer().getAnnotationMetadataPredicate();
        }
        if (rejectedClasses == null) {
            List<String> strings = context.getConfiguration().stringList(findOption(this.getClass(), REJECTED_CLASSES).key());
            Set<String> rejected = strings.isEmpty() ? Collections.emptySet() : new HashSet<>(strings);
            rejectedClasses = rejected::contains;
        }
        if (staticServiceClasses == null) {
            staticServiceClasses = new HashMap<>();
            try {
                for (String serviceName : serviceNames) {
                    generateServiceLoader(rejectedClasses, serviceName);
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
        context.put(Substitutes.class, substitutes);
        staticServiceClasses.values()
                .stream()
                .map(context::javaFile)
                .forEach(context::registerGeneratedSourceFile);
        context.registerStaticInitializer(generateStaticInit());
    }

    private void generateServiceLoader(Predicate<String> rejectedClasses, String serviceName) throws ClassNotFoundException {
        Class<?> serviceType = this.getClass().getClassLoader().loadClass(serviceName);
        TypeSpec.Builder factory = prepareServiceLoaderType(serviceName, serviceType);
        generateFindAllMethod(rejectedClasses, serviceName, serviceType, factory);
        staticServiceClasses.put(serviceName, factory.build());
    }

    protected final <T> List<T> collectServiceImplementations(
            String serviceName,
            BiFunction<Class<?>, Boolean, T> emitter) {
        context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Starting service discovery for type " + serviceName);
        ClassLoader cl = this.getClass().getClassLoader();
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        SoftServiceLoader.ServiceCollector<T> collector = SoftServiceLoader.newCollector(serviceName, s -> !s.isEmpty(), cl, className -> {
            if (rejectedClasses.test(className) || !seen.add(className)) {
                return null;
            }
            AbstractCodeGenerator substitution = substitutions.get(className);
            if (substitution != null) {
                List<JavaFile> javaFiles = new ArrayList<>();
                AOTContext tracker = new DelegatingSourceGenerationContext(context) {
                    @Override
                    public void registerGeneratedSourceFile(@NonNull JavaFile javaFile) {
                        super.registerGeneratedSourceFile(javaFile);
                        javaFiles.add(javaFile);
                    }
                };
                substitution.generate(tracker);
                javaFiles.forEach(substitute -> substitutes.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(substitute));
                if (!javaFiles.isEmpty()) {
                    return null;
                }
            }
            try {
                Class<?> clazz = cl.loadClass(className);
                DeepAnalyzer deepAnalyzer = deepAnalyzerFor(clazz, serviceName);
                boolean available = deepAnalyzer.isAvailable(clazz);
                if (!available) {
                    context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping " + clazz + " because it doesn't match bean requirements");
                    return null;
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    if ("provider".equals(method.getName()) && Modifier.isStatic(method.getModifiers())) {
                        return emitter.apply(clazz, true);
                    }
                }
                for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                    if (constructor.getParameterCount() == 0 && Modifier.isPublic(constructor.getModifiers())) {
                        return emitter.apply(clazz, false);
                    }
                }
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping service " + serviceName + " implementation " + className + " because of missing dependencies: " + e.getMessage());
                return null;
            }
            context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping service " + serviceName + " implementation " + className + " because it's not a service provider");
            return null;
        });
        List<T> result = new ArrayList<>();
        collector.collect(result::add);
        context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Found " + result.size() + " services of type " + serviceName);
        return result;
    }

    private DeepAnalyzer deepAnalyzerFor(Class<?> clazz, String serviceName) throws ClassNotFoundException {
        if (AnnotationMetadataProvider.class.isAssignableFrom(clazz)) {
            return new AnnotationMetadataAnalyzer(context, metadataProviderPredicate, serviceName);
        }
        return DeepAnalyzer.DEFAULT;
    }

    protected abstract void generateFindAllMethod(Predicate<String> rejectedClasses,
                                                  String serviceName,
                                                  Class<?> serviceType,
                                                  TypeSpec.Builder factory);

    private TypeSpec.Builder prepareServiceLoaderType(String serviceName, Class<?> serviceType) {
        String name = simpleNameOf(serviceName) + "Factory";
        TypeSpec.Builder factory = TypeSpec.classBuilder(name)
                .addModifiers(PUBLIC)
                .addAnnotation(Generated.class)
                .addSuperinterface(ParameterizedTypeName.get(SoftServiceLoader.StaticServiceLoader.class, serviceType));
        return factory;
    }

    private MethodSpec generateStaticInit() {
        return staticMethod("staticServices", body -> {
            ParameterizedTypeName serviceLoaderType = ParameterizedTypeName.get(
                    ClassName.get(SoftServiceLoader.StaticServiceLoader.class), WildcardTypeName.subtypeOf(Object.class));
            body.addStatement("$T staticServices = new $T()",
                    ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), serviceLoaderType),
                    ParameterizedTypeName.get(ClassName.get(HashMap.class), ClassName.get(String.class), serviceLoaderType));

            for (Map.Entry<String, TypeSpec> entry : staticServiceClasses.entrySet()) {
                body.addStatement("staticServices.put($S, new $T())", entry.getKey(), ClassName.bestGuess(entry.getValue().name));
            }
            body.addStatement("$T.set(new $T(staticServices))",
                    StaticOptimizations.class,
                    SoftServiceLoader.Optimizations.class);
        });
    }

    /**
     * Data holder for substitutes.
     */
    static final class Substitutes {
        private final Map<String, List<JavaFile>> substitutes = new HashMap<>();

        private Collection<List<JavaFile>> values() {
            return substitutes.values();
        }

        private List<JavaFile> computeIfAbsent(String key, Function<? super String, ? extends List<JavaFile>> mappingFunction) {
            return substitutes.computeIfAbsent(key, mappingFunction);
        }

        /**
         * Returns the list of java source files which will are generated
         * in replacement of a known service type.
         * This mechanism is used to substitute, for example, Yaml property
         * source loader with individual property sources (which are not of
         * the requested service type).
         *
         * @param serviceType the service implementation type
         * @return the list of source files generated in replacement
         */
        public List<JavaFile> findSubstitutesFor(String serviceType) {
            return substitutes.getOrDefault(serviceType, Collections.emptyList());
        }
    }

    interface DeepAnalyzer {
        DeepAnalyzer DEFAULT = new DeepAnalyzer() {
        };

        default boolean isAvailable(Class<?> clazz) {
            return true;
        }
    }

    private static final class AnnotationMetadataAnalyzer implements DeepAnalyzer {
        private final AOTContext context;
        private final Predicate<AnnotationMetadataProvider> predicate;
        private final String serviceName;

        private AnnotationMetadataAnalyzer(AOTContext context, Predicate<AnnotationMetadataProvider> predicate, String serviceName) {
            this.context = context;
            this.predicate = predicate;
            this.serviceName = serviceName;
        }

        @Override
        public boolean isAvailable(Class<?> clazz) {
            try {
                AnnotationMetadataProvider reference = (AnnotationMetadataProvider) clazz.getConstructor().newInstance();
                return predicate.test(reference);
            } catch (Throwable e) {
                return skipService(clazz, e);
            }
        }

        private boolean skipService(Class<?> clazz, Throwable e) {
            context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping service " + serviceName + " implementation " + clazz.getName() + " because of missing dependencies:" + e.getMessage());
            return false;
        }
    }


}
