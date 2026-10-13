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

import java.lang.constant.ClassDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static io.micronaut.aot.serviceindex.Fixtures.BEAN_DEFINITIONS;
import static io.micronaut.aot.serviceindex.Fixtures.GREETING;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The build does not run the application: on a class path whose services, bean definition references and static
 * optimization loaders create a marker file and throw from their static initialisers and constructors, the index is
 * generated and none of them ran.
 */
class ApplicationNotRunTest {

    @TempDir
    Path temporary;

    @Test
    void noClassOfTheApplicationIsInitialisedOrInstantiated() throws Exception {
        Path markers = Files.createDirectories(temporary.resolve("markers"));
        ClassDesc loader = ClassDesc.of("io.micronaut.core.optim.StaticOptimizations$Loader");
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.content()
                .services(GREETING, "fixture.ExplodingService")
                .micronaut(BEAN_DEFINITIONS, "fixture.ExplodingReference")
                .put(ServiceIndexGenerator.LOADER_SERVICE, "fixture.ExplodingLoader\n")
                .type("fixture.ExplodingService", Fixtures.explodingClass("fixture.ExplodingService", markers))
                .type("fixture.ExplodingReference", Fixtures.explodingClass("fixture.ExplodingReference", markers))
                .type("fixture.ExplodingLoader", Fixtures.explodingClass("fixture.ExplodingLoader", markers, loader)));

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        try (Stream<Path> created = Files.list(markers)) {
            assertEquals(List.of(), created.toList());
        }
    }
}
