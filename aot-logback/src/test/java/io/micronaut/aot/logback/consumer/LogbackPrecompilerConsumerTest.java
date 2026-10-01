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
package io.micronaut.aot.logback.consumer;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.Context;
import io.micronaut.aot.logback.LogbackPrecompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.ILoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Micronaut build plugin writes, in another package than the engine's and with its public types only: build
 * a request from paths, precompile, log the line, write the entries and let Logback find the configurator, whose
 * copied classes are package-private and so have to be on its class loader.
 */
class LogbackPrecompilerConsumerTest {

    @TempDir
    Path temporary;

    @Test
    void aBuildToolPrecompilesWritesAndFindsTheConfiguratorAsAService() throws Exception {
        Path processedResources = Files.createDirectories(temporary.resolve("resources/main"));
        Files.writeString(processedResources.resolve("logback.xml"), """
                <configuration>
                    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                        <encoder>
                            <pattern>%cyan(%d{HH:mm:ss.SSS}) %highlight(%-5level) %magenta(%logger{36}) - %msg%n</pattern>
                        </encoder>
                    </appender>
                    <root level="info">
                        <appender-ref ref="STDOUT"/>
                    </root>
                </configuration>
                """);
        Path classesDirectory = Files.createDirectories(temporary.resolve("classes/java/main"));
        List<Path> runtimeClasspath = new ArrayList<>();
        for (Class<?> type : List.of(LoggerContext.class, Context.class, ILoggerFactory.class)) {
            runtimeClasspath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()));
        }
        Path outputDirectory = temporary.resolve("generated/logback");
        List<String> log = new ArrayList<>();

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                LogbackPrecompiler.Request.builder()
                        .applicationOutput(List.of(processedResources, classesDirectory))
                        .runtimeClasspath(runtimeClasspath)
                        .targetRelease(25)
                        .build());
        log.add(result.message());
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            Path file = outputDirectory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        assertSame(LogbackPrecompiler.Status.GENERATED, result.status(), result.message());
        assertTrue(log.get(0).startsWith("Precompiled logback.xml (application output) into "), log::toString);
        assertEquals(4, result.entries().size());
        assertTrue(LogbackPrecompiler.applicationInputs().contains("logback.xml"));
        try (URLClassLoader application = new URLClassLoader(new URL[] {outputDirectory.toUri().toURL()},
                LoggerContext.class.getClassLoader())) {
            List<Configurator> configurators = new ArrayList<>();
            ServiceLoader.load(Configurator.class, application).forEach(configurators::add);

            assertEquals(1, configurators.size());
            assertEquals("io.micronaut.aot.logback.generated.LogbackConfigurator",
                    configurators.get(0).getClass().getName());
            assertSame(application, configurators.get(0).getClass().getClassLoader());
            // The test class path has a logback-test.xml, so the guard hands over to JoranFallback: both
            // package-private classes link from the configurator written next to them.
            LoggerContext context = new LoggerContext();
            try {
                configurators.get(0).setContext(context);
                assertNotNull(configurators.get(0).configure(context));
            } finally {
                context.stop();
            }
        }
    }
}
