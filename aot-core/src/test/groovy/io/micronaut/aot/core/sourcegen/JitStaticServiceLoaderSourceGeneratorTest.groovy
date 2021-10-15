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

class JitStaticServiceLoaderSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final List<String> services = []
    @Override
    SourceGenerator newGenerator() {
        new JitStaticServiceLoaderSourceGenerator(
                context,
                { true },
                services,
                { false },
                [:]
        )
    }

    def "generates a service loader for a single service"() {
        services << TestService.name

        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void staticServices() {
  java.util.Map<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>> staticServices = new java.util.HashMap<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>>();
  staticServices.put("io.micronaut.aot.core.sourcegen.TestService", new TestServiceFactory());
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.io.service.SoftServiceLoader.Optimizations(staticServices));
}
"""
            hasClass("TestServiceFactory") {
                withSources """package io.micronaut.test;

import io.micronaut.aot.core.sourcegen.TestService;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.io.service.SoftServiceLoader;
import java.lang.Class;
import java.lang.ClassLoader;
import java.lang.String;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Generated
public class TestServiceFactory implements SoftServiceLoader.StaticServiceLoader<TestService> {
  private static final String[] SERVICE_TYPES = new String[] {
    "io.micronaut.aot.core.sourcegen.TestServiceImpl"}
  ;

  static {
    ClassLoader cl = TestService.class.getClassLoader();
    ForkJoinPool pool = ForkJoinPool.commonPool();
    pool.submit(() -> loadClass(cl, "io.micronaut.aot.core.sourcegen.TestServiceImpl"));
  }

  private static Class<TestService> loadClass(ClassLoader cl, String name) {
    try {
      return (Class<TestService>) cl.loadClass(name);
    }
    catch (Exception e) {
      return null;
    }
  }

  public Stream<SoftServiceLoader.StaticDefinition<TestService>> findAll(
      Predicate<String> predicate) {
    ClassLoader cl = TestService.class.getClassLoader();
    return Arrays.stream(SERVICE_TYPES)
        .parallel()
        .filter(predicate::test)
        .map(s -> loadClass(cl, s))
        .filter(Objects::nonNull)
        .map(c -> SoftServiceLoader.StaticDefinition.of(c.getName(), c));
  }
}
"""
            }
        }
    }

    def "generates a service loader for a multiple implementations of a service"() {
        services << TestServiceWithMoreThanOneImpl.name

        when:
        generate()

        then:
        assertThatGeneratedSources {
            createsInitializer """private static void staticServices() {
  java.util.Map<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>> staticServices = new java.util.HashMap<java.lang.String, io.micronaut.core.io.service.SoftServiceLoader.StaticServiceLoader<?>>();
  staticServices.put("io.micronaut.aot.core.sourcegen.TestServiceWithMoreThanOneImpl", new TestServiceWithMoreThanOneImplFactory());
  io.micronaut.core.optim.StaticOptimizations.set(new io.micronaut.core.io.service.SoftServiceLoader.Optimizations(staticServices));
}
"""
            hasClass("TestServiceWithMoreThanOneImplFactory") {
                withSources """package io.micronaut.test;

import io.micronaut.aot.core.sourcegen.TestServiceWithMoreThanOneImpl;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.io.service.SoftServiceLoader;
import java.lang.Class;
import java.lang.ClassLoader;
import java.lang.String;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Predicate;
import java.util.stream.Stream;

@Generated
public class TestServiceWithMoreThanOneImplFactory implements SoftServiceLoader.StaticServiceLoader<TestServiceWithMoreThanOneImpl> {
  private static final String[] SERVICE_TYPES = new String[] {
    "io.micronaut.aot.core.sourcegen.TestServiceImpl",
    "io.micronaut.aot.core.sourcegen.TestServiceImpl2"}
  ;

  static {
    ClassLoader cl = TestServiceWithMoreThanOneImpl.class.getClassLoader();
    ForkJoinPool pool = ForkJoinPool.commonPool();
    pool.submit(() -> loadClass(cl, "io.micronaut.aot.core.sourcegen.TestServiceImpl"));
    pool.submit(() -> loadClass(cl, "io.micronaut.aot.core.sourcegen.TestServiceImpl2"));
  }

  private static Class<TestServiceWithMoreThanOneImpl> loadClass(ClassLoader cl, String name) {
    try {
      return (Class<TestServiceWithMoreThanOneImpl>) cl.loadClass(name);
    }
    catch (Exception e) {
      return null;
    }
  }

  public Stream<SoftServiceLoader.StaticDefinition<TestServiceWithMoreThanOneImpl>> findAll(
      Predicate<String> predicate) {
    ClassLoader cl = TestServiceWithMoreThanOneImpl.class.getClassLoader();
    return Arrays.stream(SERVICE_TYPES)
        .parallel()
        .filter(predicate::test)
        .map(s -> loadClass(cl, s))
        .filter(Objects::nonNull)
        .map(c -> SoftServiceLoader.StaticDefinition.of(c.getName(), c));
  }
}
"""
            }
        }
    }
}
