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

import io.micronaut.aot.serviceindex.Fixtures.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static io.micronaut.aot.serviceindex.Fixtures.APPLICATION_LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.BEAN_DEFINITIONS;
import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JVM registers one index: the output of an earlier generation in the holder is replaced, not added to, and
 * generating again on the output changes nothing.
 */
class OneIndexTest {

    private static final String REMOVED_BEANS = "META-INF/micronaut/" + BEAN_DEFINITIONS + "/fixture.$Removed";

    @TempDir
    Path temporary;

    @Test
    void anEarlierIndexInTheHolderIsReplaced() throws Exception {
        // The earlier output is of a larger application, so it has more classes than the new one, and another index.
        List<String> removedBeans = new ArrayList<>();
        for (int i = 0; i < 30_000; i++) {
            removedBeans.add("fixture.$Removed%05d$Definition$Reference".formatted(i));
        }
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"),
                Fixtures.application().micronaut(BEAN_DEFINITIONS, removedBeans));
        ServiceIndexGenerator.Result earlier = Fixtures.generateFor(jar);
        Fixtures.apply(jar, earlier);
        Fixtures.Content changed = Fixtures.read(jar);
        changed.entries.keySet().removeIf(name -> name.startsWith(REMOVED_BEANS));
        Fixtures.jar(jar, changed);

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        assertTrue(result.message().endsWith(" (it replaces an earlier index)"), result::message);
        Set<String> leftOver = new TreeSet<>(earlier.entries().keySet());
        leftOver.removeAll(result.entries().keySet());
        assertFalse(leftOver.isEmpty(), "the earlier output was not larger");
        assertEquals(leftOver, result.removals());
        assertEquals(ServiceIndexGenerator.LOADER_CLASS + "\n",
                new String(result.entries().get(ServiceIndexGenerator.LOADER_SERVICE), StandardCharsets.UTF_8));

        Fixtures.apply(jar, result);
        Run run = Fixtures.javaJar(jar, List.of(VALIDATE), LOOKUPS);
        assertEquals(APPLICATION_LOOKUPS, run.lookups());
        assertEquals(1, run.err().split(Fixtures.USING, -1).length - 1, run::err);
        assertFalse(run.warns(), run::err);

        ServiceIndexGenerator.Result again = Fixtures.generateFor(jar);
        Map<String, byte[]> written = Fixtures.read(jar).entries;
        assertEquals(ServiceIndexGenerator.Status.GENERATED, again.status(), again.message());
        assertEquals(Set.of(), again.removals());
        for (Map.Entry<String, byte[]> entry : again.entries().entrySet()) {
            assertArrayEquals(written.get(entry.getKey()), entry.getValue(), entry.getKey());
        }
    }

    /** The line of an earlier loader is dropped wherever it stands, and the other lines are kept as they were. */
    @Test
    void theLoaderLineComesFirstAndOnce() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application()
                .put(ServiceIndexGenerator.LOADER_SERVICE, "fixture.OtherLoader\n  " + ServiceIndexGenerator.LOADER_CLASS
                        + "  # an earlier index\nfixture.LastLoader")
                .type("fixture.OtherLoader", Fixtures.loaderClass("fixture.OtherLoader", "fixture.One"))
                .type("fixture.LastLoader", Fixtures.loaderClass("fixture.LastLoader", "fixture.Two"))
                .type("fixture.One", Fixtures.emptyClass("fixture.One"))
                .type("fixture.Two", Fixtures.emptyClass("fixture.Two")));

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.LOADER_CLASS + "\nfixture.OtherLoader\nfixture.LastLoader\n",
                new String(result.entries().get(ServiceIndexGenerator.LOADER_SERVICE), StandardCharsets.UTF_8));
        assertTrue(result.message().endsWith(" (it replaces an earlier index)"), result::message);
    }
}
