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

class PublishersSourceGeneratorTest extends AbstractSourceGeneratorSpec {

    @Override
    AOTCodeGenerator newGenerator() {
        new PublishersSourceGenerator()
    }

    def "generates publishers optimization sources"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotGenerateClasses()
            createsInitializer """
private static void preparePublishers() {
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.async.publisher.PublishersOptimizations(java.util.Arrays.asList(), java.util.Arrays.asList(io.micronaut.core.async.publisher.CompletableFuturePublisher.class, io.micronaut.core.async.publisher.Publishers.JustPublisher.class), java.util.Arrays.asList(io.micronaut.core.async.subscriber.Completable.class)));
}
"""
        }
    }

}
