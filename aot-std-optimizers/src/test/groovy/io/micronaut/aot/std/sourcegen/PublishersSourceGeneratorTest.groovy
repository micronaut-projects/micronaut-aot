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
import io.micronaut.core.async.publisher.PublishersOptimizations
import io.micronaut.core.optim.StaticOptimizations

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
            doesNotCreateInitializer()
            hasClass("PublishersOptimizationsLoader") {
                withSources """package io.micronaut.test;

import io.micronaut.core.async.publisher.CompletableFuturePublisher;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.async.publisher.PublishersOptimizations;
import io.micronaut.core.async.subscriber.Completable;
import io.micronaut.core.optim.StaticOptimizations;
import java.lang.Override;
import java.util.ArrayList;
import java.util.Arrays;

public class PublishersOptimizationsLoader implements StaticOptimizations.Loader<PublishersOptimizations> {
  @Override
  public PublishersOptimizations load() {
    return new PublishersOptimizations(new ArrayList<>(Arrays.asList(CompletableFuturePublisher.class, Publishers.JustPublisher.class, Publishers.JustThrowPublisher.class, Completable.class)), new ArrayList<>(Arrays.asList(CompletableFuturePublisher.class, Publishers.JustPublisher.class, Publishers.JustThrowPublisher.class)), new ArrayList<>(Arrays.asList(Completable.class)));
  }
}"""
                compiles()
            }

        }
    }

    def "generated lists accept types registered at runtime"() {
        given:
        generate()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("PublishersOptimizationsLoader") {
                containingSources "new ArrayList<>(Arrays.asList("
                compiles()
            }
        }

        when: "the compiled loader is run, as StaticOptimizations does at startup"
        def optimizations = loadOptimizations()

        and: "types are registered, as Publishers.registerReactive* does"
        optimizations.reactiveTypes.add(StringBuilder)
        optimizations.singleTypes.add(StringBuilder)
        optimizations.completableTypes.add(StringBuilder)

        then:
        noExceptionThrown()
        optimizations.reactiveTypes.last() == StringBuilder
        optimizations.singleTypes.last() == StringBuilder
        optimizations.completableTypes.last() == StringBuilder
    }

    private PublishersOptimizations loadOptimizations() {
        def compiled = testDirectory.resolve("compiled").toUri().toURL()
        try (def loader = new URLClassLoader([compiled] as URL[], getClass().classLoader)) {
            def loaderClass = loader.loadClass("${packageName}.PublishersOptimizationsLoader")
            def optimizationsLoader = (StaticOptimizations.Loader<PublishersOptimizations>) loaderClass.getDeclaredConstructor().newInstance()
            optimizationsLoader.load()
        }
    }

}
