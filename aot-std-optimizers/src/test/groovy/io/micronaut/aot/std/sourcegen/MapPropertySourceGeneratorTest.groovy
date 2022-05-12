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
import io.micronaut.core.order.Ordered
import spock.lang.Issue

class MapPropertySourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, Object> values = [:]

    @Override
    AOTCodeGenerator newGenerator() {
        new MapPropertySourceGenerator("test", values)
    }

    def "supports generating an empty property source"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass('TestStaticPropertySource') {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import java.util.HashMap;

@Generated
public class TestStaticPropertySource extends MapPropertySource {
  TestStaticPropertySource() {
    super("test", new HashMap() {{
        }});
  }

  public int getOrder() {
    return -2147483648;
  }
}
"""
            }
        }
    }

    @Issue("https://github.com/micronaut-projects/micronaut-aot/issues/33")
    def "can tweak the generated property source priority"() {
        when:
        props.put("map.property.order.test", order)
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass('TestStaticPropertySource') {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import java.util.HashMap;

@Generated
public class TestStaticPropertySource extends MapPropertySource {
  TestStaticPropertySource() {
    super("test", new HashMap() {{
        }});
  }

  public int getOrder() {
    return $order;
  }
}
"""
            }
        }

        where:
        order << [Ordered.LOWEST_PRECEDENCE, Ordered.HIGHEST_PRECEDENCE, 42]
    }

    def "supports generating a property source with multiple values"() {
        when:
        values.title = 'test'
        values["micronaut.port"] = 8080
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass('TestStaticPropertySource') {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import java.util.HashMap;

@Generated
public class TestStaticPropertySource extends MapPropertySource {
  TestStaticPropertySource() {
    super("test", new HashMap() {{
        put("title", "test");
        put("micronaut.port", 8080);
        }});
  }

  public int getOrder() {
    return -2147483648;
  }
}
"""
            }
        }
    }

    def "supports generating different property types"() {
        when:
        values['some.key'] = value
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass('TestStaticPropertySource') {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import java.util.HashMap;

@Generated
public class TestStaticPropertySource extends MapPropertySource {
  TestStaticPropertySource() {
    super("test", new HashMap() {{
        put("some.key", $literal);
        }});
  }

  public int getOrder() {
    return -2147483648;
  }
}
"""
            }
            compiles()
        }

        where:
        value                            | literal
        'a string'                       | '"a string"'
        'a string with a $dollar'        | '"a string with a $dollar"'
        '"${string with double quotes}"' | '"\\"${string with double quotes}\\""'
        (int) 1                          | '1'
        (short) 1                        | '(short) 1'
        (byte) 1                         | '(byte) 1'
        (long) 1                         | '1L'
        (long) 2147483648L               | '2147483648L'
        2f                               | '2.0F'
        2d                               | '2.0D'
        true                             | true
        false                            | false
        Boolean.TRUE                     | true
        Boolean.FALSE                    | false
    }

}
