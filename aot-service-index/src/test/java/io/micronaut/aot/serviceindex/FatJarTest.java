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

import java.nio.file.Path;
import java.util.List;

import static io.micronaut.aot.serviceindex.Fixtures.APPLICATION_COUNTS;
import static io.micronaut.aot.serviceindex.Fixtures.APPLICATION_LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fat jar, merged as Shadow merges one, started with {@code java -jar}: the index lists the jar alone, without a
 * size, and micronaut-core uses it and validates it against a scan.
 */
class FatJarTest {

    @TempDir
    Path temporary;

    @Test
    void aFatJarStartsWithItsIndexValidatedAgainstAScan() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        Run scan = Fixtures.javaJar(jar, List.of(VALIDATE), LOOKUPS);
        assertEquals(APPLICATION_LOOKUPS, scan.lookups());
        assertFalse(scan.usesTheIndex(), scan::err);

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);
        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        Fixtures.apply(jar, result);
        Run indexed = Fixtures.javaJar(jar, List.of(VALIDATE), LOOKUPS);

        assertEquals(APPLICATION_LOOKUPS, indexed.lookups());
        assertTrue(indexed.usesTheIndex(), indexed::err);
        assertTrue(indexed.err().contains(APPLICATION_COUNTS + " (class path compared: the 1 entry of java.class.path,"
                + " to which the manifest of app.jar adds nothing, names validated against a scan)"), indexed::err);
        assertFalse(indexed.warns(), indexed::err);
    }

    @Test
    void theMessageCountsWhatWasIndexed() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        assertTrue(result.message().startsWith("Generated a service index of 3 META-INF/micronaut entries of 2 types"
                + " and 4 META-INF/services names of 7 types, listing 1 class path entry, in "), result::message);
    }
}
