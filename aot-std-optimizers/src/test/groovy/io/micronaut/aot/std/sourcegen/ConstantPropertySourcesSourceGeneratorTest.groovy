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

import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import io.micronaut.aot.core.AOTModule
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.Option
import io.micronaut.aot.core.codegen.AbstractSingleClassFileGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import io.micronaut.core.annotation.NonNull

import java.util.stream.Stream

class ConstantPropertySourcesSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, AbstractSingleClassFileGenerator> substitutions = [:]

    @Override
    AOTCodeGenerator newGenerator() {
        substitutions[TestServiceImpl.name] = new SubstituteGenerator()
        AbstractStaticServiceLoaderSourceGenerator generator = new TestServiceLoaderGenerator()
        generator.generate(context)
        new ConstantPropertySourcesSourceGenerator()
    }

    def "can generate constant property source class"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer(1,"""private static void preparePropertySources() {
  // Generates pre-computed Micronaut property sources from known configuration files
  java.util.List<io.micronaut.context.env.PropertySource> propertySources = new java.util.ArrayList<io.micronaut.context.env.PropertySource>();
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.context.env.ConstantPropertySources(propertySources));
}
""")
        }
    }

    @AOTModule(id = "loader", options = [
            @Option(
                    key = "service.types"
            ),
            @Option(
                    key = "serviceloading.rejected.impls"
            )
    ])
    static class TestServiceLoaderGenerator extends AbstractStaticServiceLoaderSourceGenerator {
        @Override
        protected void generateFindAllMethod(Stream<Class<?>> serviceClasses,
                                             String serviceName,
                                             Class<?> serviceType,
                                             TypeSpec.Builder factory) {

        }
    }

    @AOTModule(id = SubstituteGenerator.ID)
    class SubstituteGenerator extends AbstractSingleClassFileGenerator {
        private static final String ID = "internal.substitute.generator";

        @Override
        @NonNull
        protected JavaFile generate() {
            TypeSpec typeSpec = TypeSpec.classBuilder("Substitute").build()
            JavaFile.builder(packageName, typeSpec).build()
        }
    }
}
