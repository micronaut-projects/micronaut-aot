package io.micronaut.aot.cli

import groovy.transform.CompileStatic
import io.micronaut.aot.std.sourcegen.AbstractStaticServiceLoaderSourceGenerator
import io.micronaut.aot.std.sourcegen.ConstantPropertySourcesSourceGenerator
import io.micronaut.aot.std.sourcegen.EnvironmentPropertiesSourceGenerator
import io.micronaut.aot.std.sourcegen.GraalVMOptimizationFeatureSourceGenerator
import io.micronaut.aot.std.sourcegen.KnownMissingTypesSourceGenerator
import io.micronaut.aot.std.sourcegen.LogbackConfigurationSourceGenerator
import io.micronaut.aot.std.sourcegen.PublishersSourceGenerator
import io.micronaut.aot.std.sourcegen.SealedEnvironmentSourceGenerator
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path

class CliTest extends Specification {
    @TempDir
    Path testDirectory

    @Unroll
    def "can export a dummy configuration file"() {
        def configFile = testDirectory.resolve("${runtime}.properties")
        def classpath = System.getProperty('aot.runtime')

        when:
        isolate {
            Main.main(
                    '--classpath', classpath,
                    '--optimizer-classpath', classpath,
                    '--runtime', runtime,
                    '--package', 'dummy',
                    '--config', configFile.toString()
            )
        }

        then:
        Files.exists(configFile)
        def config = normalize(configFile.toFile().text)
        String expected = normalize([
                runtime == 'native' ? [GraalVMOptimizationFeatureSourceGenerator.DESCRIPTION, "graalvm.config.enabled = true\n${GraalVMOptimizationFeatureSourceGenerator.OPTION.toPropertiesSample()}"] : null,
                [KnownMissingTypesSourceGenerator.DESCRIPTION, """known.missing.types.enabled = true
${KnownMissingTypesSourceGenerator.OPTION.toPropertiesSample()}"""],
                [LogbackConfigurationSourceGenerator.DESCRIPTION, 'logback.xml.to.java.enabled = true'],
                [EnvironmentPropertiesSourceGenerator.DESCRIPTION, 'precompute.environment.properties.enabled = true'],
                [PublishersSourceGenerator.DESCRIPTION, 'scan.reactive.types.enabled = true'],
                [SealedEnvironmentSourceGenerator.DESCRIPTION, 'sealed.environment.enabled = true'],
                [AbstractStaticServiceLoaderSourceGenerator.DESCRIPTION, """serviceloading.${runtime}.enabled = true
${AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES.toPropertiesSample()}
${AbstractStaticServiceLoaderSourceGenerator.REJECTED_CLASSES.toPropertiesSample()}"""],
                [ConstantPropertySourcesSourceGenerator.DESCRIPTION, "sealed.property.source.enabled = true"],
        ].findAll().collect { desc, c -> """# $desc
$c
""" }.join("\n").trim())

        println config
        config == expected

        where:
        runtime << ['jit', 'native']
    }

    static String normalize(Object input) {
        input.toString().trim().replaceAll("\\r", "")
    }

    @CompileStatic
    private static void isolate(Runnable r) {
        def cl = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = new ClassLoader(null) {
                @Override
                Class<?> loadClass(String name) throws ClassNotFoundException {
                    if (name.startsWith('java')) {
                        return cl.loadClass(name)
                    }
                    throw new ClassNotFoundException(name)
                }

                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (name.startsWith('java')) {
                        return cl.loadClass(name)
                    }
                    throw new ClassNotFoundException(name)
                }
            }
            r.run()
        } finally {
            Thread.currentThread().contextClassLoader = cl
        }
    }
}
