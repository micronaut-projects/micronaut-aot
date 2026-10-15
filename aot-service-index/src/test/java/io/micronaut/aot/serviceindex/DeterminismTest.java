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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same class path gives the same bytes, which build caches rely on, whatever order a directory lists its files in.
 */
class DeterminismTest {

    @TempDir
    Path temporary;

    @Test
    void twoCallsGiveTheSameBytes() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        assertSameEntries(generated(Fixtures.generateFor(jar)), generated(Fixtures.generateFor(jar)));
    }

    private static Map<String, byte[]> generated(ServiceIndexGenerator.Result result) {
        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        return result.entries();
    }

    @Test
    void directoriesWrittenInAnotherOrderGiveTheSameBytes() throws Exception {
        Fixtures.Content content = Fixtures.application().services("fixture.Third", "fixture.C")
                .micronaut("fixture.Other", "fixture.$Z", "fixture.$A", "fixture.$M");
        List<Map.Entry<String, byte[]>> reversed = new ArrayList<>(content.entries.entrySet());
        Collections.reverse(reversed);
        Fixtures.Content backwards = Fixtures.content();
        reversed.forEach(entry -> backwards.put(entry.getKey(), entry.getValue()));

        Map<String, byte[]> forward = generate(Fixtures.directory(temporary.resolve("forward/classes"), content));
        Map<String, byte[]> backward = generate(Fixtures.directory(temporary.resolve("backward/classes"), backwards));

        assertSameEntries(forward, backward);
    }

    private static Map<String, byte[]> generate(Path classes) {
        ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder()
                .entry(classes, "classes").holder(classes);
        for (Path library : Fixtures.core()) {
            request.entry(library, library.getFileName().toString());
        }
        ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(request.build());
        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        return result.entries();
    }

    private static void assertSameEntries(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(actual.keySet()));
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey());
        }
    }
}
