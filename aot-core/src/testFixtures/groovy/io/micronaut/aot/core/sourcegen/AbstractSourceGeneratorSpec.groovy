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
package io.micronaut.aot.core.sourcegen

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import groovy.transform.CompileStatic
import io.micronaut.aot.core.AOTSourceGenerator
import io.micronaut.aot.core.Configuration
import io.micronaut.aot.core.config.DefaultConfiguration
import io.micronaut.aot.core.context.ApplicationContextAnalyzer
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

@CompileStatic
abstract class AbstractSourceGeneratorSpec extends Specification {
    final String packageName = "io.micronaut.test"

    @TempDir
    Path testDirectory

    final Properties props = new Properties()
    final Configuration config = new DefaultConfiguration(props)
    private GeneratedSources generatedSources

    DefaultSourceGenerationContext context = new DefaultSourceGenerationContext(packageName, ApplicationContextAnalyzer.create(), config)

    abstract AOTSourceGenerator newGenerator()

    void generate() {
        def sourceGenerator = newGenerator()
        sourceGenerator.init(context)
        def sources = sourceGenerator.generateSourceFiles().collectEntries([:]) {
            def writer = new StringWriter()
            it.writeTo(writer)
            [it, writer.toString()]
        }
        def init = sourceGenerator.generateStaticInit()
        def resourcesDir = testDirectory.resolve("resources").toFile()
        sourceGenerator.generateResourceFiles(resourcesDir)
        this.generatedSources = new GeneratedSources(sources, init, resourcesDir)
    }

    void assertThatGeneratedSources(@DelegatesTo(value=GeneratedSources, strategy = Closure.DELEGATE_FIRST) Closure<?> spec) {
        if (generatedSources == null) {
            throw new AssertionError("Couldn't find generated sources. Did you call 'generateSources()'?")
        }
        spec.delegate = generatedSources
        spec.resolveStrategy = Closure.DELEGATE_FIRST
        spec()
        def verified = generatedSources.verifiedClasses
        def missing = generatedSources.generatedClasses - verified
        if (missing) {
            throw new AssertionError("Too few assertions. You also need to verify classes: $missing")
        }
        if (!generatedSources.checkedForInitializer) {
            throw new AssertionError("Too few assertions. You also need to check the generated initializer")

        }
    }

    void excludesResources(String... resources) {
        Set<String> expectedResources = resources as Set
        // cast is workaround for Groovy compiler bug
        assert ((DefaultSourceGenerationContext)context).excludedResources == expectedResources
    }

    class GeneratedSources {
        private final Map<JavaFile, String> sources
        private final Optional<MethodSpec> init
        private final Set<String> verifiedClasses = []
        private final File resourcesDir
        private boolean checkedForInitializer

        GeneratedSources(Map<JavaFile, String> sources, Optional<MethodSpec> init, File resourcesDir) {
            this.sources = sources
            this.init = init
            this.resourcesDir = resourcesDir
        }

        void doesNotGenerateClasses() {
            assert sources.isEmpty()
        }

        void hasInitializer() {
            checkedForInitializer = true
            assert init.present
        }

        void doesNotCreateInitializer() {
            checkedForInitializer = true
            assert !init.present
        }

        void createsInitializer(String expectedContents) {
            hasInitializer()
            init.ifPresent { spec ->
                String actualCode = spec.toString().trim()
                expectedContents = expectedContents.trim()
                assert actualCode == expectedContents
            }
        }

        void hasClass(String name, @DelegatesTo(value=JavaFileAssertions, strategy = Closure.DELEGATE_FIRST) Closure<?> spec) {
            String fqn = "${packageName}.${name}"
            def javaFile = sources.find {
                def key = it.key
                "${key.packageName}.${key.typeSpec.name}".toString().equals(fqn)
            }
            if (javaFile == null) {
                String available = sources.keySet().collect { JavaFile key ->
                    "${key.packageName}.${key.typeSpec.name}".toString()
                }
                throw new AssertionError("Expected to find a generated source class '$fqn' but it wasn't found. Generated classes: ${available}")
            }
            verifiedClasses << fqn
            def helper = new JavaFileAssertions(
                    javaFile.key,
                    javaFile.value
            )
            spec.delegate = helper
            spec.resolveStrategy = Closure.DELEGATE_FIRST
            spec()
            if (!helper.checkedSources) {
                throw new AssertionError("Too few assertions. You also need to check the generated code.")
            }
        }

        void generatesServiceFile(Class<?> serviceType, String... implementationTypes) {
            generatesServiceFile(serviceType.name, implementationTypes)
        }

        void generatesServiceFile(String serviceType, String... implementationTypes) {
            def serviceFile = new File(resourcesDir, "META-INF/services/$serviceType")
            assert serviceFile.exists()
            Set<String> actualImplementationTypes = serviceFile.readLines().findAll() as Set<String>
            Set<String> expectedImplementationTypes = implementationTypes as Set<String>
            assert actualImplementationTypes == expectedImplementationTypes
        }

        void generatesMetaInfResource(String path, String contents) {
            def file = new File(resourcesDir, "META-INF/$path")
            assert file.exists()
            def actualContents = file.text.trim()
            def expectedContents = contents.trim()
            if (actualContents != expectedContents) {
                println "ACTUAL CONTENTS"
                println "---------------"
                println actualContents

                println "EXPECTED CONTENTS"
                println "-----------------"
                println expectedContents
            }
            assert actualContents == expectedContents
        }

        Set<String> getGeneratedClasses() {
            sources.keySet().collect { key ->
                "${key.packageName}.${key.typeSpec.name}".toString()
            } as Set<String>
        }
    }

    static class JavaFileAssertions {
        private final JavaFile javaFile
        private final String generatedSource
        private boolean checkedSources

        JavaFileAssertions(JavaFile javaFile, String sources) {
            this.javaFile = javaFile
            this.generatedSource = sources.trim()
        }

        void withSources(String expectedSource) {
            checkedSources = true
            expectedSource = expectedSource.trim()
            if (generatedSource != expectedSource) {
                println("GENERATED")
                println("=========")
                println(generatedSource)
                println("EXPECTED")
                println("========")
                println(expectedSource)
            }
            assert generatedSource == expectedSource
        }

        void containingSources(String expected) {
            checkedSources = true
            assert generatedSource.contains(expected)
        }
    }
}
