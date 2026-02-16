package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec

class GraalVMOptimizationFeatureSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTCodeGenerator newGenerator() {
        new GraalVMOptimizationFeatureSourceGenerator()
    }

    def "generates a feature file"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            generatesMetaInfResource("native-image/$packageName/native-image.properties", """
Args=--initialize-at-build-time=io.micronaut.context.ApplicationContextConfigurer\$1 \\
     --initialize-at-build-time=io.micronaut.test.AOTApplicationContextConfigurer
""")
        }
    }

    def "generates a feature file excluding service loading"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
generatesMetaInfResource("native-image/$packageName/native-image.properties", """
Args=--initialize-at-build-time=io.micronaut.context.ApplicationContextConfigurer\$1 \\
     --initialize-at-build-time=io.micronaut.test.AOTApplicationContextConfigurer
""")
        }
    }
}
