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

class GenericPropertySourceGeneratorTest extends AbstractSourceGeneratorSpec {

    @Override
    AOTCodeGenerator newGenerator() {
        props.put(GenericPropertySourceGenerator.TYPES_OPTION.key(),
                [MyPropertySourceLoader.class.getName(), PropertiesPropertySourceLoader.class.getName()].join(","))
        new GenericPropertySourceGenerator([Environment.DEFAULT_NAME], [ActiveEnvironment.of(Environment.TEST, 0)])
    }

    def "generates classes from a configuration"() {
        when:
        generate()

        then:
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
                    super("applicationTest", new HashMap() {{
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
                    super("applicationTest", new HashMap() {{
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

    @Singleton
    static class MyPropertySourceLoader implements PropertySourceLoader {

        MyPropertySourceLoader() {
        }

        @Override
        Optional<PropertySource> load(String resourceName, ResourceLoader resourceLoader) {
            if (resourceName == Environment.DEFAULT_NAME) {
                return Optional.of(new MapPropertySource(resourceName,
                        ["language.short": "en", "greeting": "Hello", "numRepeat": 2]
                ))
            }
            return Optional.empty()
        }

        @Override
        Optional<PropertySource> loadEnv(String resourceName, ResourceLoader resourceLoader, ActiveEnvironment activeEnvironment) {
            if (resourceName == Environment.DEFAULT_NAME && activeEnvironment.name == Environment.TEST) {
                return Optional.of(new MapPropertySource(resourceName, ["language.short": "fr", "greeting": "Bonjour"]) {
                    @Override
                    int getOrder() {
                        return 1 + activeEnvironment.getPriority()
                    }
                })
            }
            return Optional.empty()
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
