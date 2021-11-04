/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTSourceGenerator
import io.micronaut.aot.core.sourcegen.AbstractSourceGeneratorSpec

class EnvironmentPropertiesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, String> env = [:]

    @Override
    AOTSourceGenerator newGenerator() {
        new EnvironmentPropertiesSourceGenerator(env)
    }

    def "generates static optimizations for environment variables"() {
        env['MICRONAUT_PORT'] = '8080'
        env['SOME_LONG_ENV_VAR'] = 'true'

        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void prepareEnvironment() {
  // Generates pre-computed Micronaut property names from environment variables
  java.util.Map<java.lang.String, java.util.List<java.lang.String>> env = new java.util.HashMap<java.lang.String, java.util.List<java.lang.String>>();
  env.put("MICRONAUT_PORT", java.util.Arrays.asList("micronaut.port", "micronaut-port"));
  env.put("SOME_LONG_ENV_VAR", java.util.Arrays.asList("some.long.env.var", "some.long.env-var", "some.long-env.var", "some.long-env-var", "some-long.env.var", "some-long.env-var", "some-long-env.var", "some-long-env-var"));
  io.micronaut.core.optim.StaticOptimizations.set(io.micronaut.core.util.EnvironmentProperties.of(env));
}
"""
        }
    }
}
