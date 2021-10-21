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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.optim.StaticOptimizations;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * Base class for generation of service loader classes. Because service loading
 * has a very different behavior in JIT (regular JVM) mode and native mode, we
 * have dedicated implementations for both (see subclasses).
 */
public abstract class AbstractStaticServiceLoaderSourceGenerator extends AbstractSourceGenerator {
    public static final String SERVICE_LOADING_CATEGORY = "service-loading";
    private final Predicate<AnnotationMetadataProvider> applicationContextAnalyzer;
    private final List<String> serviceNames;
    private final Predicate<String> rejectedClasses;
    private final Map<String, AbstractSourceGenerator> substitutions;
    private final Map<String, List<JavaFile>> substitutes = new HashMap<>();
    private Map<String, TypeSpec> staticServiceClasses;

    protected AbstractStaticServiceLoaderSourceGenerator(SourceGenerationContext context,
                                                         Predicate<AnnotationMetadataProvider> applicationContextAnalyzer,
                                                      List<String> serviceNames,
                                                      Predicate<String> rejectedClasses,
                                                      Map<String, AbstractSourceGenerator> substitutions) {
        super(context);
        this.applicationContextAnalyzer = applicationContextAnalyzer;
        this.serviceNames = serviceNames;
        this.rejectedClasses = rejectedClasses;
        this.substitutions = substitutions;
    }

    protected final void doInit() throws ClassNotFoundException {
        if (staticServiceClasses == null) {
            staticServiceClasses = new HashMap<>();
            for (String serviceName : serviceNames) {
                generateServiceLoader(rejectedClasses, serviceName);
            }
        }
    }

    private void generateServiceLoader(Predicate<String> rejectedClasses, String serviceName) throws ClassNotFoundException {
        Class<?> serviceType = this.getClass().getClassLoader().loadClass(serviceName);
        TypeSpec.Builder factory = prepareServiceLoaderType(serviceName, serviceType);
        generateFindAllMethod(rejectedClasses, serviceName, serviceType, factory);
        staticServiceClasses.put(serviceName, factory.build());
    }

    protected final <T> List<T> collectServiceImplementations(String serviceName,
                                                        BiFunction<Class<?>, Boolean, T> emitter) {
        context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Starting service discovery for type " + serviceName);
        ClassLoader cl = this.getClass().getClassLoader();
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        SoftServiceLoader.ServiceCollector<T> collector = SoftServiceLoader.newCollector(serviceName, s -> !s.isEmpty(), cl, className -> {
            if (rejectedClasses.test(className) || !seen.add(className)) {
                return null;
            }
            AbstractSourceGenerator substitution = substitutions.get(className);
            if (substitution != null) {
                List<JavaFile> javaFiles = substitution.generateSourceFiles();
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
            return new AnnotationMetadataAnalyzer(context, applicationContextAnalyzer, serviceName);
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

    @Override
    public Optional<MethodSpec> generateStaticInit() {
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

    @Override
    public List<JavaFile> generateSourceFiles() {
        return Stream.concat(
                substitutes.values().stream().flatMap(List::stream),
                staticServiceClasses.values()
                        .stream()
                        .map(typeSpec -> getContext().javaFile(typeSpec))
        ).collect(Collectors.toList());
    }

    /**
     * Returns the list of java source files which will are generated
     * in replacement of a known service type.
     * This mechanism is used to substitute, for example, Yaml property
     * source loader with individual property sources (which are not of
     * the requested service type).
     * @param serviceType the service implementation type
     * @return the list of source files generated in replacement
     */
    public List<JavaFile> findSubstitutesFor(String serviceType) {
        return substitutes.getOrDefault(serviceType, Collections.emptyList());
    }

    interface DeepAnalyzer {
        DeepAnalyzer DEFAULT = new DeepAnalyzer() {
        };

        default boolean isAvailable(Class<?> clazz) {
            return true;
        }
    }

    private static final class AnnotationMetadataAnalyzer implements DeepAnalyzer {
        private final SourceGenerationContext context;
        private final Predicate<AnnotationMetadataProvider> predicate;
        private final String serviceName;

        private AnnotationMetadataAnalyzer(SourceGenerationContext context, Predicate<AnnotationMetadataProvider> predicate, String serviceName) {
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
