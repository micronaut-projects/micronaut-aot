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

import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.context.env.ActiveEnvironment;
import io.micronaut.context.env.EmptyPropertySource;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourceLoader;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.scan.DefaultClassPathResourceLoader;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A source generator which will generate a static {@link PropertySource}
 * from a given configuration file, in order to substitute the dynamic loader
 * with a static configuration.
 * It works for any types of loaders and therefore needs to use reflection to instantiate them.
 */
@AOTModule(
    id = GenericPropertySourceGenerator.ID,
    description = GenericPropertySourceGenerator.DESCRIPTION,
    options = {@Option(
        key = "property-source-loader.types",
        description = "The PropertySourceLoader classnames to use for generating property sources",
        sampleValue = "io.micronaut.context.env.PropertiesPropertySourceLoader,io.micronaut.context.env.yaml.YamlPropertySourceLoader"
    ), @Option(
        key = "property-source-loader.base-order",
        description = "The base order to use for the generated property sources. "
            + "Positive value will be added to base order for specific environments",
        sampleValue = "-1073741824"
    ), @Option(
        key = "property-source-loader.resource-names",
        description = "The resource names to generate property sources for. By default, it is '" + Environment.DEFAULT_NAME + "'.",
        sampleValue = Environment.DEFAULT_NAME
    )}
)
@Internal
public class GenericPropertySourceGenerator extends AbstractCodeGenerator {

    public static final String ID = "property-source-loader.generate";
    public static final String DESCRIPTION = "Converts configuration files supplied by property source loaders to Java configuration";
    public static final Option TYPES_OPTION =
        MetadataUtils.findMetadata(GenericPropertySourceGenerator.class).get().options()[0];
    public static final Option BASE_ORDER_OPTION =
        MetadataUtils.findMetadata(GenericPropertySourceGenerator.class).get().options()[1];
    public static final Option RESOURCE_NAMES_OPTION =
        MetadataUtils.findMetadata(GenericPropertySourceGenerator.class).get().options()[2];

    private static final Logger LOG = LoggerFactory.getLogger(GenericPropertySourceGenerator.class);

    private final Collection<String> resources;
    private final List<String> propertySourceLoaderTypes;
    private final int baseOrder;
    private final Collection<ActiveEnvironment> environments;

    /**
     * Create the generic property source generator from resource names.
     * A resource name and environment will form a property source name e.g.
     * {@code application} or {@code application-test}.
     * @param environments The environments
     */
    public GenericPropertySourceGenerator(AOTContext context, Collection<ActiveEnvironment> environments) {
        List<String> resources = context.getConfiguration().stringList(RESOURCE_NAMES_OPTION.key());
        if (resources.isEmpty()) {
            resources = List.of(Environment.DEFAULT_NAME);
        }
        this.resources = resources;
        this.propertySourceLoaderTypes = context.getConfiguration().stringList(TYPES_OPTION.key());
        this.baseOrder = context.getConfiguration().optionalValue(BASE_ORDER_OPTION.key(),
            v -> v.map(Integer::parseInt).orElse(Ordered.HIGHEST_PRECEDENCE / 2));
        this.environments = environments;
    }

    @Override
    public void generate(@NonNull AOTContext context) {
        for (String propertySourceLoaderType : propertySourceLoaderTypes) {
            Optional<PropertySourceLoader> loader = createLoader(propertySourceLoaderType);
            if (loader.isPresent()) {
                for (String resource : resources) {
                    createMapProperty(loader.get(), context, resource, null);
                    for (ActiveEnvironment environment : environments) {
                        createMapProperty(loader.get(), context, resource, environment);
                    }
                }
            }
        }
    }

    private Optional<PropertySourceLoader> createLoader(String className) {
        Class<? extends PropertySourceLoader> loaderClass;
        try {
            Class<?> classValue = Class.forName(className);
            if (PropertySourceLoader.class.isAssignableFrom(classValue)) {
                loaderClass = (Class<? extends PropertySourceLoader>) classValue;
            } else {
                LOG.error("The loader class {} does not extend AbstractPropertySourceLoader and cannot be used for loading properties with AOT", className);
                return Optional.empty();
            }
        } catch (ClassNotFoundException e) {
            LOG.error("Could not load the loader class: {} ({})", className, e.getMessage());
            return Optional.empty();
        }

        Constructor<? extends PropertySourceLoader> defaultConstructor = null;
        for (Constructor<?> constructor: loaderClass.getDeclaredConstructors()) {
            if (constructor.getParameters().length == 0) {
                defaultConstructor = (Constructor<? extends PropertySourceLoader>) constructor;
            }
        }
        if (defaultConstructor == null) {
            LOG.error("Did not find a default constructor for the loader class: {}", className);
            return Optional.empty();
        }
        try {
            return Optional.of(defaultConstructor.newInstance());
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            LOG.error("Could not initialize an instance of the class: {} ({})", className, e.getMessage());
            return Optional.empty();
        }
    }

    private void createMapProperty(PropertySourceLoader loader, AOTContext context, String resource, @Nullable ActiveEnvironment environment) {
        Optional<PropertySource> optionalSource;
        if (environment != null) {
            optionalSource = loader.loadEnv(resource, new DefaultClassPathResourceLoader(this.getClass().getClassLoader()), environment);
        } else {
            optionalSource = loader.load(resource, new DefaultClassPathResourceLoader(this.getClass().getClassLoader()));
        }

        if (optionalSource.isPresent()) {
            LOG.info("Converting {} into Java based configuration with loader {}", resource, loader.getClass().getSimpleName());

            for (String extension: loader.getExtensions()) {
                context.registerExcludedResource(resource + (environment == null ? "" : "-" + environment.getName()) + "." + extension);
            }

            PropertySource ps = optionalSource.get();
            if (ps instanceof MapPropertySource mps) {
                Map<String, Object> values = mps.asMap();
                var generator = new MapPropertySourceGenerator(
                    getNamePrefix(loader),
                    resource + (environment == null ? "" : StringUtils.capitalize(environment.getName())),
                    values,
                    mps.getOrder() + baseOrder);
                generator.generate(context);
            } else if (!(ps instanceof EmptyPropertySource)) {
                throw new UnsupportedOperationException("Unknown property source type:" + ps.getClass());
            }
        }
    }

    private String getNamePrefix(PropertySourceLoader loader) {
        String namePrefix = loader.getClass().getSimpleName();
        if (namePrefix.endsWith(PropertySourceLoader.class.getSimpleName())) {
            namePrefix = namePrefix.substring(0, namePrefix.length() - PropertySourceLoader.class.getSimpleName().length());
        }
        return namePrefix;
    }

}
