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

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.aot.serviceindex.Fixtures.APPLICATION_COUNTS;
import static io.micronaut.aot.serviceindex.Fixtures.APPLICATION_LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An index without runtime names lists no class path. It is served for the class loader that defines the generated
 * loader whatever that class loader is, here one that is neither the system class loader nor a
 * {@code URLClassLoader}, as Micronaut Runner's is.
 */
class NoClassPathTest {

    @TempDir
    Path temporary;

    @Test
    void anIndexWithoutAClassPathIsServedForAnyClassLoader() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(ServiceIndexGenerator.Request.builder()
                .entry(jar).holder(jar).build());
        Fixtures.apply(jar, result);
        assertNull(Fixtures.load(List.of(jar), result.entries()).classPath());
        List<String> command = new ArrayList<>(List.of(VALIDATE, "-cp",
                String.join(File.pathSeparator, System.getProperty(Fixtures.PROBE)), Fixtures.LAUNCHER_CLASS,
                jar.toString()));
        command.addAll(LOOKUPS);
        Run run = Fixtures.java(temporary, command);

        assertEquals(APPLICATION_LOOKUPS, run.lookups());
        assertTrue(run.usesTheIndex(), run::err);
        assertTrue(run.err().contains(APPLICATION_COUNTS
                + " (class path not compared: the index does not list one, names validated against a scan)"), run::err);
        assertFalse(run.warns(), run::err);
        assertTrue(result.message().contains(", without a class path, in "), result::message);
    }
}
