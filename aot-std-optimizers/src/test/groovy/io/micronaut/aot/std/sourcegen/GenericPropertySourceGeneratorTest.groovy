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
package io.micronaut.aot.std.sourcegen

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.context.env.ActiveEnvironment
import io.micronaut.context.env.Environment
import io.micronaut.context.env.MapPropertySource
import io.micronaut.context.env.PropertiesPropertySourceLoader
import io.micronaut.context.env.PropertySource
import io.micronaut.context.env.PropertySourceLoader
import io.micronaut.core.io.ResourceLoader
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory

class GenericPropertySourceGeneratorTest extends AbstractSourceGeneratorSpec {

    @Override
    AOTCodeGenerator newGenerator() {
        props.put(GenericPropertySourceGenerator.TYPES_OPTION.key(),
                [MyPropertySourceLoader.class.getName(), PropertiesPropertySourceLoader.class.getName()].join(","))
        new GenericPropertySourceGenerator(context, [ActiveEnvironment.of(Environment.TEST, 0)])
    }

    def "generates classes from a configuration"() {
        when:
        generate()

        then:
        var excludedImpls = context.getExcludedServiceImplementations()
        excludedImpls.keySet() == [MyPropertySourceLoader.class.getName(), PropertiesPropertySourceLoader.class.getName()] as Set

        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("ApplicationMyStaticPropertySource") {
                withSources """
                package io.micronaut.test;

                import io.micronaut.context.env.MapPropertySource;
                import io.micronaut.core.annotation.Generated;
                import java.util.HashMap;

                @Generated
                public class ApplicationMyStaticPropertySource extends MapPropertySource {
                  ApplicationMyStaticPropertySource() {
                    super("application", new HashMap() {{
                        put("language.short", "en");
                        put("greeting", "Hello");
                        put("numRepeat", 2);
                        }});
                  }

                  public int getOrder() {
                    return -1073741824;
                  }
                }
                """.stripIndent()
            }

            hasClass("ApplicationTestMyStaticPropertySource") {
                withSources """
                package io.micronaut.test;

                import io.micronaut.context.env.MapPropertySource;
                import io.micronaut.core.annotation.Generated;
                import java.util.HashMap;

                @Generated
                public class ApplicationTestMyStaticPropertySource extends MapPropertySource {
                  ApplicationTestMyStaticPropertySource() {
                    super("application-test", new HashMap() {{
                        put("language.short", "fr");
                        put("greeting", "Bonjour");
                        }});
                  }

                  public int getOrder() {
                    return -1073741823;
                  }
                }
                """.stripIndent()
            }

            hasClass("ApplicationTestPropertiesStaticPropertySource") {
                withSources """
                package io.micronaut.test;

                import io.micronaut.context.env.MapPropertySource;
                import io.micronaut.core.annotation.Generated;
                import java.util.HashMap;

                @Generated
                public class ApplicationTestPropertiesStaticPropertySource extends MapPropertySource {
                  ApplicationTestPropertiesStaticPropertySource() {
                    super("application-test", new HashMap() {{
                        put("my.property.environment", "test");
                        }});
                  }

                  public int getOrder() {
                    return -1073742123;
                  }
                }
                """.stripIndent()
            }
        }
    }

    def "keeps the configuration files and warns when sealed.property.source is disabled"() {
        given:
        def logger = (Logger) LoggerFactory.getLogger(GenericPropertySourceGenerator)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        when:
        generate()

        then: "nothing registers the generated classes, so the files must stay"
        excludesResources()
        appender.list.any {
            it.level == Level.WARN && it.formattedMessage.startsWith("The property sources generated by property-source-loader.generate are not used")
        }

        cleanup:
        logger.detachAppender(appender)
    }

    /**
     * Reads the application resource and, when it is listed in property-source-loader.resource-names,
     * the {@link #OTHER_RESOURCE} resource, each with values for the test environment.
     */
    @Singleton
    static class MyPropertySourceLoader implements PropertySourceLoader {

        static final String OTHER_RESOURCE = "other"

        MyPropertySourceLoader() {
        }

        @Override
        Optional<PropertySource> load(String resourceName, ResourceLoader resourceLoader) {
            if (resourceName == Environment.DEFAULT_NAME) {
                return Optional.of(new MapPropertySource(resourceName,
                        ["language.short": "en", "greeting": "Hello", "numRepeat": 2]
                ))
            }
            if (resourceName == OTHER_RESOURCE) {
                return Optional.of(new MapPropertySource(resourceName, ["other.greeting": "Hi"]))
            }
            return Optional.empty()
        }

        @Override
        Optional<PropertySource> loadEnv(String resourceName, ResourceLoader resourceLoader, ActiveEnvironment activeEnvironment) {
            if (activeEnvironment.name != Environment.TEST) {
                return Optional.empty()
            }
            Map<String, Object> values
            if (resourceName == Environment.DEFAULT_NAME) {
                values = ["language.short": "fr", "greeting": "Bonjour"]
            } else if (resourceName == OTHER_RESOURCE) {
                values = ["other.greeting": "Salut"]
            } else {
                return Optional.empty()
            }
            return Optional.of(new MapPropertySource(resourceName, values) {
                @Override
                int getOrder() {
                    return 1 + activeEnvironment.getPriority()
                }
            })
        }

        @Override
        Map<String, Object> read(String name, InputStream input) throws IOException {
            return null
        }

        @Override
        Set<String> getExtensions() {
            return ["my"]
        }
    }

}
