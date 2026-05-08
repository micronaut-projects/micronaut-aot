package io.micronaut.aot.core.report

import com.squareup.javapoet.TypeSpec
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.AOTContext
import io.micronaut.aot.core.AOTModule
import io.micronaut.aot.core.Option
import io.micronaut.aot.core.Runtime
import io.micronaut.aot.core.config.DefaultConfiguration
import io.micronaut.aot.core.config.SourceGeneratorSelection
import io.micronaut.aot.core.context.ApplicationContextAnalyzer
import io.micronaut.aot.core.context.DefaultSourceGenerationContext
import spock.lang.Specification
import spock.lang.TempDir

import javax.lang.model.element.Modifier
import java.nio.file.Files
import java.nio.file.Path

class AotDiagnosticReportWriterTest extends Specification {
    @TempDir
    Path testDirectory

    def "writes tracked artifacts and redacts configured option values"() {
        given:
        def props = new Properties()
        props.put("sample.password", "super-secret")
        def context = new DefaultSourceGenerationContext(
                "example",
                ApplicationContextAnalyzer.create {},
                new DefaultConfiguration(props),
                testDirectory.resolve("resources")
        )
        context.registerGeneratedSourceFile(context.javaFile(TypeSpec.classBuilder("GeneratedType").addModifiers(Modifier.PUBLIC).build()))
        context.registerGeneratedResource("META-INF/example/resource.txt") {}
        context.registerExcludedResource("application.yml")
        context.registerExcludedServiceImpl("example.Service", "replaced by generated implementation")
        context.registerBuildTimeInit("example.NativeType")
        context.addDiagnostics("sample", "message with \"quotes\"")
        def module = MetadataGenerator.getAnnotation(AOTModule)
        def selection = new SourceGeneratorSelection(new MetadataGenerator(), module, true, null)

        when:
        def report = AotDiagnosticReportWriter.toJson(
                Runtime.JIT,
                "example",
                ["test"] as Set,
                [selection],
                context,
                "1.2.3"
        )

        then:
        !report.contains("super-secret")
        report.contains('"schemaVersion": 1')
        report.contains('"runtime": "JIT"')
        report.contains('"packageName": "example"')
        report.contains('"activeEnvironments": ["test"]')
        report.contains('"id": "metadata-generator"')
        report.contains('"enabled": true')
        report.contains('"key": "sample.password"')
        report.contains('"configured": true')
        report.contains('"redacted": true')
        report.contains('"className": "example.GeneratedType"')
        report.contains('"path": "example/GeneratedType.java"')
        report.contains('"generatedResources": ["META-INF/example/resource.txt"]')
        report.contains('"filteredResources": ["application.yml"]')
        report.contains('"className": "example.Service"')
        report.contains('"buildTimeInitClasses": ["example.NativeType"]')
        report.contains('"category": "sample"')
        report.contains('"message": "message with \\"quotes\\""')
    }

    def "writes report file to requested directory"() {
        given:
        def context = new DefaultSourceGenerationContext(
                "example",
                ApplicationContextAnalyzer.create {},
                new DefaultConfiguration(new Properties()),
                testDirectory.resolve("resources")
        )

        when:
        def reportFile = AotDiagnosticReportWriter.write(
                testDirectory.resolve("nested/reports"),
                Runtime.NATIVE,
                "example",
                [] as Set,
                [],
                context,
                null
        )

        then:
        reportFile.fileName.toString() == "micronaut-aot-report.json"
        Files.exists(reportFile)
        reportFile.toFile().text.contains('"runtime": "native"')
    }

    @AOTModule(
            id = "metadata-generator",
            description = "Test generator",
            options = [@Option(key = "sample.password", description = "Sensitive sample")]
    )
    private static class MetadataGenerator implements AOTCodeGenerator {
        @Override
        void generate(AOTContext context) {
        }
    }
}
