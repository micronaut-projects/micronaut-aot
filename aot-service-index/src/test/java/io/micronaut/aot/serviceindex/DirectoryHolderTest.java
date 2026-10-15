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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code -cp libs/*:classes/} layout, as the Maven plugin's Dockerfile and Jib start an application: the holder is
 * the {@code classes} directory, and the order of the wildcard is the file system's, which micronaut-core matches
 * without regard to order.
 */
class DirectoryHolderTest {

    @TempDir
    Path temporary;

    @Test
    void aClassesDirectoryHoldsTheIndex() throws Exception {
        Path app = temporary.resolve("app");
        List<Path> libraries = new ArrayList<>();
        for (Path library : Fixtures.libraries()) {
            libraries.add(Files.copy(library, Files.createDirectories(app.resolve("libs")).resolve(library.getFileName())));
        }
        libraries.add(Fixtures.jar(app.resolve("libs/fixture-lib-1.0.jar"), Fixtures.library()));
        Path classes = Fixtures.directory(app.resolve("classes"), Fixtures.application().probe());
        List<String> command = new ArrayList<>(List.of(VALIDATE, "-cp", "libs/*" + File.pathSeparator + "classes/",
                Fixtures.PROBE_CLASS));
        command.addAll(LOOKUPS);
        Run scan = Fixtures.java(app, command);
        assertTrue(scan.lookups().contains("fixture.LibraryGreeting"), scan::out);

        ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder();
        for (Path library : libraries) {
            request.entry(library, library.getFileName().toString());
        }
        ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(request.entry(classes, "classes")
                .holder(classes).build());
        Fixtures.apply(classes, result);
        Run indexed = Fixtures.java(app, command);

        assertTrue(Files.isRegularFile(classes.resolve("io/micronaut/aot/serviceindex/generated/ServiceIndexLoader.class")));
        assertEquals(scan.lookups(), indexed.lookups());
        assertTrue(indexed.usesTheIndex(), indexed::err);
        assertTrue(indexed.err().contains("(class path compared: the " + (libraries.size() + 1)
                + " entries of java.class.path, without the Class-Path of the manifests of its JARs, names validated"
                + " against a scan)"), indexed::err);
        assertFalse(indexed.warns(), indexed::err);
    }
}
