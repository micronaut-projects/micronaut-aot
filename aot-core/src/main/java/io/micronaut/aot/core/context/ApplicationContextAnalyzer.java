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

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.DefaultBeanResolutionContext;
import io.micronaut.context.Qualifier;
import io.micronaut.context.RequiresCondition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.condition.Failure;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.convert.ArgumentConversionContext;
import io.micronaut.core.type.Argument;
import io.micronaut.inject.BeanDefinition;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * An application context analyzer is responsible for instantiating
 * an application context and inferring whether a bean should be
 * included in the application binaries.
 *
 * It uses a {@code Predicate<Object>} as superinterface because it
 * needs to run in the same classloader as the optimizer, where the
 * Micronaut specific types are different from the ones loaded in
 * the analyzed classpath.
 *
 */
@SuppressWarnings("unused")
public final class ApplicationContextAnalyzer {
    private final ApplicationContext applicationContext;

    private ApplicationContextAnalyzer(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Set<String> getEnvironmentNames() {
        return applicationContext.getEnvironment().getActiveNames();
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * Instantiates an application context analyzer.
     * @return an analyzer
     */
    public static ApplicationContextAnalyzer create() {
        return create(spec -> { });
    }

    /**
     * Instantiates an application context analyzer with the ability to
     * customize the application context.
     * @param spec the spec to configure the application context
     * @return the analyzer
     */
    public static ApplicationContextAnalyzer create(Consumer<? super ApplicationContextBuilder> spec) {
        ApplicationContextBuilder builder = ApplicationContext.builder();
        spec.accept(builder);
        ApplicationContext context = builder.build();
        finalizeConfiguration(context);
        return new ApplicationContextAnalyzer(context);
    }

    private static void finalizeConfiguration(ApplicationContext context) {
        try {
            // Micronaut 3.2.3+
            Method method = DefaultBeanContext.class.getDeclaredMethod("finalizeConfiguration");
            method.invoke(context);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            // Micronaut 3.2.x
            try {
                Method method = DefaultBeanContext.class.getDeclaredMethod("readAllBeanConfigurations");
                method.setAccessible(true);
                method.invoke(context);
                method = DefaultBeanContext.class.getDeclaredMethod("readAllBeanDefinitionClasses");
                method.setAccessible(true);
                method.invoke(context);
            }  catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
                // ignore
            }
        }
        Environment environment = context.getEnvironment();
        environment.start();
        context.registerSingleton(Environment.class, environment);
    }

    /**
     * Returns a predicate which can be used to determine, from annotation metadata,
     * if a bean matches requirements.
     * @return a predicate
     */
    public Predicate<AnnotationMetadataProvider> getAnnotationMetadataPredicate() {
        return new AnnotationMetadataProviderPredicate();
    }

    private final class ShallowConditionContext<T extends AnnotationMetadataProvider> implements ConditionContext<T> {
        private final T component;
        private List<Failure> failures;

        private ShallowConditionContext(T component) {
            this.component = component;
        }

        @Override
        public boolean containsProperty(String name) {
            return applicationContext.containsProperty(name);
        }

        @Override
        public boolean containsProperties(String name) {
            return applicationContext.containsProperties(name);
        }

        @Override
        public <T> Optional<T> getProperty(String name, ArgumentConversionContext<T> conversionContext) {
            return applicationContext.getProperty(name, conversionContext);
        }

        @Override
        public Collection<List<String>> getPropertyPathMatches(String pathPattern) {
            return applicationContext.getPropertyPathMatches(pathPattern);
        }

        @Override
        public <T> T getBean(BeanDefinition<T> definition) {
            return applicationContext.getBean(definition);
        }

        @Override
        public <T> T getBean(Class<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.getBean(beanType, qualifier);
        }

        @Override
        public <T> Optional<T> findBean(Argument<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.findBean(beanType, qualifier);
        }

        @Override
        public <T> Optional<T> findBean(Class<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.findBean(beanType, qualifier);
        }

        @Override
        public <T> Collection<T> getBeansOfType(Class<T> beanType) {
            return applicationContext.getBeansOfType(beanType);
        }

        @Override
        public <T> Collection<T> getBeansOfType(Class<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.getBeansOfType(beanType, qualifier);
        }

        @Override
        public <T> Stream<T> streamOfType(Class<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.streamOfType(beanType, qualifier);
        }

        @Override
        public <T> T getProxyTargetBean(Class<T> beanType, Qualifier<T> qualifier) {
            return applicationContext.getProxyTargetBean(beanType, qualifier);
        }

        @Override
        public T getComponent() {
            return component;
        }

        @Override
        public BeanContext getBeanContext() {
            return applicationContext;
        }

        @Override
        public BeanResolutionContext getBeanResolutionContext() {
            return new DefaultBeanResolutionContext(applicationContext, null);
        }

        @Override
        public List<Failure> getFailures() {
            return failures == null ? Collections.emptyList() : failures;
        }

        @Override
        public ConditionContext<T> fail(Failure failure) {
            if (failures == null) {
                failures = new ArrayList<>();
            }
            failures.add(failure);
            return this;
        }
    }

    private class AnnotationMetadataProviderPredicate implements Predicate<AnnotationMetadataProvider> {

        @Override
        public boolean test(AnnotationMetadataProvider component) {
            ShallowConditionContext<AnnotationMetadataProvider> context = new ShallowConditionContext<>(component);
            return new RequiresCondition(component.getAnnotationMetadata()).matches(context);
        }

    }
}
