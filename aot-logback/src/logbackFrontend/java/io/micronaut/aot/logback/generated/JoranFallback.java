/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.aot.logback.generated;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Every path of the generated {@code LogbackConfigurator} that hands the configuration over to Joran.
 *
 * <p>The precompiler copies this class into the application byte for byte, next to the configurator it generates.
 * It is a class of its own so that the configurator's literal path never loads or links Joran: only a fallback
 * touches it. It uses Logback and {@code java.base} only, no lambdas and no invokedynamic, so it runs on any class
 * loader.</p>
 *
 * <p>It is package-private, and so are its methods: only the configurator calls them, so application code cannot.
 * That needs the configurator and this class on the same class loader, which they are, as the precompiler emits
 * them together.</p>
 */
final class JoranFallback {

    private JoranFallback() {
    }

    /**
     * Configures the context the way Logback does without a {@code Configurator} service: the
     * {@code logback.configurationFile} system property, then {@code logback-test.xml}, then {@code logback.xml}.
     *
     * @param context the context to configure
     * @return what Logback's own default configurator returns
     */
    static Configurator.ExecutionStatus defaultLookup(LoggerContext context) {
        DefaultJoranConfigurator configurator = new DefaultJoranConfigurator();
        configurator.setContext(context);
        return configurator.configure(context);
    }

    /**
     * Configures the context from one location, as Micronaut's {@code LogbackUtils} does when no
     * {@code Configurator} service is registered: a class path resource, then a file.
     *
     * @param context  the context to configure
     * @param location the location, such as the value of {@code logger.config}
     * @return {@link Configurator.ExecutionStatus#DO_NOT_INVOKE_NEXT_IF_ANY}
     * @throws IllegalStateException if nothing is found at the location or Joran cannot read it
     */
    static Configurator.ExecutionStatus location(LoggerContext context, String location) {
        URL resource = JoranFallback.class.getClassLoader().getResource(location);
        if (resource == null) {
            File file = new File(location);
            if (file.exists()) {
                try {
                    resource = file.toURI().toURL();
                } catch (MalformedURLException e) {
                    throw new IllegalStateException("Error creating URL for off-classpath resource", e);
                }
            }
        }
        if (resource == null) {
            System.err.println("ERROR: Logback configuration file ".concat(location).concat(" not found"));
            throw new IllegalStateException("Resource ".concat(location).concat(" not found"));
        }
        DefaultJoranConfigurator configurator = new DefaultJoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.configureByResource(resource);
        } catch (JoranException e) {
            throw new IllegalStateException("Error while refreshing Logback", e);
        }
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
    }
}
