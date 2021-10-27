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

import io.micronaut.aot.core.AOTSourceGenerator

class MapPropertySourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private final Map<String, Object> values = [:]

    @Override
    AOTSourceGenerator newGenerator() {
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
}
"""
            }
        }
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
}
"""
            }
        }

        where:
        value      | literal
        'a string' | '"a string"'
        (int) 1    | '1'
        (short) 1  | '1'
        (byte) 1   | '1'
        (long) 1   | '1'
        2f         | '2.0'
        2d         | '2.0'
    }
}
