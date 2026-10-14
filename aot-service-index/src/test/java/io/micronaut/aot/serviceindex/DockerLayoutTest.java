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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.micronaut.aot.serviceindex.Fixtures.FAREWELL;
import static io.micronaut.aot.serviceindex.Fixtures.LOOKUPS;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layout of the Gradle plugin's JVM Docker images: {@code /home/app/application.jar}, whose manifest
 * {@code Class-Path} names {@code libs/*.jar}, {@code resources/} and {@code classes/}, started with
 * {@code java -jar}. The request is made of what the plugin has before it lays the image out: its runner jar under
 * the name {@code application.jar}, its resources directory under {@code resources} and the libraries under their own
 * names, in the order of the manifest, without {@code classes/}, which the image does not have. With a JDK AOT cache,
 * the plugin packages the resources as a jar instead, and the manifest names it and the libraries only.
 */
class DockerLayoutTest {

    @TempDir
    Path temporary;

    /** The manifests the plugin writes. */
    enum Layout {
        /** {@code runnerJar}: {@code resources/ classes/ libs/...}, with a resources layer. */
        RUNNER,
        /** {@code optimizedRunnerJitJar}: {@code libs/... resources/ classes/}, and no resources layer. */
        OPTIMIZED,
        /**
         * {@code runnerJar} with a JDK AOT cache, which takes no directory: {@code resources/<jar> libs/...}, with the
         * resources layer holding that jar.
         */
        RUNNER_RESOURCES_JAR
    }

    private static final String RESOURCES_JAR = "app-0.1-runner-resources.jar";

    @ParameterizedTest
    @EnumSource(Layout.class)
    void theImageStartsWithItsIndexValidatedAgainstAScan(Layout layout) throws Exception {
        Path build = temporary.resolve("build");
        List<Path> libraries = new ArrayList<>(Fixtures.libraries());
        libraries.add(Fixtures.jar(build.resolve("dependencies/fixture-lib-1.0.jar"), Fixtures.library()));
        Fixtures.Content resourceContent = Fixtures.content().services(FAREWELL, "fixture.ResourceBye");
        Path resources = layout == Layout.RUNNER_RESOURCES_JAR
                ? Fixtures.jar(build.resolve("libs").resolve(RESOURCES_JAR), resourceContent)
                : Fixtures.directory(build.resolve("resources/main"), resourceContent);
        List<String> classPath = new ArrayList<>();
        for (Path library : libraries) {
            classPath.add("libs/" + library.getFileName());
        }
        switch (layout) {
            case RUNNER -> classPath.addAll(0, List.of("resources/", "classes/"));
            case OPTIMIZED -> classPath.addAll(List.of("resources/", "classes/"));
            case RUNNER_RESOURCES_JAR -> classPath.addFirst("resources/" + RESOURCES_JAR);
        }
        Fixtures.Content runner = Fixtures.application().probe()
                .manifest(Map.of("Main-Class", Fixtures.PROBE_CLASS, "Class-Path", String.join(" ", classPath)));
        Path runnerJar = Fixtures.jar(build.resolve("libs/app-0.1-runner.jar"), runner);

        Run scan = Fixtures.javaJar(image(layout, runnerJar, resources, libraries), List.of(VALIDATE), LOOKUPS);
        assertTrue(scan.lookups().contains("fixture.LibraryGreeting"), scan::out);
        assertEquals(layout != Layout.OPTIMIZED, scan.lookups().contains("fixture.ResourceBye"), scan::out);

        ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder()
                .entry(runnerJar, "application.jar")
                .holder(runnerJar);
        if (layout == Layout.RUNNER) {
            request.entry(resources, "resources");
        } else if (layout == Layout.RUNNER_RESOURCES_JAR) {
            request.entry(resources, RESOURCES_JAR);
        }
        for (Path library : libraries) {
            request.entry(library, library.getFileName().toString());
        }
        ServiceIndexGenerator.Result result = ServiceIndexGenerator.generate(request.build());
        Fixtures.apply(runnerJar, result);
        Run indexed = Fixtures.javaJar(image(layout, runnerJar, resources, libraries), List.of(VALIDATE), LOOKUPS);

        assertEquals(scan.lookups(), indexed.lookups());
        assertTrue(indexed.usesTheIndex(), indexed::err);
        int named = libraries.size() + (layout == Layout.OPTIMIZED ? 0 : 1);
        assertTrue(indexed.err().contains("(class path compared: the 1 entry of java.class.path and the " + named
                + " entries of the Class-Path of the manifest of application.jar, names validated against a scan)"),
                indexed::err);
        assertFalse(indexed.warns(), indexed::err);
    }

    /** Lays the image out as the plugin's Dockerfile copies the layers, and returns its {@code application.jar}. */
    private Path image(Layout layout, Path runnerJar, Path resources, List<Path> libraries) throws IOException {
        Path image = temporary.resolve("image-" + System.nanoTime());
        Files.createDirectories(image.resolve("libs"));
        for (Path library : libraries) {
            Files.copy(library, image.resolve("libs").resolve(library.getFileName()));
        }
        if (layout == Layout.RUNNER_RESOURCES_JAR) {
            Files.createDirectories(image.resolve("resources"));
            Files.copy(resources, image.resolve("resources").resolve(RESOURCES_JAR));
        } else if (layout == Layout.RUNNER) {
            try (Stream<Path> files = Files.walk(resources)) {
                for (Path file : files.toList()) {
                    Path target = image.resolve("resources").resolve(resources.relativize(file).toString());
                    if (Files.isDirectory(file)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(file, target);
                    }
                }
            }
        }
        return Files.copy(runnerJar, image.resolve("application.jar"), StandardCopyOption.REPLACE_EXISTING);
    }
}
