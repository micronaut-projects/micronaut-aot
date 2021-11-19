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

class SealedEnvironmentSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTCodeGenerator newGenerator() {
        new SealedEnvironmentSourceGenerator()
    }

    def "generates code to enable environment caching"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void enableEnvironmentCaching() {
  io.micronaut.core.optim.StaticOptimizations.cacheEnvironment();
}
"""
        }
    }
}
