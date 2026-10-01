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
package io.micronaut.aot.logback;

import ch.qos.logback.classic.LoggerContext;
import io.micronaut.aot.logback.LogbackTestSupport.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.micronaut.aot.logback.LogbackTestSupport.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What only a JVM of its own shows about a guarded configurator on an unchanged class path: which classes its
 * literal path loads, and what a second call does with real environment variables.
 *
 * <p>Each test starts a JVM whose class path is an application's resources, the generated classes, Logback and
 * the {@link LogbackProbe}, which is its main class.</p>
 */
class LogbackForkedJvmTest {

    private static final String DO_NOT_INVOKE_NEXT = "DO_NOT_INVOKE_NEXT_IF_ANY";

    @TempDir
    Path temporary;

    private Application application;

    private List<Path> classPath;

    @BeforeEach
    void precompileForAnOpenClassPath() throws IOException {
        application = LogbackTestSupport.application(temporary,
                Map.of("logback.xml", LogbackTestSupport.corpus("accept/benchmark-large.xml", null)));
        classPath = new ArrayList<>(List.of(application.resources(), application.generate(false),
                LogbackTestSupport.harness(temporary.resolve("harness"))));
        classPath.addAll(LogbackTestSupport.logbackJars());
    }

    @Test
    void theLiteralPathLoadsNeitherJoranFallbackNorJoran() throws Exception {
        Path classLoading = temporary.resolve("class-load.log");

        List<String> calls = run(1, Map.of(), List.of("-Xlog:class+load:file=" + classLoading));

        assertEquals(DO_NOT_INVOKE_NEXT + "\n" + compiledDescription(), calls.get(0));
        List<String> loaded = Files.readAllLines(classLoading, StandardCharsets.UTF_8);
        assertTrue(loads(loaded, LogbackPrecompiler.CONFIGURATOR_CLASS), "the configurator did not run");
        assertTrue(loads(loaded, LogbackPrecompiler.GUARD_CLASS), "the guard did not run");
        assertTrue(loads(loaded, "ch.qos.logback.core.ConsoleAppender"), "the configuration was not applied");
        assertFalse(loads(loaded, LogbackPrecompiler.FALLBACK_CLASS), "the literal path loaded JoranFallback");
        for (String line : loaded) {
            assertFalse(line.contains(" ch.qos.logback.classic.joran."), line);
            assertFalse(line.contains(" ch.qos.logback.classic.util.DefaultJoranConfigurator "), line);
        }
    }

    /**
     * On a second call the configurator resolves a location as Micronaut does without a configurator: an
     * environment variable named {@code logback.configurationFile}, the {@code logger.config} system property,
     * the {@code LOGGER_CONFIG} environment variable, an environment variable named {@code logger.config}.
     */
    @ParameterizedTest
    @CsvSource({
        "'env:logback.configurationFile, property:logger.config, env:LOGGER_CONFIG, env:logger.config', TRACE",
        "'property:logger.config, env:LOGGER_CONFIG, env:logger.config', DEBUG",
        "'env:LOGGER_CONFIG, env:logger.config', INFO",
        "'env:logger.config', ERROR",
        // Micronaut does not resolve this name, so neither does the configurator: the compiled file stays.
        "'env:LOGBACK_CONFIGURATIONFILE', WARN"
    })
    void aSecondCallAppliesTheFirstVisibleLocation(String set, String expectedRootLevel) throws Exception {
        Map<String, String> levels = new LinkedHashMap<>();
        levels.put("env:logback.configurationFile", "TRACE");
        levels.put("property:logger.config", "DEBUG");
        levels.put("env:LOGGER_CONFIG", "INFO");
        levels.put("env:logger.config", "ERROR");
        levels.put("env:LOGBACK_CONFIGURATIONFILE", "OFF");
        Map<String, String> environment = new LinkedHashMap<>();
        List<String> options = new ArrayList<>();
        for (String source : set.split(", ")) {
            String name = source.substring(source.indexOf(':') + 1);
            // Each source names a file of its own, told apart by its root level.
            String location = LogbackTestSupport.write(temporary.resolve("locations/" + levels.get(source) + ".xml"),
                    LogbackTestSupport.LOGBACK_XML.replace("WARN", levels.get(source))).toString();
            if (source.startsWith("env:")) {
                environment.put(name, location);
            } else {
                options.add("-D" + name + "=" + location);
            }
        }

        List<String> calls = run(2, environment, options);

        // The first call sees none of them: that is Logback's own initialisation.
        assertEquals(DO_NOT_INVOKE_NEXT + "\n" + compiledDescription(), calls.get(0));
        assertTrue(calls.get(1).startsWith(DO_NOT_INVOKE_NEXT + "\n"), calls.get(1));
        assertTrue(calls.get(1).contains("ROOT level=" + expectedRootLevel + " "), calls.get(1));
    }

    /** What Joran builds from the compiled file, which is what the literal path has to build. */
    private String compiledDescription() {
        LoggerContext joran = LogbackDifferential.joran(application.resources().resolve("logback.xml"));
        String description = LogbackDifferential.describe(joran);
        joran.stop();
        assertEquals(description, tree(description), "Joran reports a problem with the compiled file");
        return description;
    }

    private static boolean loads(List<String> classLoading, String className) {
        return classLoading.stream().anyMatch(line -> line.contains(" " + className + " "));
    }

    /**
     * Runs the probe in a new JVM.
     *
     * @param calls       how many configurator calls
     * @param environment environment variables to add
     * @param options     JVM options to add
     * @return for every call, the status, a line break and the description of the context
     */
    private List<String> run(int calls, Map<String, String> environment, List<String> options)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, classPath.stream().map(Path::toString).toList()));
        command.addAll(options);
        command.add(LogbackProbe.class.getName());
        command.add(String.valueOf(calls));
        Path output = Files.createTempFile(temporary, "probe", ".out");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        // Nothing of the test JVM's own environment may name a location.
        builder.environment().keySet().removeIf(name -> name.toLowerCase().contains("logger")
                || name.toLowerCase().contains("logback") || name.equals("JAVA_TOOL_OPTIONS"));
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), "the probe did not finish");
        String text = Files.readString(output, StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), text);
        assertTrue(text.endsWith(LogbackProbe.END_MARKER + "\n"), text);
        List<String> descriptions = new ArrayList<>();
        String body = text.substring(0, text.length() - LogbackProbe.END_MARKER.length() - 1);
        for (String call : body.split(LogbackProbe.CALL_MARKER)) {
            if (!call.isEmpty()) {
                descriptions.add(call);
            }
        }
        assertEquals(calls, descriptions.size(), text);
        return descriptions;
    }
}
