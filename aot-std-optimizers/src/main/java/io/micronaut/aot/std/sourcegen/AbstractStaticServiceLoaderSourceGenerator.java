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
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.Environments;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.codegen.DelegatingSourceGenerationContext;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.inject.BeanConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

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
    public static final String FORCE_INCLUDE = "serviceloading.force.include.impls";

    protected static final String DEFAULT_SERVICE_TYPES = "io.micronaut.context.env.PropertySourceLoader,io.micronaut.inject.BeanConfiguration,io.micronaut.inject.BeanDefinitionReference,io.micronaut.http.HttpRequestFactory,io.micronaut.http.HttpResponseFactory,io.micronaut.core.beans.BeanIntrospectionReference,io.micronaut.core.convert.TypeConverterRegistrar,io.micronaut.context.ApplicationContextConfigurer,io.micronaut.context.env.PropertyExpressionResolver";
    public static final List<String> DEFAULT_SERVICE_TYPES_LIST = Arrays.stream(DEFAULT_SERVICE_TYPES.split(",")).toList();

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractStaticServiceLoaderSourceGenerator.class);

    protected AOTContext context;

    private Predicate<AnnotationMetadataProvider> metadataProviderPredicate;
    private List<String> serviceNames;
    private Predicate<String> rejectedClasses;
    private Map<String, AbstractCodeGenerator> substitutions;
    private Set<String> forceInclude;
    private final Substitutes substitutes = new Substitutes();
    private final Map<String, TypeSpec> staticServiceClasses = new HashMap<>();
    private final Set<BeanConfiguration> disabledConfigurations = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, List<Class<?>>> serviceClasses = new HashMap<>();
    private final Set<Class<?>> disabledServices = new HashSet<>();

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
            context.getConfiguration().stringList(Environments.POSSIBLE_ENVIRONMENTS_NAMES)
                    .stream()
                    .filter(env -> !"default".equals(env))
                    .map(env -> "application-" + env)
                    .forEach(resourceNames::add);
            substitutions = new HashMap<>();
            if (context.getConfiguration().isFeatureEnabled(YamlPropertySourceGenerator.ID)) {
                YamlPropertySourceGenerator yaml = new YamlPropertySourceGenerator(resourceNames);
                yaml.generate(context);
                if (MetadataUtils.isEnabledOn(context.getRuntime(), yaml)) {
                    LOGGER.debug("Substituting {} with {}", PropertySourceLoader.class.getName(), yaml.getClass().getName());
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
        if (forceInclude == null) {
            forceInclude = new HashSet<>(context.getConfiguration().stringList(findOption(this.getClass(), FORCE_INCLUDE).key()));
        }
        for (String serviceName : serviceNames) {
            LOGGER.debug("Processing service type {}", serviceName);
            collectServiceImplementations(serviceName);
        }
        context.put(Substitutes.class, substitutes);

        for (BeanConfiguration beanConfiguration : disabledConfigurations) {
            for (List<Class<?>> classList : serviceClasses.values()) {
                for (Class<?> clazz : classList) {
                    if (beanConfiguration.isWithin(clazz)) {
                        context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Disabling " + clazz.getName() + " because it belongs to " + beanConfiguration.getName() + " which is disabled (" + beanConfiguration.getClass() + ")");
                        disabledServices.add(clazz);
                    }
                }
            }
        }
        generateServiceLoader();
        LOGGER.debug("Generated static service loader classes: {}", staticServiceClasses.keySet());
        LOGGER.debug("Generated static {} service loader substitutions", substitutes.values().size());
        staticServiceClasses.values()
                .stream()
                .map(context::javaFile)
                .forEach(context::registerGeneratedSourceFile);
        context.registerStaticOptimization("StaticServicesLoader", SoftServiceLoader.Optimizations.class, this::buildOptimization);
    }

    private void generateServiceLoader() {
        for (Map.Entry<String, List<Class<?>>> services : serviceClasses.entrySet()) {
            String serviceName = services.getKey();
            List<Class<?>> implementations = services.getValue();
            Class<?> serviceType;
            try {
                serviceType = this.getClass().getClassLoader().loadClass(serviceName);
            } catch (ClassNotFoundException e) {
                // Shouldn't happen at this stage
                throw new RuntimeException(e);
            }
            TypeSpec.Builder factory = prepareServiceLoaderType(serviceName, serviceType);
            generateFindAllMethod(
                    implementations.stream().filter(clazz -> !rejectedClasses.test(clazz.getName()) && !disabledServices.contains(clazz)),
                    serviceName,
                    serviceType,
                    factory);
            staticServiceClasses.put(serviceName, factory.build());
        }
    }

    private void collectServiceImplementations(String serviceName) {
        context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Starting service discovery for type " + serviceName);
        ClassLoader cl = this.getClass().getClassLoader();
        Set<String> seen = Collections.synchronizedSet(new HashSet<>());
        SoftServiceLoader.ServiceCollector<Class<?>> availableClasses = SoftServiceLoader.newCollector(serviceName, s -> !s.isEmpty(), cl, className -> {
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
            Class<?> clazz;
            try {
                clazz = cl.loadClass(className);
                DeepAnalyzer deepAnalyzer = deepAnalyzerFor(clazz, serviceName);
                boolean available = deepAnalyzer.isAvailable(clazz);
                if (!available && forceInclude.contains(className)) {
                    context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Forcing inclusion of " + clazz + " despite it not matching bean requirements");
                    available = true;
                }
                if (!available) {
                    if (BeanConfiguration.class.isAssignableFrom(clazz)) {
                        disabledConfigurations.add((BeanConfiguration) clazz.getConstructor().newInstance());
                    }
                    context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping " + clazz + " because it doesn't match bean requirements");
                    return null;
                }
            } catch (ClassNotFoundException | NoClassDefFoundError | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                context.addDiagnostics(SERVICE_LOADING_CATEGORY, "Skipping service " + serviceName + " implementation " + className + " because of missing dependencies: " + e.getMessage());
                return null;
            }
            return clazz;
        });
        List<Class<?>> serviceClasses = new ArrayList<>();
        availableClasses.collect(serviceClasses::add);
        this.serviceClasses.put(serviceName, serviceClasses);
    }

    private DeepAnalyzer deepAnalyzerFor(Class<?> clazz, String serviceName) throws ClassNotFoundException {
        if (AnnotationMetadataProvider.class.isAssignableFrom(clazz)) {
            return new AnnotationMetadataAnalyzer(context, metadataProviderPredicate, serviceName);
        }
        return DeepAnalyzer.DEFAULT;
    }

    protected abstract void generateFindAllMethod(Stream<Class<?>> serviceClasses,
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

    private void buildOptimization(CodeBlock.Builder body) {
            ParameterizedTypeName serviceLoaderType = ParameterizedTypeName.get(
                    ClassName.get(SoftServiceLoader.StaticServiceLoader.class), WildcardTypeName.subtypeOf(Object.class));
            body.addStatement("$T staticServices = new $T()",
                    ParameterizedTypeName.get(ClassName.get(Map.class), ClassName.get(String.class), serviceLoaderType),
                    ParameterizedTypeName.get(ClassName.get(HashMap.class), ClassName.get(String.class), serviceLoaderType));

            for (Map.Entry<String, TypeSpec> entry : staticServiceClasses.entrySet()) {
                body.addStatement("staticServices.put($S, new $T())", entry.getKey(), ClassName.bestGuess(entry.getValue().name));
            }
            body.addStatement("return new $T(staticServices)", SoftServiceLoader.Optimizations.class);
    }

    /**
     * Data holder for substitutes.
     */
    static final class Substitutes {
        private final Map<String, List<JavaFile>> substitutes = new HashMap<>();

        private Collection<List<JavaFile>> values() {
            return substitutes.values();
        }

        void putAll(Map<String, List<JavaFile>> substitutes) {
            this.substitutes.putAll(substitutes);
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
