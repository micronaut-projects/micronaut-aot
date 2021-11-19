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

class NativeStaticServiceLoaderSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    @Override
    AOTCodeGenerator newGenerator() {
        new NativeStaticServiceLoaderSourceGenerator()
    }

    def "generates a service loader for a single service"() {
        props.put(AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES, TestService.name)

        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void staticServices() {
  java.util.Map<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>> staticServices = new java.util.HashMap<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>>();
  staticServices.put("io.micronaut.aot.std.sourcegen.TestService", new TestServiceFactory());
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.io.service.SoftServiceLoader.Optimizations(staticServices));
}
"""
            hasClass("TestServiceFactory") {
                withSources """package io.micronaut.test;

import io.micronaut.aot.std.sourcegen.TestService;
import io.micronaut.aot.std.sourcegen.TestServiceImpl;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.io.service.SoftServiceLoader;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Generated
public class TestServiceFactory implements SoftServiceLoader.StaticServiceLoader<TestService> {
  public Stream<SoftServiceLoader.StaticDefinition<TestService>> findAll(
      Predicate<String> predicate) {
    List<SoftServiceLoader.StaticDefinition<TestService>> list = new ArrayList<>();
    if (predicate.test("io.micronaut.aot.std.sourcegen.TestServiceImpl")) {
      list.add(SoftServiceLoader.StaticDefinition.of("io.micronaut.aot.std.sourcegen.TestServiceImpl", TestServiceImpl::new));
    }
    return list.stream();
  }
}
"""
            }
        }
    }

    def "generates a service loader for a multiple implementations of a service"() {
        props.put(AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES, TestServiceWithMoreThanOneImpl.name)

        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void staticServices() {
  java.util.Map<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>> staticServices = new java.util.HashMap<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>>();
  staticServices.put("io.micronaut.aot.std.sourcegen.TestServiceWithMoreThanOneImpl", new TestServiceWithMoreThanOneImplFactory());
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.io.service.SoftServiceLoader.Optimizations(staticServices));
}
"""
            hasClass("TestServiceWithMoreThanOneImplFactory") {
                withSources """package io.micronaut.test;

import io.micronaut.aot.std.sourcegen.TestServiceImpl;
import io.micronaut.aot.std.sourcegen.TestServiceImpl2;
import io.micronaut.aot.std.sourcegen.TestServiceWithMoreThanOneImpl;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.io.service.SoftServiceLoader;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Generated
public class TestServiceWithMoreThanOneImplFactory implements SoftServiceLoader.StaticServiceLoader<TestServiceWithMoreThanOneImpl> {
  public Stream<SoftServiceLoader.StaticDefinition<TestServiceWithMoreThanOneImpl>> findAll(
      Predicate<String> predicate) {
    List<SoftServiceLoader.StaticDefinition<TestServiceWithMoreThanOneImpl>> list = new ArrayList<>();
    if (predicate.test("io.micronaut.aot.std.sourcegen.TestServiceImpl")) {
      list.add(SoftServiceLoader.StaticDefinition.of("io.micronaut.aot.std.sourcegen.TestServiceImpl", TestServiceImpl::new));
    }
    if (predicate.test("io.micronaut.aot.std.sourcegen.TestServiceImpl2")) {
      list.add(SoftServiceLoader.StaticDefinition.of("io.micronaut.aot.std.sourcegen.TestServiceImpl2", TestServiceImpl2::new));
    }
    return list.stream();
  }
}
"""
            }
        }
    }
}
