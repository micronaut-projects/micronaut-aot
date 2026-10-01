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
package example.logback;

import io.micronaut.aot.logback.LogbackPrecompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackPrecompilationTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void precompilesTheLogbackConfigurationOfAnApplication() throws Exception {
        Path processedResources = processedResources();
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("classes/java/main"));
        Path outputDirectory = temporaryDirectory.resolve("generated/logback");
        List<String> log = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        LogbackPrecompiler.Result result = LogbackPrecompilation.precompile(processedResources, classesDirectory,
            applicationRuntimeClasspath(), outputDirectory, log::add, warnings::add);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result.message());
        assertEquals(List.of(result.message()), log);
        assertEquals(List.of(), warnings);
        assertEquals(4, result.entries().size());
        for (String entry : result.entries().keySet()) {
            assertTrue(Files.isRegularFile(outputDirectory.resolve(entry)), entry);
        }
    }

    /** An application that enables distributed configuration is still precompiled, with a warning. */
    @Test
    void warnsWhenTheApplicationEnablesDistributedConfiguration() throws Exception {
        Path processedResources = processedResources();
        Files.writeString(processedResources.resolve("bootstrap.yml"), """
            micronaut:
              config-client:
                enabled: true
            """);
        Path classesDirectory = Files.createDirectories(temporaryDirectory.resolve("classes/java/main"));
        List<String> log = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        LogbackPrecompiler.Result result = LogbackPrecompilation.precompile(processedResources, classesDirectory,
            applicationRuntimeClasspath(), temporaryDirectory.resolve("generated/logback"), log::add, warnings::add);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result.message());
        assertEquals(List.of(result.message()), log);
        assertEquals(result.warnings(), warnings);
        assertEquals(1, warnings.size(), warnings::toString);
        assertTrue(warnings.get(0).startsWith("The packaged bootstrap.yml of " + processedResources),
            warnings::toString);
    }

    /** The processed resources of an application whose logback.xml the precompiler can compile. */
    private Path processedResources() throws IOException {
        Path processedResources = Files.createDirectories(temporaryDirectory.resolve("resources/main"));
        Files.writeString(processedResources.resolve("logback.xml"), """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder>
                        <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
                    </encoder>
                </appender>
                <root level="info">
                    <appender-ref ref="STDOUT"/>
                </root>
            </configuration>
            """);
        return processedResources;
    }

    /**
     * The runtime class path of the application, which the build resolves and passes: it is not the class path
     * of this test, as it is not the class path of a build tool.
     */
    private static List<Path> applicationRuntimeClasspath() {
        String classpath = System.getProperty("example.logback.runtimeClasspath");
        assertNotNull(classpath, "the build did not pass example.logback.runtimeClasspath");
        List<Path> entries = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            entries.add(Path.of(entry));
        }
        return entries;
    }
}
