package io.micronaut.aot.cli

import groovy.transform.CompileStatic
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.config.MetadataUtils

import io.micronaut.aot.std.sourcegen.ConstantPropertySourcesSourceGenerator
import io.micronaut.aot.std.sourcegen.DeduceEnvironmentSourceGenerator
import io.micronaut.aot.std.sourcegen.EnvironmentPropertiesSourceGenerator
import io.micronaut.aot.core.Environments
import io.micronaut.aot.std.sourcegen.GenericPropertySourceGenerator
import io.micronaut.aot.std.sourcegen.GraalVMOptimizationFeatureSourceGenerator

import io.micronaut.aot.std.sourcegen.KnownMissingTypesSourceGenerator
import io.micronaut.aot.std.sourcegen.LogbackConfigurationSourceGenerator
import io.micronaut.aot.std.sourcegen.NettyPropertiesSourceGenerator
import io.micronaut.aot.std.sourcegen.PublishersSourceGenerator
import io.micronaut.aot.std.sourcegen.CachedEnvironmentSourceGenerator
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path

class CliTest extends Specification {
    @TempDir
    Path testDirectory

    def "configures slf4j to use logback without provider lookup"() {
        given:
        def existingVerbosity = System.getProperty(Main.SLF4J_INTERNAL_VERBOSITY)
        def existingProvider = System.getProperty(Main.SLF4J_PROVIDER)
        System.clearProperty(Main.SLF4J_INTERNAL_VERBOSITY)
        System.clearProperty(Main.SLF4J_PROVIDER)

        when:
        Main.configureSlf4j()

        then:
        System.getProperty(Main.SLF4J_INTERNAL_VERBOSITY) == 'WARN'
        System.getProperty(Main.SLF4J_PROVIDER) == Main.LOGBACK_SERVICE_PROVIDER

        cleanup:
        restoreSystemProperty(Main.SLF4J_INTERNAL_VERBOSITY, existingVerbosity)
        restoreSystemProperty(Main.SLF4J_PROVIDER, existingProvider)
    }

    def "keeps user provided slf4j system properties"() {
        given:
        def existingVerbosity = System.getProperty(Main.SLF4J_INTERNAL_VERBOSITY)
        def existingProvider = System.getProperty(Main.SLF4J_PROVIDER)
        System.setProperty(Main.SLF4J_INTERNAL_VERBOSITY, 'ERROR')
        System.setProperty(Main.SLF4J_PROVIDER, 'example.CustomServiceProvider')

        when:
        Main.configureSlf4j()

        then:
        System.getProperty(Main.SLF4J_INTERNAL_VERBOSITY) == 'ERROR'
        System.getProperty(Main.SLF4J_PROVIDER) == 'example.CustomServiceProvider'

        cleanup:
        restoreSystemProperty(Main.SLF4J_INTERNAL_VERBOSITY, existingVerbosity)
        restoreSystemProperty(Main.SLF4J_PROVIDER, existingProvider)
    }

    def "can generate a diagnostics report and print its path"() {
        given:
        def oldOut = System.out
        def configFile = testDirectory.resolve("aot.properties")
        Files.writeString(configFile, """cached.environment.enabled = true
netty.machine.id = super-secret
datasources.default.password = super-secret
""")
        def outputDirectory = testDirectory.resolve("output")
        def classpath = System.getProperty('aot.runtime')
        def stdout = new ByteArrayOutputStream()

        when:
        System.out = new PrintStream(stdout)
        def exitCode = Main.execute(
                '--classpath', classpath,
                '--runtime', 'jit',
                '--package', 'dummy',
                '--config', configFile.toString(),
                '--output', outputDirectory.toString(),
                '--report',
                '--report-format', 'json,html'
        )
        System.out = oldOut
        def reportFile = outputDirectory.resolve("reports/micronaut-aot-report.json")
        def htmlReportFile = outputDirectory.resolve("reports/micronaut-aot-report.html")
        def reportText = Files.readString(reportFile)
        def htmlReportText = Files.readString(htmlReportFile)

        then:
        exitCode == 0
        stdout.toString().contains(reportFile.toFile().absolutePath)
        stdout.toString().contains(htmlReportFile.toFile().absolutePath)
        Files.exists(reportFile)
        Files.exists(htmlReportFile)
        !reportText.contains("super-secret")
        !htmlReportText.contains("super-secret")
        reportText.contains('"schemaVersion": 1')
        reportText.contains('"runtime": "JIT"')
        reportText.contains('"packageName": "dummy"')
        reportText.contains('"id": "cached.environment"')
        reportText.contains('"enabled": true')
        reportText.contains('"id": "netty.properties"')
        reportText.contains('"key": "netty.machine.id"')
        reportText.contains('"configured": true')
        reportText.contains('"className": "dummy.AOTApplicationContextConfigurer"')
        reportText.contains('"META-INF/services/io.micronaut.context.ApplicationContextConfigurer"')
        htmlReportText.contains("<title>Micronaut AOT Diagnostics Report</title>")
        htmlReportText.contains("<td>JIT</td>")
        htmlReportText.contains("<td>cached.environment</td>")
        htmlReportText.contains("netty.machine.id (configured: true, redacted: true)")

        cleanup:
        if (oldOut != null) {
            System.out = oldOut
        }
    }

    def "does not generate a diagnostics report by default"() {
        given:
        def configFile = testDirectory.resolve("aot.properties")
        Files.writeString(configFile, "cached.environment.enabled = true")
        def outputDirectory = testDirectory.resolve("output")
        def classpath = System.getProperty('aot.runtime')

        when:
        def exitCode = Main.execute(
                '--classpath', classpath,
                '--runtime', 'jit',
                '--package', 'dummy',
                '--config', configFile.toString(),
                '--output', outputDirectory.toString()
        )

        then:
        exitCode == 0
        !Files.exists(outputDirectory.resolve("reports/micronaut-aot-report.json"))
    }

    @Unroll
    def "can export a dummy configuration file"() {
        def configFile = testDirectory.resolve("${runtime}.properties")
        def classpath = System.getProperty('aot.runtime')

        when:
        Main.execute(
                '--classpath', classpath,
                '--runtime', runtime,
                '--package', 'dummy',
                '--config', configFile.toString()
        )

        then:
        Files.exists(configFile)
        def config = normalize(configFile.toFile().text)

        def generatedDocs = [
                [CachedEnvironmentSourceGenerator.DESCRIPTION, 'cached.environment.enabled = true'],
                [DeduceEnvironmentSourceGenerator.DESCRIPTION, "deduce.environment.enabled = true"],
                runtime == 'native' ? [GraalVMOptimizationFeatureSourceGenerator.DESCRIPTION, "graalvm.config.enabled = true\n${toPropertiesSample(GraalVMOptimizationFeatureSourceGenerator)}"] : null,
                [KnownMissingTypesSourceGenerator.DESCRIPTION, """known.missing.types.enabled = true
${toPropertiesSample(KnownMissingTypesSourceGenerator)}"""],
                [LogbackConfigurationSourceGenerator.DESCRIPTION, 'logback.xml.to.java.enabled = true'],
                [NettyPropertiesSourceGenerator.DESCRIPTION, """netty.properties.enabled = true
${toPropertiesSample(NettyPropertiesSourceGenerator, NettyPropertiesSourceGenerator.MACHINE_ID)}
${toPropertiesSample(NettyPropertiesSourceGenerator, NettyPropertiesSourceGenerator.PROCESS_ID)}"""],
                [EnvironmentPropertiesSourceGenerator.DESCRIPTION, 'precompute.environment.properties.enabled = true'],
                [GenericPropertySourceGenerator.DESCRIPTION, """property-source-loader.generate.enabled = true
${toPropertiesSample(GenericPropertySourceGenerator, "property-source-loader.types")}
${toPropertiesSample(GenericPropertySourceGenerator, "property-source-loader.base-order")}
${toPropertiesSample(GenericPropertySourceGenerator, "property-source-loader.resource-names")}
${toPropertiesSample(GenericPropertySourceGenerator, "property-source-loader.service-loader-exclude")}
${toPropertiesSample(GenericPropertySourceGenerator, "yaml.to.java.config")}"""],
                [PublishersSourceGenerator.DESCRIPTION, 'scan.reactive.types.enabled = true'],
                [ConstantPropertySourcesSourceGenerator.DESCRIPTION, "sealed.property.source.enabled = true"],
        ].findAll().collect { desc, c ->
            """# $desc
$c
"""
        }.join("\n").trim()
        String expected = normalize """$generatedDocs

# ${Environments.TARGET_ENVIRONMENTS_DESCRIPTION}
${Environments.TARGET_ENVIRONMENTS_NAMES} = ${Environments.TARGET_ENVIRONMENTS_SAMPLE}
""".trim()

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

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key)
        } else {
            System.setProperty(key, value)
        }
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
