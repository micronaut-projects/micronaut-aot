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
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.yaml.YamlPropertySourceLoader;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.io.scan.DefaultClassPathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * A source generator which will generate a static {@link io.micronaut.context.env.PropertySource}
 * from a given YAML configuration file, in order to substitute the dynamic loader
 * with a static configuration.
 */
@AOTModule(
    id = YamlPropertySourceGenerator.ID,
    description = YamlPropertySourceGenerator.DESCRIPTION
)
public class YamlPropertySourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "yaml.to.java.config";
    public static final String DESCRIPTION = "Converts YAML configuration files to Java configuration";
    private static final Logger LOGGER = LoggerFactory.getLogger(YamlPropertySourceGenerator.class);

    private final Collection<String> resources;

    public YamlPropertySourceGenerator(Collection<String> resources) {
        this.resources = resources;
    }

    @Override
    public void generate(@NonNull AOTContext context) {
        var loader = new YamlPropertySourceLoader();
        for (String resource : resources) {
            createMapProperty(loader, context, resource);
        }
    }

    private void createMapProperty(YamlPropertySourceLoader loader, AOTContext context, String resource) {
        Optional<PropertySource> optionalSource = loader.load(resource, new DefaultClassPathResourceLoader(this.getClass().getClassLoader()));
        if (optionalSource.isPresent()) {
            LOGGER.info("Converting {} into Java based configuration", resource + ".yml");
            context.registerExcludedResource(resource + ".yml");
            context.registerExcludedResource(resource + ".yaml");
            PropertySource ps = optionalSource.get();
            if (ps instanceof MapPropertySource mps) {
                Map<String, Object> values = mps.asMap();
                var generator = new MapPropertySourceGenerator(
                    resource,
                    values);
                generator.generate(context);
            } else {
                throw new UnsupportedOperationException("Unknown property source type:" + ps.getClass());
            }
        }
    }

}
