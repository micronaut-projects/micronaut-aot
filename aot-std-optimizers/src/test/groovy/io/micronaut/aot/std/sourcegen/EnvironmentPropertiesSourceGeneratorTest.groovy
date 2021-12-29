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

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec

class EnvironmentPropertiesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, String> env = [:]

    @Override
    AOTCodeGenerator newGenerator() {
        new EnvironmentPropertiesSourceGenerator(env)
    }

    def "generates static optimizations for environment variables"() {
        env['MICRONAUT_PORT'] = '8080'
        env['SOME_LONG_ENV_VAR'] = 'true'

        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("EnvironmentPropertiesOptimizationLoader") {
                withSources """package io.micronaut.test;

import io.micronaut.core.optim.StaticOptimizations;
import io.micronaut.core.util.EnvironmentProperties;
import java.lang.Override;
import java.lang.String;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnvironmentPropertiesOptimizationLoader implements StaticOptimizations.Loader<EnvironmentProperties> {
  @Override
  public EnvironmentProperties load() {
    Map<String, List<String>> env = new HashMap<String, List<String>>();
    env.put("MICRONAUT_PORT", Arrays.asList("micronaut.port", "micronaut-port"));
    env.put("SOME_LONG_ENV_VAR", Arrays.asList("some.long.env.var", "some.long.env-var", "some.long-env.var", "some.long-env-var", "some-long.env.var", "some-long.env-var", "some-long-env.var", "some-long-env-var"));
    return EnvironmentProperties.of(env);
  }
}"""
            }
        }
    }
}
