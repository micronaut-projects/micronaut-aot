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

class YamlPropertySourceGeneratorTest extends AbstractSourceGeneratorSpec {
    private String resource = "test-config"

    @Override
    AOTCodeGenerator newGenerator() {
        new YamlPropertySourceGenerator([resource])
    }

    def "generates a class from a YAML configuration file"() {
        when:
        generate()

        then:
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("Test_configStaticPropertySource") {
                withSources """package io.micronaut.test;

import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Generated
public class Test_configStaticPropertySource extends MapPropertySource {
  Test_configStaticPropertySource() {
    super("test-config", new HashMap() {{
        put("my.prop1", "val1");
        put("my.prop2", "val2");
        put("micronaut.application.name", "demoApp");
        put("micronaut.server.port", 8181);
        put("micronaut.server.cors.enabled", true);
        put("micronaut.security.intercept-url-map", list0());
        put("otel.exclusions", list5());
        }});
  }

  private static List list2() {
    return Collections.singletonList("isAnonymous()");
  }

  private static Map map1() {
    Map result = new LinkedHashMap<>(3);
    result.put("pattern", "/api/v1/subscriber/confirm");
    result.put("http-method", "PATCH");
    result.put("access", list2());
    return result;
  }

  private static List list4() {
    return Collections.singletonList("isAnonymous()");
  }

  private static Map map3() {
    Map result = new LinkedHashMap<>(3);
    result.put("pattern", "/api/v1/subscriber/count");
    result.put("http-method", "GET");
    result.put("access", list4());
    return result;
  }

  private static Map map5() {
    Map result = new LinkedHashMap<>(2);
    result.put("pattern", "/v1/odata/\$metadata");
    result.put("http-method", "GET");
    return result;
  }

  private static List list0() {
    List result = new ArrayList<>(3);
    result.add(map1());
    result.add(map3());
    result.add(map5());
    return result;
  }

  private static List list5() {
    List result = new ArrayList<>(3);
    result.add("\${my.prop1}");
    result.add("\${my.prop2}");
    result.add("fixed-value");
    return result;
  }

  public int getOrder() {
    return -2147483648;
  }
}
"""
            }
        }
    }
}
