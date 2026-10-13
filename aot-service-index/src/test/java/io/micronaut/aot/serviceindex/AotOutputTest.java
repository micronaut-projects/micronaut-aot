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
import java.util.List;

import static io.micronaut.aot.serviceindex.Fixtures.CONFIGURER;
import static io.micronaut.aot.serviceindex.Fixtures.VALIDATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An application that Micronaut AOT optimized: it carries an {@code ApplicationContextConfigurer} service and two
 * static optimization loaders of its own. The configurer is indexed; the loaders keep running, after the index loader,
 * and their service type is not indexed, because micronaut-core reads it with {@code java.util.ServiceLoader}.
 */
class AotOutputTest {

    private static final String LOADER_TYPE = "io.micronaut.core.optim.StaticOptimizations$Loader";

    @TempDir
    Path temporary;

    @Test
    void theOutputOfAnAotRunIsIndexedAndItsLoadersKept() throws Exception {
        Fixtures.Content application = Fixtures.application()
                .services(CONFIGURER, "fixture.AOTApplicationContextConfigurer")
                .put(ServiceIndexGenerator.LOADER_SERVICE, "# AOT's loaders\nfixture.AotLoaderOne\nfixture.AotLoaderTwo\n")
                .type("fixture.AotLoaderOne", Fixtures.loaderClass("fixture.AotLoaderOne", "fixture.AotOptimizationOne"))
                .type("fixture.AotLoaderTwo", Fixtures.loaderClass("fixture.AotLoaderTwo", "fixture.AotOptimizationTwo"))
                .type("fixture.AotOptimizationOne", Fixtures.emptyClass("fixture.AotOptimizationOne"))
                .type("fixture.AotOptimizationTwo", Fixtures.emptyClass("fixture.AotOptimizationTwo"));
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), application);

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);
        Fixtures.apply(jar, result);
        Fixtures.Index index = Fixtures.load(List.of(jar), result.entries());
        Run run = Fixtures.javaJar(jar, List.of(VALIDATE), List.of("services:" + CONFIGURER,
                "optimization:fixture.AotOptimizationOne", "optimization:fixture.AotOptimizationTwo"));

        assertEquals("io.micronaut.aot.serviceindex.generated.ServiceIndexLoader\n# AOT's loaders\nfixture.AotLoaderOne\n"
                + "fixture.AotLoaderTwo\n", new String(result.entries().get(ServiceIndexGenerator.LOADER_SERVICE),
                StandardCharsets.UTF_8));
        assertEquals(List.of("fixture.AOTApplicationContextConfigurer"), index.standardServices().get(CONFIGURER));
        assertFalse(index.standardServices().containsKey(LOADER_TYPE), index::toString);
        assertEquals("""
                services:io.micronaut.context.ApplicationContextConfigurer [fixture.AOTApplicationContextConfigurer]
                optimization:fixture.AotOptimizationOne [true]
                optimization:fixture.AotOptimizationTwo [true]
                """, run.lookups());
        assertTrue(run.usesTheIndex(), run::err);
        assertFalse(run.warns(), run::err);
    }

    /** The two types that micronaut-core looks up at every start are indexed, with no names, when nothing lists them. */
    @Test
    void theTypesEveryStartLooksUpAreIndexedWithoutAFile() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);
        Fixtures.Index index = Fixtures.load(List.of(jar), result.entries());

        for (String type : ServiceIndexGenerator.ALWAYS_INDEXED) {
            assertEquals(List.of(), index.standardServices().get(type), type);
        }
        assertEquals(List.of("io.micronaut.context.ApplicationContextConfigurer",
                "io.micronaut.context.env.PropertyExpressionResolver"), ServiceIndexGenerator.ALWAYS_INDEXED);
    }
}
