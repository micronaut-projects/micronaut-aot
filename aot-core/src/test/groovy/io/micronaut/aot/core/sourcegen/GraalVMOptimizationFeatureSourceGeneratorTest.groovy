package io.micronaut.aot.core.sourcegen

class GraalVMOptimizationFeatureSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    SourceGenerator newGenerator() {
        new GraalVMOptimizationFeatureSourceGenerator(context, 'Application$Optimized', ['A', 'B', 'C'])
    }

    def "generates a feature class"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass('MicronautAOTFeature') {
                withSources '''package io.micronaut.test;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.hosted.ServiceLoaderFeature;
import io.micronaut.core.annotation.Generated;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

@Generated
@AutomaticFeature
public class MicronautAOTFeature implements Feature {
  static {
    ServiceLoaderFeature.Options.ServiceLoaderFeatureExcludeServices.getValue().valueUpdate("A");
    ServiceLoaderFeature.Options.ServiceLoaderFeatureExcludeServices.getValue().valueUpdate("B");
    ServiceLoaderFeature.Options.ServiceLoaderFeatureExcludeServices.getValue().valueUpdate("C");
  }

  public void beforeAnalysis(Feature.BeforeAnalysisAccess access) {
    RuntimeClassInitialization.initializeAtBuildTime(Application$Optimized.class);
  }
}
'''
            }
        }
    }
}
