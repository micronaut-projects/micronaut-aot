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
package io.micronaut.aot.core.sourcegen

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec

import java.util.function.Predicate

class ConstantPropertySourcesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, AbstractSingleClassFileGenerator> substitutions = [:]

    @Override
    SourceGenerator newGenerator() {
        substitutions[TestServiceImpl.name] = new SubstituteGenerator(context)
        AbstractStaticServiceLoaderSourceGenerator generator = new TestServiceLoaderGenerator(
                context,
                { true },
                [TestService.name],
                { false },
                substitutions
        )
        generator.init()
        new ConstantPropertySourcesSourceGenerator(context, generator)
    }

    def "can generate constant property source class"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer("""private static void preparePropertySources() {
  // Generates pre-computed Micronaut property sources from known configuration files
  java.util.List<io.micronaut.context.env.PropertySource> propertySources = new java.util.ArrayList<io.micronaut.context.env.PropertySource>();
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.context.env.ConstantPropertySources(propertySources));
}
""")
        }
    }

    static class TestServiceLoaderGenerator extends AbstractStaticServiceLoaderSourceGenerator {

        protected TestServiceLoaderGenerator(SourceGenerationContext context, Predicate<Object> applicationContextAnalyzer, List<String> serviceNames, Predicate<String> rejectedClasses, Map<String, AbstractSingleClassFileGenerator> substitutions) {
            super(context, applicationContextAnalyzer, serviceNames, rejectedClasses, substitutions)
        }

        @Override
        protected void generateFindAllMethod(Predicate<String> rejectedClasses, String serviceName, Class<?> serviceType, TypeSpec.Builder factory) {
            collectServiceImplementations(
                    serviceName,
                    (clazz, provider) -> clazz.getName()
            )
        }
    }

    class SubstituteGenerator extends AbstractSingleClassFileGenerator {

        protected SubstituteGenerator(SourceGenerationContext context) {
            super(context)
        }

        @Override
        protected JavaFile generate() {
            TypeSpec typeSpec = TypeSpec.classBuilder("Substitute").build()
            JavaFile.builder(packageName, typeSpec).build()
        }
    }
}
