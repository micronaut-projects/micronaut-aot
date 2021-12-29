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
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec

class ConstantPropertySourcesSourceGeneratorTest extends AbstractSourceGeneratorSpec {

    @Override
    AOTCodeGenerator newGenerator() {
        def substitutes = new AbstractStaticServiceLoaderSourceGenerator.Substitutes()
        substitutes.putAll([
                'io.micronaut.context.env.PropertySourceLoader': [JavaFile.builder(packageName, TypeSpec.classBuilder("Replacement").build()).build()]
        ])
        context.put(AbstractStaticServiceLoaderSourceGenerator.Substitutes, substitutes)
        new ConstantPropertySourcesSourceGenerator()
    }

    def "can generate constant property source class"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("AotConstantPropertySources") {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.ConstantPropertySources;
import io.micronaut.context.env.PropertySource;
import io.micronaut.core.optim.StaticOptimizations;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;

public class AotConstantPropertySources implements StaticOptimizations.Loader<ConstantPropertySources> {
  @Override
  public ConstantPropertySources load() {
    List<PropertySource> propertySources = new ArrayList<PropertySource>();
    propertySources.add(new Replacement());
    return new ConstantPropertySources(propertySources);
  }
}"""
            }
        }
    }

}
