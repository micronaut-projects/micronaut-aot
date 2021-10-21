package io.micronaut.aot.core.sourcegen

class GraalVMOptimizationFeatureSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    SourceGenerator newGenerator() {
        new GraalVMOptimizationFeatureSourceGenerator(context, 'ApplicationService', ['A', 'B', 'C'])
    }

    def "generates a feature class"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            generatesMetaInfResource("native-image/$packageName/native-image.properties", """
--initialize-at-build-time=io.micronaut.test.ApplicationService
-H:ServiceLoaderFeatureExcludeServices=A
-H:ServiceLoaderFeatureExcludeServices=B
-H:ServiceLoaderFeatureExcludeServices=C
""")
        }
    }
}
