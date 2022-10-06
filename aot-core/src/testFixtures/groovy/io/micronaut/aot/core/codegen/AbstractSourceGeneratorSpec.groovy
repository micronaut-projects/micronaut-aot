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
package io.micronaut.aot.core.codegen

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import groovy.transform.CompileStatic
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.Configuration
import io.micronaut.aot.core.config.DefaultConfiguration
import io.micronaut.aot.core.context.ApplicationContextAnalyzer
import io.micronaut.aot.core.context.DefaultSourceGenerationContext
import io.micronaut.context.ApplicationContextBuilder
import spock.lang.Specification
import spock.lang.TempDir

import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Function
import java.util.stream.Collectors

@CompileStatic
abstract class AbstractSourceGeneratorSpec extends Specification {
    final String packageName = "io.micronaut.test"

    @TempDir
    Path testDirectory

    final Properties props = new Properties()
    final Configuration config = new DefaultConfiguration(props)
    private GeneratedSources generatedSources
    Path resourcesDir

    DefaultSourceGenerationContext context

    def setup() {
        resourcesDir = testDirectory.resolve("resources")
        context = new DefaultSourceGenerationContext(packageName, ApplicationContextAnalyzer.create { customizeContext(it) }, config, resourcesDir)
    }

    protected void customizeContext(ApplicationContextBuilder builder) {

    }

    abstract AOTCodeGenerator newGenerator()

    void generate() {
        def sourceGenerator = newGenerator()
        sourceGenerator.generate(context)
        def sources = context.getGeneratedJavaFiles().collectEntries([:]) {
            def writer = new StringWriter()
            it.writeTo(writer)
            [it, writer.toString()]
        }

        this.generatedSources = new GeneratedSources(sources as Map<JavaFile, String>, context.getGeneratedStaticInitializers(), resourcesDir.toFile())
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
        private final List<MethodSpec> init
        private final Set<String> verifiedClasses = []
        private final File resourcesDir
        private boolean checkedForInitializer

        GeneratedSources(Map<JavaFile, String> sources, List<MethodSpec> init, File resourcesDir) {
            this.sources = sources
            this.init = init
            this.resourcesDir = resourcesDir
        }

        void doesNotGenerateClasses() {
            assert sources.isEmpty()
        }

        void hasInitializer() {
            checkedForInitializer = true
            assert !init.isEmpty()
        }

        void hasInitializers(int count) {
            checkedForInitializer = true
            assert init.size() == count
        }

        void doesNotCreateInitializer() {
            checkedForInitializer = true
            assert init.isEmpty()
        }

        void createsInitializer(String expectedContents) {
            createsInitializer(0, expectedContents)
        }

        void createsInitializer(int index, String expectedContents) {
            hasInitializer()
            init[index].with { spec ->
                String actualCode = normalize(spec)
                expectedContents = normalize(expectedContents)
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
            def actualContents = normalize(file.text)
            def expectedContents = normalize(contents)
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

        void compiles(List<File> classpath = []) {
            def compiler = ToolProvider.getSystemJavaCompiler()
            def ds = new DiagnosticCollector<>()
            try (def mgr = compiler.getStandardFileManager(ds, null, null)) {
                def fullClasspath = System.getProperty("java.class.path").split(File.pathSeparator).collect {
                    new File(it)
                }.findAll { it.name.endsWith(".jar") } + classpath
                def sourceDir = testDirectory.resolve("sources").toFile()
                def output = testDirectory.resolve("compiled").toFile()
                List<String> options = compilerOptions(output, fullClasspath)
                sources.keySet().each {
                    it.writeTo(sourceDir)
                }
                def filesToCompile = Files.walk(sourceDir.toPath()).filter {
                    it.toFile().isFile() && it.toFile().name.endsWith(".java")
                }.map {
                    it.toFile()
                }.collect(Collectors.toList())
                if (output.mkdirs()) {
                    def sources = mgr.getJavaFileObjectsFromFiles(filesToCompile)
                    def task = compiler.getTask(null, mgr, ds, options, null, sources)
                    task.call()
                }
            } catch (IOException e) {
                throw new RuntimeException("Unable to compile generated classes", e)
            }
            def diagnostics = ds.diagnostics
                    .findAll { d -> d.kind == Diagnostic.Kind.ERROR }
            if (!diagnostics.isEmpty()) {
                diagnostics.each {
                    println "ERROR: ${((Diagnostic)it).getMessage(Locale.ENGLISH)}"
                }
                throw new AssertionError("Expected sources to compile but they didn't")
            }
        }

        private static List<String> compilerOptions(File dstDir,
                                                    List<File> classPath) {
            def options = new ArrayList<String>()
            options.add("-source")
            options.add("1.8")
            options.add("-target")
            options.add("1.8")
            options.add("-classpath")
            String cp = classPath.collect { it.absolutePath }.join(File.pathSeparator)
            options.add(cp)
            options.add("-d")
            options.add(dstDir.getAbsolutePath())
            options
        }
    }

    static class JavaFileAssertions {
        private final JavaFile javaFile
        private final String generatedSource
        private boolean checkedSources
        private Function<String, String> normalizer = { it }

        JavaFileAssertions(JavaFile javaFile, String sources) {
            this.javaFile = javaFile
            this.generatedSource = normalize(sources)
        }

        void withNormalizer(Function<String, String> normalizer) {
            this.normalizer = normalizer
        }

        void withSources(String expectedSource) {
            checkedSources = true
            expectedSource = normalizer.apply(normalize(expectedSource))
            String actualSources = normalizer.apply(generatedSource)
            if (actualSources != expectedSource) {
                println("GENERATED")
                println("=========")
                println(actualSources)
                println("EXPECTED")
                println("========")
                println(expectedSource)
            }
            assert actualSources == expectedSource
        }

        void containingSources(String expected) {
            checkedSources = true
            assert generatedSource.contains(expected)
        }

        void doesNotContainSources(String missing) {
            checkedSources = true
            assert !generatedSource.contains(missing)
        }
    }

    static String normalize(Object input) {
        input.toString().trim().replaceAll("\\r", "")
    }


}
