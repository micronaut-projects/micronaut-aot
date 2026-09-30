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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guarded variant of {@link LogbackPrecompiler}, which is what a build tool gets unless it declares the class
 * path closed: the differential gate again, and what the configurator does when the class path is no longer the one
 * that was compiled.
 *
 * <p>Every case runs on an {@link IsolatedClassPath}. What is expected of a case is what Logback's own
 * {@code DefaultJoranConfigurator} gives on the same class path.</p>
 */
class LogbackGuardTest {

    private static final String DO_NOT_INVOKE_NEXT = "DO_NOT_INVOKE_NEXT_IF_ANY";

    private static final String INVOKE_NEXT = "INVOKE_NEXT_IF_ANY";

    @TempDir
    Path temporary;

    // ------------------------------------------------------------------ the differential gate, guarded

    @ParameterizedTest
    @ValueSource(strings = {"benchmark-large.xml", "launch-default.xml", "rich.xml", "file-appender.xml"})
    void anAcceptedConfigurationBehavesExactlyLikeJoranBehindTheGuards(String file) throws Exception {
        Path logFile = temporary.resolve("appender.log");
        Application application = LogbackTestSupport.application(temporary,
                Map.of("logback.xml", LogbackTestSupport.corpus("accept/" + file, logFile)));
        Path generated = application.generate(false);
        String xml = application.resources().resolve("logback.xml").toString();

        // The tree, the appenders, their encoders and every warning, with both contexts alive.
        try (IsolatedClassPath classPath = classPath(application.resources(), generated)) {
            String[] trees = classPath.probe("trees", xml);

            assertEquals(DO_NOT_INVOKE_NEXT, trees[0]);
            assertEquals(trees[1], trees[2]);
            assertTrue(trees[1].contains("PatternLayoutEncoder:pattern="), trees[1]);
            assertLiteralPath(classPath);
        }

        // What every appender writes, one context at a time: a file appender of each writes the same file.
        try (IsolatedClassPath classPath = classPath(application.resources(), generated)) {
            String[] outputs = classPath.probe("outputs", xml, logFile.toString());

            assertEquals(outputs[0], outputs[1]);
            assertTrue(outputs[0].length() > "--- stderr ---\n(no file)".length(), outputs[0]);
            assertLiteralPath(classPath);
        }
    }

    // ------------------------------------------------------------------ the guards

    @Test
    void theCompiledFileIsAppliedLiterallyWhileItIsTheOneVisible() throws Exception {
        Application application = application();
        Path generated = application.generate(false);

        try (IsolatedClassPath classPath = classPath(application.resources(), generated)) {
            String[] calls = classPath.probe("configure", 2);

            // Twice: a second call is Micronaut's refresh.
            assertEquals(joranDefault(application.resources()), calls[0]);
            assertEquals(calls[0], calls[1]);
            assertTrue(calls[0].startsWith(DO_NOT_INVOKE_NEXT + "\n"), calls[0]);
            assertTrue(calls[0].contains("io.micronaut.runtime.Micronaut level=INFO"), calls[0]);
            assertLiteralPath(classPath);
        }
    }

    @Test
    void oneChangedByteAtTheSameSizeHandsOverToLogbacksDefaultLookup() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        Path xml = application.resources().resolve("logback.xml");
        String compiled = Files.readString(xml);
        // The same size, and another CRC-32: the logger is now another one.
        String changed = compiled.replace("io.micronaut.runtime.Micronaut", "io.micronaut.runtime.Micronauu");
        assertEquals(compiled.length(), changed.length());
        Files.writeString(xml, changed, StandardCharsets.UTF_8);

        String description = assertFallsBack(application.resources(), generated);

        assertTrue(description.startsWith(DO_NOT_INVOKE_NEXT + "\n"), description);
        assertTrue(description.contains("io.micronaut.runtime.Micronaut level=null"), description);
    }

    @Test
    void anotherSizeHandsOverToLogbacksDefaultLookup() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        Path xml = application.resources().resolve("logback.xml");
        Files.writeString(xml, Files.readString(xml).replace("level=\"WARN\"", "level=\"ERROR\""),
                StandardCharsets.UTF_8);

        String description = assertFallsBack(application.resources(), generated);

        assertTrue(description.startsWith(DO_NOT_INVOKE_NEXT + "\n"), description);
        assertTrue(description.contains("ROOT level=ERROR"), description);
    }

    @Test
    void aMissingLogbackXmlHandsOverToLogbacksDefaultLookup() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        Files.delete(application.resources().resolve("logback.xml"));

        String description = assertFallsBack(application.resources(), generated);

        // Nothing is found, so Logback goes on to its next configurator, as it does without the generated one.
        assertTrue(description.startsWith(INVOKE_NEXT + "\n"), description);
        assertTrue(description.contains("ROOT level=DEBUG effective=DEBUG additive=true appenders=[]"), description);
    }

    @Test
    void aVisibleLogbackTestXmlIsWhatIsApplied() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        // What a later test run on the same output directory adds.
        Path tests = Files.createDirectories(temporary.resolve("test-resources"));
        LogbackTestSupport.write(tests.resolve("logback-test.xml"),
                LogbackTestSupport.LOGBACK_XML.replace("STDOUT", "FROM-LOGBACK-TEST").replace("WARN", "TRACE"));

        String description = assertFallsBack(tests, application.resources(), generated);

        assertTrue(description.startsWith(DO_NOT_INVOKE_NEXT + "\n"), description);
        assertTrue(description.contains("ROOT level=TRACE"), description);
        assertTrue(description.contains("FROM-LOGBACK-TEST"), description);
    }

    @Test
    void anEarlierClassPathEntryWithAnotherLogbackXmlIsWhatIsApplied() throws Exception {
        // The changed file is on the class path before the compiled one, where Joran finds it first.
        Application application = application();
        Path generated = application.generate(false);
        Path earlier = Files.createDirectories(temporary.resolve("mounted"));
        LogbackTestSupport.write(earlier.resolve("logback.xml"),
                LogbackTestSupport.LOGBACK_XML.replace("STDOUT", "MOUNTED"));

        String description = assertFallsBack(earlier, application.resources(), generated);

        assertTrue(description.contains("MOUNTED"), description);
    }

    /**
     * Two loaders: Logback in a parent that sees no {@code logback.xml}, the compiled file, unchanged, and the
     * generated classes in a child. Joran searches with the loader that defines Logback, so it finds nothing
     * there, and the guard has to look through that same loader, not through its own.
     */
    @Test
    void theGuardLooksThroughTheLoaderThatDefinesLogback() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        Path harness = LogbackTestSupport.harness(temporary.resolve("harness"));
        String expected;

        try (IsolatedClassPath logback = new IsolatedClassPath(LogbackTestSupport.logbackJars());
             IsolatedClassPath child = new IsolatedClassPath(
                     List.of(application.resources(), generated, harness), logback)) {
            // The fixture: the loader of the generated classes sees the compiled file, Logback's does not.
            assertNotNull(child.getResource("logback.xml"));
            assertNull(logback.getResource("logback.xml"));
            expected = child.probe("joranDefault");

            String[] calls = child.probe("configure", 1);

            assertEquals(expected, calls[0]);
            assertTrue(calls[0].startsWith(INVOKE_NEXT + "\n"), calls[0]);
            assertTrue(child.loaded(LogbackPrecompiler.GUARD_CLASS), "the guard did not run");
            assertTrue(child.loaded(LogbackPrecompiler.FALLBACK_CLASS), "the configuration was not left to Joran");
        }
        assertNotEquals(expected, compiledDescription());
    }

    /**
     * The guard answers once. A {@code logback.xml} that is edited while the application runs is therefore not
     * picked up by a later call, which is Micronaut's logging refresh: a documented limit, where Joran would read
     * the file again.
     */
    @Test
    void theAnswerOfTheGuardIsKeptForLaterCalls() throws Exception {
        Application application = application();
        Path generated = application.generate(false);
        Path xml = application.resources().resolve("logback.xml");
        String changed = Files.readString(xml).replace("level=\"WARN\"", "level=\"ERROR\"");
        String[] calls;

        try (IsolatedClassPath classPath = classPath(application.resources(), generated)) {
            calls = classPath.probe("configureChangeAndConfigureAgain", xml.toString(), changed);

            assertLiteralPath(classPath);
        }

        assertTrue(calls[0].startsWith(DO_NOT_INVOKE_NEXT + "\n"), calls[0]);
        assertTrue(calls[0].contains("ROOT level=WARN"), calls[0]);
        // reset() drops the statuses the first call recorded, so only the tree itself is compared.
        assertEquals(LogbackTestSupport.tree(calls[0]), LogbackTestSupport.tree(calls[1]));
        // The limit: Logback's own lookup on that class path now gives the edited file.
        assertTrue(joranDefault(application.resources(), generated).contains("ROOT level=ERROR"));
    }

    // ------------------------------------------------------------------ why a second logback.xml stands down

    @Test
    void joranWarnsAboutASecondLogbackXmlSoThePrecompilerStandsDown() throws Exception {
        Application application = application();
        Path dependency = LogbackTestSupport.jar(temporary.resolve("libs/logging-configuration.jar"), null,
                Map.of("logback.xml", LogbackTestSupport.LOGBACK_XML.getBytes(StandardCharsets.UTF_8)));

        // What Logback does with two of them: it reads the first and adds warnings, and warnings make it print its
        // status list at startup. A generated configurator adds none.
        String joran;
        try (IsolatedClassPath classPath = classPath(application.resources(), dependency)) {
            joran = classPath.probe("joranDefault");
        }
        assertTrue(joran.contains("status 1 Resource [logback.xml] occurs multiple times on the classpath."), joran);

        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(dependency);
        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(runtimeClasspath).build());

        assertEquals(LogbackPrecompiler.Status.STOOD_DOWN, result.status(), result::message);
        assertEquals(LogbackTestSupport.STAND_DOWN + "application output and logging-configuration.jar both have a"
                + " logback.xml, which Logback reports at startup" + LogbackTestSupport.JORAN_AT_STARTUP,
                result.message());
        assertEquals(Map.of(), result.entries());
    }

    /**
     * The same with two roots of the application: on an open class path each is a class path entry of its own, so
     * Joran sees both files.
     */
    @Test
    void joranWarnsAboutALogbackXmlInTwoApplicationRootsSoThePrecompilerStandsDown() throws Exception {
        Application application = application();
        Files.copy(application.resources().resolve("logback.xml"), application.classes().resolve("logback.xml"));

        String joran;
        try (IsolatedClassPath classPath = classPath(application.resources(), application.classes())) {
            joran = classPath.probe("joranDefault");
        }
        assertTrue(joran.contains("status 1 Resource [logback.xml] occurs multiple times on the classpath."), joran);

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.STOOD_DOWN, result.status(), result::message);
        assertEquals(LogbackTestSupport.STAND_DOWN + "the application roots " + application.resources() + " and "
                + application.classes() + " both have a logback.xml, which Logback reports at startup"
                + LogbackTestSupport.JORAN_AT_STARTUP, result.message());
        assertEquals(Map.of(), result.entries());
    }

    // ------------------------------------------------------------------ plumbing

    /** An application whose {@code logback.xml} sets one logger besides the root, so that a change shows. */
    private Application application() throws IOException {
        return LogbackTestSupport.application(temporary,
                Map.of("logback.xml", LogbackTestSupport.corpus("accept/benchmark-large.xml", null)));
    }

    /** A class path of the given entries, then the probe and Logback. */
    private IsolatedClassPath classPath(Path... entries) throws IOException {
        List<Path> classPath = new ArrayList<>(List.of(entries));
        Path harness = temporary.resolve("harness");
        if (!Files.isDirectory(harness)) {
            LogbackTestSupport.harness(harness);
        }
        classPath.add(harness);
        classPath.addAll(LogbackTestSupport.logbackJars());
        return new IsolatedClassPath(classPath);
    }

    /** What Logback's own default lookup gives on a class path: the expectation of every case. */
    private String joranDefault(Path... entries) throws Exception {
        try (IsolatedClassPath classPath = classPath(entries)) {
            return classPath.probe("joranDefault");
        }
    }

    /**
     * Asserts that the generated configurator, on a class path that changed, goes through {@code JoranFallback}
     * and gives what Logback's default lookup gives there.
     *
     * @return the description both give
     */
    private String assertFallsBack(Path... entries) throws Exception {
        String expected = joranDefault(entries);
        try (IsolatedClassPath classPath = classPath(entries)) {
            String[] calls = classPath.probe("configure", 1);

            assertEquals(expected, calls[0]);
            assertTrue(classPath.loaded(LogbackPrecompiler.GUARD_CLASS), "the guard did not run");
            assertTrue(classPath.loaded(LogbackPrecompiler.FALLBACK_CLASS), "the configuration was not left to Joran");
        }
        // The case is not vacuous: the compiled configuration is another one.
        assertNotEquals(expected, compiledDescription());
        return expected;
    }

    private String compiledDescription() throws Exception {
        Application application = application();
        try (IsolatedClassPath classPath = classPath(application.resources(), application.generate(false))) {
            String[] calls = classPath.probe("configure", 1);
            assertLiteralPath(classPath);
            return calls[0];
        }
    }

    private static void assertLiteralPath(IsolatedClassPath classPath) {
        assertTrue(classPath.loaded(LogbackPrecompiler.CONFIGURATOR_CLASS), "the configurator did not run");
        assertTrue(classPath.loaded(LogbackPrecompiler.GUARD_CLASS), "the guard did not run");
        assertFalse(classPath.loaded(LogbackPrecompiler.FALLBACK_CLASS), "the literal path loaded JoranFallback");
    }
}
