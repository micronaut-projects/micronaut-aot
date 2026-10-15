/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.aot.serviceindex;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The comparison that the generated loader has to pass before it is returned: the index it builds in
 * micronaut-core has to be the builder's, in the same order.
 */
class RoundTripTest {

    private static final IndexData BUILT = index(
            Map.of("a.Bean", List.of("a.One", "a.Two")),
            Map.of("a.Service", List.of("a.First", "a.Second"), "a.Empty", List.of()),
            List.of(new IndexData.Entry("app.jar", -1), new IndexData.Entry("lib.jar", 42)));

    @Test
    void theSameIndexHasNoDifference() {
        assertNull(BUILT.difference(index(
                Map.of("a.Bean", List.of("a.One", "a.Two")),
                Map.of("a.Service", List.of("a.First", "a.Second"), "a.Empty", List.of()),
                List.of(new IndexData.Entry("app.jar", -1), new IndexData.Entry("lib.jar", 42)))));
    }

    @Test
    void aDroppedType() {
        assertEquals("the META-INF/services types are [a.Service] instead of [a.Service, a.Empty]",
                BUILT.difference(index(
                        Map.of("a.Bean", List.of("a.One", "a.Two")),
                        Map.of("a.Service", List.of("a.First", "a.Second")),
                        BUILT.classPath())));
    }

    @Test
    void aReorderedName() {
        assertEquals("the names of META-INF/micronaut/a.Bean are [a.Two, a.One] instead of [a.One, a.Two]",
                BUILT.difference(index(
                        Map.of("a.Bean", List.of("a.Two", "a.One")),
                        BUILT.standardServices(),
                        BUILT.classPath())));
    }

    @Test
    void aChangedSize() {
        assertEquals("the class path is [app.jar (-1), lib.jar (41)] instead of [app.jar (-1), lib.jar (42)]",
                BUILT.difference(index(BUILT.micronautServices(), BUILT.standardServices(),
                        List.of(new IndexData.Entry("app.jar", -1), new IndexData.Entry("lib.jar", 41)))));
    }

    @Test
    void aMissingClassPath() {
        assertEquals("the class path is null instead of [app.jar (-1), lib.jar (42)]",
                BUILT.difference(index(BUILT.micronautServices(), BUILT.standardServices(), null)));
    }

    /** Maps in the order the entries are given, as an index iterates them. */
    private static IndexData index(Map<String, List<String>> micronaut, Map<String, List<String>> standard,
                                   List<IndexData.Entry> classPath) {
        return new IndexData(ordered(micronaut), ordered(standard), classPath);
    }

    private static Map<String, List<String>> ordered(Map<String, List<String>> map) {
        Map<String, List<String>> ordered = new LinkedHashMap<>();
        map.keySet().stream().sorted((a, b) -> b.compareTo(a)).forEach(key -> ordered.put(key, map.get(key)));
        return ordered;
    }
}
