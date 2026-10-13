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
import java.util.Map;

import static io.micronaut.aot.serviceindex.Fixtures.GREETING;
import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A class path that is not the one the index lists: micronaut-core sets the index aside with a {@code WARN} and scans,
 * so every service is still found. That needs the index to list its class path, which is what these tests check.
 */
class ClassPathChangeTest {

    private static final String LIBRARY = "fixture-lib-1.0.jar";

    @TempDir
    Path temporary;

    @Test
    void aJarAddedToTheClassPath() throws Exception {
        Path jar = indexedFatJar();
        Fixtures.jar(temporary.resolve("extra.jar"), Fixtures.library());
        List<String> command = new ArrayList<>(List.of("-cp", "app.jar" + File.pathSeparator + "extra.jar",
                Fixtures.PROBE_CLASS));
        command.addAll(LOOKUPS);

        Run run = Fixtures.java(jar.getParent(), command);

        assertTrue(run.lookups().contains("fixture.LibraryGreeting"), run::out);
        assertSetAside(run, "the class path has [extra.jar");
    }

    @Test
    void theHolderRenamed() throws Exception {
        Path jar = indexedFatJar();
        Path renamed = Files.move(jar, jar.resolveSibling("app-renamed.jar"));

        Run run = Fixtures.javaJar(renamed, List.of(), LOOKUPS);

        assertEquals(Fixtures.APPLICATION_LOOKUPS, run.lookups());
        assertSetAside(run, "the class path lacks [app.jar], which the index was built for, and has [app-renamed.jar");
    }

    @Test
    void aJarRemovedFromTheImage() throws Exception {
        Path application = indexedImage(Fixtures.library(), false);
        Files.delete(application.resolveSibling("libs").resolve(LIBRARY));

        Run run = Fixtures.javaJar(application, List.of(), LOOKUPS);

        assertFalse(run.lookups().contains("fixture.LibraryGreeting"), run::out);
        assertSetAside(run, "the class path lacks [" + LIBRARY);
    }

    @Test
    void aJarReplacedByOneOfTheSameNameAndAnotherSize() throws Exception {
        Path application = indexedImage(Fixtures.library(), false);
        Fixtures.jar(application.resolveSibling("libs").resolve(LIBRARY),
                Fixtures.content().services(GREETING, "fixture.ReplacedLibraryGreeting"));

        Run run = Fixtures.javaJar(application, List.of(), LOOKUPS);

        assertTrue(run.lookups().contains("fixture.ReplacedLibraryGreeting"), run::out);
        assertSetAside(run, "the class path lacks [" + LIBRARY);
    }

    /**
     * What micronaut-core cannot see: a jar replaced by another of the same name and size. The index is used, and
     * only {@code micronaut.service.index.validate} shows that its names are stale.
     */
    @Test
    void aJarReplacedByOneOfTheSameNameAndSizeIsABlindSpot() throws Exception {
        Path application = indexedImage(Fixtures.content().services(GREETING, "fixture.LibraryGreetingA"), true);
        Path library = application.resolveSibling("libs").resolve(LIBRARY);
        long size = Files.size(library);
        Fixtures.jar(library, Fixtures.content().services(GREETING, "fixture.LibraryGreetingB"), true);
        assertEquals(size, Files.size(library));

        Run run = Fixtures.javaJar(application, List.of(), LOOKUPS);
        Run validated = Fixtures.javaJar(application, List.of(VALIDATE), LOOKUPS);

        assertTrue(run.lookups().contains("fixture.LibraryGreetingA"), run::out);
        assertTrue(run.usesTheIndex(), run::err);
        assertFalse(run.warns(), run::err);
        assertNotEquals(0, validated.exit(), validated::toString);
        assertTrue(validated.err().contains("failed its validation: the index differs from a scan of the class path"),
                validated::err);
        assertTrue(validated.err().contains("missing from the index [fixture.LibraryGreetingB]"), validated::err);
    }

    private static void assertSetAside(Run run, String difference) {
        assertFalse(run.usesTheIndex(), run::err);
        assertTrue(run.err().contains(Fixtures.NOT_USING), run::err);
        assertTrue(run.err().contains(difference), run::err);
    }

    private Path indexedFatJar() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        Fixtures.apply(jar, Fixtures.generateFor(jar));
        Run run = Fixtures.javaJar(jar, List.of(), LOOKUPS);
        assertTrue(run.usesTheIndex(), run::err);
        return jar;
    }

    /** An image laid out as {@code application.jar} and {@code libs/}, indexed where it is. */
    private Path indexedImage(Fixtures.Content library, boolean storedLibrary) throws Exception {
        Path image = temporary.resolve("image");
        List<Path> libraries = new ArrayList<>();
        for (Path path : Fixtures.libraries()) {
            libraries.add(Files.copy(path, Files.createDirectories(image.resolve("libs")).resolve(path.getFileName())));
        }
        libraries.add(Fixtures.jar(image.resolve("libs").resolve(LIBRARY), library, storedLibrary));
        List<String> classPath = libraries.stream().map(path -> "libs/" + path.getFileName()).toList();
        Path application = Fixtures.jar(image.resolve("application.jar"), Fixtures.application().probe()
                .manifest(Map.of("Main-Class", Fixtures.PROBE_CLASS, "Class-Path", String.join(" ", classPath))));
        ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder()
                .entry(application, "application.jar").holder(application);
        for (Path path : libraries) {
            request.entry(path, path.getFileName().toString());
        }
        Fixtures.apply(application, ServiceIndexGenerator.generate(request.build()));
        Run run = Fixtures.javaJar(application, List.of(), LOOKUPS);
        assertTrue(run.usesTheIndex(), run::err);
        assertFalse(run.warns(), run::err);
        return application;
    }
}
