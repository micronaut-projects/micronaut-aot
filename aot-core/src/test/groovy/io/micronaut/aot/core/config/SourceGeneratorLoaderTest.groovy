/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package io.micronaut.aot.core.config

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.AOTContext
import io.micronaut.aot.core.AOTModule
import io.micronaut.aot.core.Option
import io.micronaut.aot.core.Runtime
import io.micronaut.aot.core.context.ApplicationContextAnalyzer
import io.micronaut.aot.core.context.DefaultSourceGenerationContext
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Path

class SourceGeneratorLoaderTest extends Specification {

    @TempDir
    Path testDirectory

    def "sorts missing metadata selections after metadata-backed selections"() {
        given:
        def first = selection(new FirstGenerator(), false)
        def second = selection(new SecondGenerator(), true)
        def missing = new SourceGeneratorSelection(new MissingMetadataGenerator(), null, false, SourceGeneratorSelection.MISSING_METADATA)

        when:
        def sorted = [missing, second, first].sort(SourceGeneratorLoader::compareSelections)

        then:
        sorted*.generator*.class == [FirstGenerator, SecondGenerator, MissingMetadataGenerator]
    }

    @Unroll
    def "deprecated yaml generation flag #property enables property source loader generator"() {
        given:
        def props = new Properties()
        props.put(property, "true")
        def config = new DefaultConfiguration(props)
        def context = new DefaultSourceGenerationContext(
                "io.micronaut.test",
                ApplicationContextAnalyzer.create { },
                config,
                testDirectory
        )

        expect:
        SourceGeneratorLoader.load(Runtime.JIT, context).any {
            it instanceof DeprecatedYamlPropertySourceGenerator
        }

        where:
        property << ["yaml.to.java.config", "yaml.to.java.config.enabled"]
    }

    def "property source loader generator remains disabled without feature flags"() {
        given:
        def config = new DefaultConfiguration(new Properties())
        def context = new DefaultSourceGenerationContext(
                "io.micronaut.test",
                ApplicationContextAnalyzer.create { },
                config,
                testDirectory
        )

        expect:
        !SourceGeneratorLoader.load(Runtime.JIT, context).any {
            it instanceof DeprecatedYamlPropertySourceGenerator
        }
    }

    private static SourceGeneratorSelection selection(AOTCodeGenerator generator, boolean enabled) {
        def module = generator.class.getAnnotation(AOTModule)
        new SourceGeneratorSelection(generator, module, enabled, enabled ? null : SourceGeneratorSelection.DISABLED_BY_CONFIGURATION)
    }

    @AOTModule(id = "first")
    private static class FirstGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }

    @AOTModule(id = "second", dependencies = ["first"])
    private static class SecondGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }

    private static class MissingMetadataGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }

    @AOTModule(
            id = "property-source-loader.generate",
            options = @Option(
                    key = "yaml.to.java.config.enabled",
                    description = "Deprecated option to enable the yaml property source generation.",
                    sampleValue = "false"
            )
    )
    static class DeprecatedYamlPropertySourceGenerator implements AOTCodeGenerator {

        @Override
        void generate(AOTContext context) {
        }
    }
}
