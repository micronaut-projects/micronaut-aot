package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTSourceGenerator
import io.micronaut.aot.core.sourcegen.AbstractSourceGeneratorSpec

class GraalVMOptimizationFeatureSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTSourceGenerator newGenerator() {
        props.put(AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES, ['A', 'B', 'C'].join(','))
        new GraalVMOptimizationFeatureSourceGenerator()
    }

    def "generates a feature file"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            generatesMetaInfResource("native-image/$packageName/native-image.properties", """
Args=--initialize-at-build-time=io.micronaut.test.AOTApplicationContextConfigurer \\
     -H:ServiceLoaderFeatureExcludeServices=A \\
     -H:ServiceLoaderFeatureExcludeServices=B \\
     -H:ServiceLoaderFeatureExcludeServices=C
""")
        }
    }
}
