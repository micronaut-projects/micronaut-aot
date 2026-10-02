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
import io.micronaut.aot.core.AOTContext
import io.micronaut.aot.core.AOTModule
import io.micronaut.aot.core.Runtime
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.aot.core.config.SourceGeneratorLoader
import io.micronaut.context.ApplicationContextBuilder
import io.micronaut.context.env.ConstantPropertySources
import io.micronaut.context.env.Environment
import io.micronaut.context.env.PropertiesPropertySourceLoader
import io.micronaut.context.env.PropertySource
import io.micronaut.core.optim.StaticOptimizations
import org.slf4j.LoggerFactory

class ConstantPropertySourcesSourceGeneratorTest extends AbstractSourceGeneratorSpec {

    private static final String LOADER_PREFIX = """package io.micronaut.test;

import io.micronaut.context.env.ConstantPropertySources;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.optim.StaticOptimizations;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;

public class AotConstantPropertySources implements StaticOptimizations.Loader<ConstantPropertySources> {
  @Override
  public ConstantPropertySources load() {
    List<PropertySource> propertySources = new ArrayList<PropertySource>();
"""

    private static final String LOADER_SUFFIX = """    return new ConstantPropertySources(propertySources);
  }
}"""

    /**
     * A resource that only {@link GenericPropertySourceGeneratorTest.MyPropertySourceLoader} reads.
     * The application resource is also read by PropertiesPropertySourceLoader, from the
     * application-test.properties file of the test classpath.
     */
    private static final String OTHER = GenericPropertySourceGeneratorTest.MyPropertySourceLoader.OTHER_RESOURCE

    /**
     * The property source loaders to convert. When empty, only the
     * {@link ConstantPropertySourcesSourceGenerator} runs.
     */
    private List<Class<?>> loaderTypes = []

    private final List<Runnable> detachAppenders = []

    @Override
    protected void customizeContext(ApplicationContextBuilder builder) {
        builder.deduceEnvironment(false).environments(Environment.TEST)
    }

    def cleanup() {
        detachAppenders*.run()
    }

    @Override
    AOTCodeGenerator newGenerator() {
        if (loaderTypes.isEmpty()) {
            return new ConstantPropertySourcesSourceGenerator()
        }
        props.put(GenericPropertySourceGenerator.ID + ".enabled", "true")
        props.put(ConstantPropertySourcesSourceGenerator.ID + ".enabled", "true")
        props.put(GenericPropertySourceGenerator.TYPES_OPTION.key(), loaderTypes*.name.join(","))
        // Selected and ordered the way the optimizer does it
        List<AOTCodeGenerator> generators = SourceGeneratorLoader.load(Runtime.JIT, context)
        assert generators*.class == [GenericPropertySourceGenerator, ConstantPropertySourcesSourceGenerator]
        return { AOTContext ctx -> generators.each { it.generate(ctx) } } as AOTCodeGenerator
    }

    def "generates an empty list when no property source was generated"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("AotConstantPropertySources") {
                withSources LOADER_PREFIX + LOADER_SUFFIX
            }
        }
        excludesResources()
    }

    def "runs after the property source loader generator"() {
        expect:
        ConstantPropertySourcesSourceGenerator.getAnnotation(AOTModule).dependencies() == [GenericPropertySourceGenerator.ID] as String[]
    }

    def "registers the generated property sources and excludes the files they replace"() {
        given: "only MyPropertySourceLoader reads the other resource"
        loaderTypes = [GenericPropertySourceGeneratorTest.MyPropertySourceLoader]
        resourceNames(OTHER)
        def genericLogs = captureLogs(GenericPropertySourceGenerator)
        def constantLogs = captureLogs(ConstantPropertySourcesSourceGenerator)

        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("OtherMyStaticPropertySource") {
                containingSources 'super("other", '
            }
            hasClass("OtherTestMyStaticPropertySource") {
                containingSources 'super("other-test", '
            }
            hasClass("AotConstantPropertySources") {
                withSources LOADER_PREFIX + """    propertySources.add(new OtherMyStaticPropertySource());
    propertySources.add(new OtherTestMyStaticPropertySource());
""" + LOADER_SUFFIX
            }
        }
        excludesResources("other.my", "other-test.my")
        warnings(genericLogs).empty
        warnings(constantLogs).empty
    }

    def "only keeps the files of the application resource when #reason"() {
        given: "only MyPropertySourceLoader reads the other resource"
        loaderTypes = types
        resourceNames(Environment.DEFAULT_NAME, OTHER)
        def logs = captureLogs(ConstantPropertySourcesSourceGenerator)

        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("ApplicationMyStaticPropertySource") {
                containingSources 'super("application", '
            }
            hasClass("ApplicationTestMyStaticPropertySource") {
                containingSources 'super("application-test", '
            }
            if (PropertiesPropertySourceLoader in types) {
                hasClass("ApplicationTestPropertiesStaticPropertySource") {
                    containingSources 'super("application-test", '
                }
            }
            hasClass("OtherMyStaticPropertySource") {
                containingSources 'super("other", '
            }
            hasClass("OtherTestMyStaticPropertySource") {
                containingSources 'super("other-test", '
            }
            hasClass("AotConstantPropertySources") {
                withSources LOADER_PREFIX + """    propertySources.add(new OtherMyStaticPropertySource());
    propertySources.add(new OtherTestMyStaticPropertySource());
""" + LOADER_SUFFIX
            }
        }
        excludesResources("other.my", "other-test.my")
        warnings(logs) == ["Keeping the configuration files of [application]: " + warning]

        where:
        reason                                                                 | types                                                                               | warning
        "two loaders produce application-test"                                 | [GenericPropertySourceGeneratorTest.MyPropertySourceLoader, PropertiesPropertySourceLoader] | "more than one property source loader produced [application-test]"
        "a loader outside property-source-loader.types reads one of its files" | [GenericPropertySourceGeneratorTest.MyPropertySourceLoader]                         | "[application-test.properties] are read by property source loaders that are not listed in property-source-loader.types"
    }

    def "the compiled loader provides the property sources that Micronaut looks up"() {
        given:
        loaderTypes = [GenericPropertySourceGeneratorTest.MyPropertySourceLoader]
        resourceNames(OTHER)
        generate()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("OtherMyStaticPropertySource") {
                containingSources 'super("other", '
            }
            hasClass("OtherTestMyStaticPropertySource") {
                containingSources 'super("other-test", '
            }
            hasClass("AotConstantPropertySources") {
                containingSources "propertySources.add("
                compiles()
            }
        }

        when: "the compiled loader is run, as StaticOptimizations does at startup"
        Map<String, PropertySource> sources = loadConstantPropertySources().collectEntries { [it.name, it] }

        then: "the names are the ones ConstantPropertySourceLoader looks up: <resource> and <resource>-<environment>"
        sources.keySet() == ["other", "other-test"] as Set
        sources["other"].get("other.greeting") == "Hi"
        sources["other-test"].get("other.greeting") == "Salut"
        sources["other-test"].order > sources["other"].order
    }

    private void resourceNames(String... names) {
        props.put(GenericPropertySourceGenerator.RESOURCE_NAMES_OPTION.key(), names.join(","))
    }

    private ListAppender<ILoggingEvent> captureLogs(Class<?> type) {
        def logger = (Logger) LoggerFactory.getLogger(type)
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        detachAppenders << ({ logger.detachAppender(appender) } as Runnable)
        appender
    }

    private static List<String> warnings(ListAppender<ILoggingEvent> logs) {
        logs.list.findAll { it.level == Level.WARN }*.formattedMessage
    }

    private List<PropertySource> loadConstantPropertySources() {
        def compiled = testDirectory.resolve("compiled").toUri().toURL()
        try (def loader = new URLClassLoader([compiled] as URL[], getClass().classLoader)) {
            def loaderClass = loader.loadClass("${packageName}.AotConstantPropertySources")
            def optimizationsLoader = (StaticOptimizations.Loader<ConstantPropertySources>) loaderClass.getDeclaredConstructor().newInstance()
            def constantPropertySources = optimizationsLoader.load()
            // getSources() is package-private in Micronaut core
            def getSources = ConstantPropertySources.getDeclaredMethod("getSources")
            getSources.accessible = true
            (List<PropertySource>) getSources.invoke(constantPropertySources)
        }
    }
}
