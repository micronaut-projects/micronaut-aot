package io.micronaut.aot.cli

import groovy.transform.CompileStatic
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.config.MetadataUtils
import io.micronaut.aot.std.sourcegen.AbstractStaticServiceLoaderSourceGenerator
import io.micronaut.aot.std.sourcegen.ConstantPropertySourcesSourceGenerator
import io.micronaut.aot.std.sourcegen.DeduceEnvironmentSourceGenerator
import io.micronaut.aot.std.sourcegen.EnvironmentPropertiesSourceGenerator
import io.micronaut.aot.std.sourcegen.GraalVMOptimizationFeatureSourceGenerator
import io.micronaut.aot.std.sourcegen.JitStaticServiceLoaderSourceGenerator
import io.micronaut.aot.std.sourcegen.KnownMissingTypesSourceGenerator
import io.micronaut.aot.std.sourcegen.LogbackConfigurationSourceGenerator
import io.micronaut.aot.std.sourcegen.PublishersSourceGenerator
import io.micronaut.aot.std.sourcegen.SealedEnvironmentSourceGenerator
import io.micronaut.aot.std.sourcegen.YamlPropertySourceGenerator
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
            Main.execute(
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
                [DeduceEnvironmentSourceGenerator.DESCRIPTION, "deduce.environment.enabled = true"],
                runtime == 'native' ? [GraalVMOptimizationFeatureSourceGenerator.DESCRIPTION, "graalvm.config.enabled = true\n${toPropertiesSample(GraalVMOptimizationFeatureSourceGenerator)}"] : null,
                [KnownMissingTypesSourceGenerator.DESCRIPTION, """known.missing.types.enabled = true
${toPropertiesSample(KnownMissingTypesSourceGenerator)}"""],
                [LogbackConfigurationSourceGenerator.DESCRIPTION, 'logback.xml.to.java.enabled = true'],
                [EnvironmentPropertiesSourceGenerator.DESCRIPTION, 'precompute.environment.properties.enabled = true'],
                [PublishersSourceGenerator.DESCRIPTION, 'scan.reactive.types.enabled = true'],
                [SealedEnvironmentSourceGenerator.DESCRIPTION, 'sealed.environment.enabled = true'],
                [AbstractStaticServiceLoaderSourceGenerator.DESCRIPTION, """serviceloading.${runtime}.enabled = true
${toPropertiesSample(JitStaticServiceLoaderSourceGenerator, AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES)}
${toPropertiesSample(JitStaticServiceLoaderSourceGenerator, AbstractStaticServiceLoaderSourceGenerator.REJECTED_CLASSES)}"""],
                [YamlPropertySourceGenerator.DESCRIPTION, 'yaml.to.java.config.enabled = true'],
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

    static String toPropertiesSample(Class<? extends AOTCodeGenerator> clazz) {
        return MetadataUtils.toPropertiesSample(
                MetadataUtils.findMetadata(clazz)
                    .get()
                    .options()[0]
        )
    }

    static String toPropertiesSample(Class<? extends AOTCodeGenerator> clazz, String name) {
        return MetadataUtils.toPropertiesSample(
                MetadataUtils.findOption(clazz, name)
        )
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
