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

import io.micronaut.aot.logback.LogbackTestSupport.Application;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static io.micronaut.aot.logback.LogbackTestSupport.LOGBACK_XML;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract of the API of {@link LogbackPrecompiler} besides what it generates: the names, the entries, which
 * inputs matter and which are required.
 */
class LogbackPrecompilerApiTest {

    @TempDir
    Path temporary;

    // ------------------------------------------------------------------ names

    @Test
    void theGeneratedNamesAreNeutralAndFixed() throws IOException {
        LogbackPrecompiler.Result result = application().precompile(false);

        assertEquals("io.micronaut.aot.logback.generated.LogbackConfigurator", LogbackPrecompiler.CONFIGURATOR_CLASS);
        assertEquals("micronaut.logback.precompiled", LogbackPrecompiler.OPT_OUT_PROPERTY);
        assertEquals(List.of(
                "io/micronaut/aot/logback/generated/LogbackConfigurator.class",
                "io/micronaut/aot/logback/generated/JoranFallback.class",
                "io/micronaut/aot/logback/generated/ClassPathGuard.class",
                "META-INF/services/ch.qos.logback.classic.spi.Configurator"), List.copyOf(result.entries().keySet()));
        assertEquals("io.micronaut.aot.logback.generated.LogbackConfigurator\n", new String(
                result.entries().get("META-INF/services/ch.qos.logback.classic.spi.Configurator"),
                StandardCharsets.UTF_8));
        // The opt-out is a constant of the generated class, by that name.
        assertTrue(new String(result.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY),
                StandardCharsets.ISO_8859_1).contains("micronaut.logback.precompiled"));
        assertTrue(result.message().contains("io.micronaut.aot.logback.generated.LogbackConfigurator"),
                result::message);
    }

    @Test
    void aClosedResultHasThreeEntriesAndAGuardedOneFour() throws IOException {
        Application application = application();

        assertEquals(LogbackPrecompiler.generatedNames(true),
                List.copyOf(application.precompile(true).entries().keySet()));
        assertEquals(LogbackPrecompiler.generatedNames(false),
                List.copyOf(application.precompile(false).entries().keySet()));
        assertEquals(3, LogbackPrecompiler.generatedNames(true).size());
        assertEquals(4, LogbackPrecompiler.generatedNames(false).size());
    }

    @Test
    void theEntriesCannotBeChanged() throws IOException {
        Map<String, byte[]> entries = application().precompile(false).entries();

        assertThrows(UnsupportedOperationException.class, () -> entries.put("other", new byte[0]));
        assertThrows(UnsupportedOperationException.class, entries::clear);
    }

    /** The warnings are a list of their own, empty or not, that the caller cannot change. */
    @Test
    void theWarningsCannotBeChanged() throws IOException {
        List<String> none = application().precompile(false).warnings();
        List<String> one = LogbackTestSupport.application(temporary, Map.of("logback.xml", LOGBACK_XML,
                "bootstrap.properties", "micronaut.config-client.enabled=true\n")).precompile(false).warnings();

        assertEquals(List.of(), none);
        assertEquals(1, one.size(), one::toString);
        for (List<String> warnings : List.of(none, one)) {
            assertThrows(UnsupportedOperationException.class, () -> warnings.add("other"));
            assertThrows(UnsupportedOperationException.class, warnings::clear);
        }
    }

    // ------------------------------------------------------------------ narrow inputs

    @Test
    void anEntryThatMatchesNoInputPatternDoesNotChangeTheResult() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        LogbackPrecompiler.Result baseline = LogbackTestSupport.application(temporary, files).precompile(false);
        assertEquals(LogbackPrecompiler.Status.GENERATED, baseline.status(), baseline::message);

        // What would change the result at the root is ignored anywhere else, whatever it says.
        files.put("com/example/Service.class", "changed");
        files.put("com/example/logback.xml", "<configuration scan=\"true\"/>");
        files.put("com/example/logback-test.xml", LOGBACK_XML);
        files.put("com/example/application.yml", "logger:\n  config: custom.xml\n");
        files.put("conf/application.yml", "logger:\n  config: custom.xml\n");
        files.put("settings.yml", "logger:\n  config: custom.xml\n");
        files.put("application.txt", "logger.config=custom.xml\n");
        files.put("messages.properties", "logger.config=custom.xml\n");
        files.put("META-INF/services/ch.qos.logback.classic.spi.LoggingEventAware", "com.example.Aware\n");
        // Not even a manifest: the engine reads the manifests of the two Logback entries and no other.
        files.put("META-INF/MANIFEST.MF", "not a manifest\n");
        files.put("META-INF/versions/21/com/example/logback.xml", LOGBACK_XML);
        files.put("ch/qos/logback/notes.txt", "not a class");
        files.put("io/micronaut/aot/logback/generated/Other.class", "not a generated name");
        for (String name : files.keySet()) {
            assertFalse(matchesAnInputPattern(name) && !name.equals("logback.xml"), name);
        }
        LogbackPrecompiler.Result changed = LogbackTestSupport.application(temporary, files).precompile(false);

        assertSameResult(baseline, changed);
    }

    @Test
    void anEntryThatMatchesAnInputPatternChangesTheResult() throws IOException {
        Map<String, String> files = Map.of("logback.xml", LOGBACK_XML);
        LogbackPrecompiler.Result baseline = LogbackTestSupport.application(temporary, files).precompile(false);
        assertEquals(LogbackPrecompiler.Status.GENERATED, baseline.status(), baseline::message);

        for (String pattern : LogbackPrecompiler.applicationInputs()) {
            // An entry the pattern matches, holding what matters in an entry of that kind.
            String name = pattern.replace("**/", "deep/").replace("*", "21");
            assertTrue(matchesAnInputPattern(name), name);
            Map<String, String> changed = new LinkedHashMap<>(files);
            changed.put(name, name.equals("logback.xml") ? LOGBACK_XML.replace("WARN", "ERROR")
                    : "logger.config=custom.xml\n");

            LogbackPrecompiler.Result result = LogbackTestSupport.application(temporary, changed).precompile(false);

            assertTrue(result.status() != baseline.status() || !Arrays.equals(
                    result.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY),
                    baseline.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY)),
                    () -> pattern + " does not change the result: " + result.message());
        }
    }

    @Test
    void theInputPatternsCoverWhatTheEngineReads() {
        List<String> patterns = LogbackPrecompiler.applicationInputs();

        assertEquals(8 + 2 * 2 * 6 + 3, patterns.size(), patterns::toString);
        assertTrue(patterns.containsAll(List.of("logback.xml", "logback-test.xml", "logback.groovy",
                "META-INF/versions/*/logback.xml", "META-INF/versions/*/logback-test.xml",
                "META-INF/services/ch.qos.logback.classic.spi.Configurator",
                "ch/qos/logback/**/*.class", "org/slf4j/**/*.class",
                "application*.yml", "bootstrap*.properties", "config/application*.toml", "config/bootstrap*.json",
                "io/micronaut/aot/logback/generated/LogbackConfigurator.class",
                "io/micronaut/aot/logback/generated/JoranFallback.class",
                "io/micronaut/aot/logback/generated/ClassPathGuard.class")), patterns::toString);
        assertThrows(UnsupportedOperationException.class, () -> patterns.add("other"));
    }

    // ------------------------------------------------------------------ the request

    @Test
    void everyRequiredInputHasToBeSet() throws IOException {
        Application application = application();
        List<Path> output = List.of(application.resources());
        List<Path> classpath = LogbackTestSupport.logbackJars();

        LogbackPrecompiler.Request.Builder withoutOutput = LogbackPrecompiler.Request.builder()
                .runtimeClasspath(classpath).targetRelease(25);
        LogbackPrecompiler.Request.Builder withoutClasspath = LogbackPrecompiler.Request.builder()
                .applicationOutput(output).targetRelease(25);
        LogbackPrecompiler.Request.Builder withoutRelease = LogbackPrecompiler.Request.builder()
                .applicationOutput(output).runtimeClasspath(classpath);

        assertEquals("applicationOutput is required",
                assertThrows(IllegalStateException.class, withoutOutput::build).getMessage());
        assertEquals("runtimeClasspath is required",
                assertThrows(IllegalStateException.class, withoutClasspath::build).getMessage());
        assertEquals("targetRelease is required",
                assertThrows(IllegalStateException.class, withoutRelease::build).getMessage());
    }

    @Test
    void aNullArgumentIsRejected() {
        LogbackPrecompiler.Request.Builder builder = LogbackPrecompiler.Request.builder();
        List<Path> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> builder.applicationOutput(null));
        assertThrows(NullPointerException.class, () -> builder.applicationOutput(withNull));
        assertThrows(NullPointerException.class, () -> builder.runtimeClasspath(null));
        assertThrows(NullPointerException.class, () -> builder.runtimeClasspath(withNull));
        assertThrows(NullPointerException.class, () -> builder.workDirectory(null));
        assertThrows(NullPointerException.class, () -> LogbackPrecompiler.precompile(null));
    }

    @Test
    void aRequestKeepsWhatItWasBuiltWith() throws IOException {
        Application application = application();
        List<Path> output = new ArrayList<>(List.of(application.resources(), application.classes()));
        LogbackPrecompiler.Request request = application.request().applicationOutput(output).build();
        output.clear();

        assertEquals(LogbackPrecompiler.Status.GENERATED, LogbackPrecompiler.precompile(request).status());
    }

    @Test
    void withoutAWorkDirectoryATemporaryOneIsUsedAndDeleted() throws Exception {
        Application application = application();
        Path temporaryFiles = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> before = temporaryWorkDirectories(temporaryFiles);

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(LogbackPrecompiler.Request.builder()
                .applicationOutput(List.of(application.resources(), application.classes()))
                .runtimeClasspath(LogbackTestSupport.logbackJars())
                .targetRelease(25)
                .build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        List<Path> created = new ArrayList<>(temporaryWorkDirectories(temporaryFiles));
        created.removeAll(before);
        // Another build on this machine may be precompiling right now: its directory goes away, ours would stay.
        for (int i = 0; i < 50 && created.stream().anyMatch(Files::exists); i++) {
            Thread.sleep(100);
        }
        assertEquals(List.of(), created.stream().filter(Files::exists).toList());
    }

    @Test
    void aWorkDirectoryOfTheCallersIsCreatedAndKept() throws IOException {
        Application application = application();
        Path work = temporary.resolve("build/tmp/precompileLogback");

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().workDirectory(work).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertTrue(Files.isRegularFile(work.resolve("logback-frontend.jar")));
    }

    // ------------------------------------------------------------------ plumbing

    private Application application() throws IOException {
        return LogbackTestSupport.application(temporary, Map.of("logback.xml", LOGBACK_XML));
    }

    private static List<Path> temporaryWorkDirectories(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().startsWith("micronaut-aot-logback")
                    && Files.isDirectory(file)).sorted().toList();
        }
    }

    /**
     * The same status, the same entries byte for byte, the same line but for the time it took and the same
     * warnings.
     */
    private static void assertSameResult(LogbackPrecompiler.Result expected, LogbackPrecompiler.Result actual) {
        assertEquals(expected.status(), actual.status(), actual::message);
        assertEquals(expected.warnings(), actual.warnings());
        assertEquals(List.copyOf(expected.entries().keySet()), List.copyOf(actual.entries().keySet()));
        for (String name : expected.entries().keySet()) {
            assertArrayEquals(expected.entries().get(name), actual.entries().get(name), name);
        }
        assertEquals(withoutTime(expected.message()), withoutTime(actual.message()));
    }

    /** A message without the time it took, which ends it: its digits and " ms". */
    private static String withoutTime(String message) {
        assertTrue(message.endsWith(" ms"), message);
        int end = message.length() - " ms".length();
        int start = end;
        while (start > 0 && message.charAt(start - 1) >= '0' && message.charAt(start - 1) <= '9') {
            start--;
        }
        assertTrue(start < end, message);
        return message.substring(0, start);
    }

    /** Whether an entry name matches one of the Ant-style patterns of {@code applicationInputs()}. */
    private static boolean matchesAnInputPattern(String name) {
        for (String pattern : LogbackPrecompiler.applicationInputs()) {
            StringBuilder regex = new StringBuilder();
            for (String part : pattern.split("(?=\\*\\*/)|(?<=\\*\\*/)|(?=\\*)|(?<=\\*)")) {
                regex.append(switch (part) {
                    case "**/" -> "(.*/)?";
                    case "*" -> "[^/]*";
                    default -> Pattern.quote(part);
                });
            }
            if (name.matches(regex.toString())) {
                return true;
            }
        }
        return false;
    }
}
