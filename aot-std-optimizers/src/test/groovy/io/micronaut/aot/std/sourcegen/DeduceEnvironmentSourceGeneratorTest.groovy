package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.context.ApplicationContextBuilder

class DeduceEnvironmentSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTCodeGenerator newGenerator() {
        new DeduceEnvironmentSourceGenerator()
    }

    @Override
    protected void customizeContext(ApplicationContextBuilder builder) {
        builder.deduceEnvironment(true)
    }

    def "generates a context configurer which disables deduce environment"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            generatesMetaInfResource("services/io.micronaut.context.ApplicationContextConfigurer", 'io.micronaut.test.DeducedEnvironmentConfigurer')
            hasClass(DeduceEnvironmentSourceGenerator.DEDUCED_ENVIRONMENT_CONFIGURER) {
               withSources """
package io.micronaut.test;

import io.micronaut.context.ApplicationContextBuilder;
import io.micronaut.context.ApplicationContextConfigurer;
import java.lang.Override;

public class DeducedEnvironmentConfigurer implements ApplicationContextConfigurer {
  @Override
  public void configure(ApplicationContextBuilder builder) {
    builder.deduceEnvironment(false);
    builder.defaultEnvironments("test");
    builder.packages("jdk.internal.reflect");
  }

  @Override
  public int getOrder() {
    return LOWEST_PRECEDENCE;
  }
}
"""
            }
        }
    }
}
