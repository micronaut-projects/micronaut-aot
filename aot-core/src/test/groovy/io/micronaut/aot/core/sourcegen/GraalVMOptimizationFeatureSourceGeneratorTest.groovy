package io.micronaut.aot.core.sourcegen

import io.micronaut.aot.core.AOTSourceGenerator

class GraalVMOptimizationFeatureSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTSourceGenerator newGenerator() {
        props.put(GraalVMOptimizationFeatureSourceGenerator.OPTION.key, ['A', 'B', 'C'].join(','))
        new GraalVMOptimizationFeatureSourceGenerator()
    }

    def "generates a feature file"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            generatesMetaInfResource("native-image/$packageName/native-image.properties", """
Args=--initialize-at-build-time=io.micronaut.test.AOTApplicationContextCustomizer \\
     -H:ServiceLoaderFeatureExcludeServices=A \\
     -H:ServiceLoaderFeatureExcludeServices=B \\
     -H:ServiceLoaderFeatureExcludeServices=C
""")
        }
    }
}
