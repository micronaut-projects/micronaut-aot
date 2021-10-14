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
package io.micronaut.aot.internal.sourcegen

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import groovy.transform.CompileStatic
import spock.lang.Specification

@CompileStatic
abstract class AbstractSourceGeneratorSpec extends Specification {
    final String packageName = "io.micronaut.test"

    private GeneratedSources generatedSources

    SourceGenerationContext context = new SourceGenerationContext(newClassLoader(), packageName)

    private URLClassLoader newClassLoader(URL... urls) {
        new URLClassLoader(urls, this.class.classLoader)
    }

    abstract SourceGenerator newGenerator()

    void generate() {
        def sourceGenerator = newGenerator()
        sourceGenerator.init()
        def sources = sourceGenerator.generateSourceFiles().collectEntries([:]) {
            def writer = new StringWriter()
            it.writeTo(writer)
            [it, writer.toString()]
        }
        def init = sourceGenerator.generateStaticInit()
        this.generatedSources = new GeneratedSources(sources, init)
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
        assert ((SourceGenerationContext)context).excludedResources == expectedResources
    }

    class GeneratedSources {
        private final Map<JavaFile, String> sources
        private final Optional<MethodSpec> init
        private final Set<String> verifiedClasses = []
        private boolean checkedForInitializer

        GeneratedSources(Map<JavaFile, String> sources, Optional<MethodSpec> init) {
            this.sources = sources
            this.init = init
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
