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
package io.micronaut.aot.internal.sourcegen

class KnownMissingTypesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    SourceGenerator newGenerator() {
        new KnownMissingTypesSourceGenerator(context, [
                'non.existing.ClassName',
                SourceGenerator.class.name,
                'another.missing.Clazz'
        ])
    }

    def "generates known missing classes initializer"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer"""private static void prepareKnownMissingTypes() {
  java.util.Set<java.lang.String> knownMissingTypes = new java.util.HashSet<java.lang.String>();
  knownMissingTypes.add("non.existing.ClassName");
  knownMissingTypes.add("another.missing.Clazz");
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.reflect.ClassUtils.Optimizations(knownMissingTypes));
}
"""
        }
    }
}
