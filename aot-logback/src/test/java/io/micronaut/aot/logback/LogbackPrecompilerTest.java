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
import ch.qos.logback.classic.spi.Configurator;
import io.micronaut.aot.logback.LogbackTestSupport.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.function.UnaryOperator;
import java.util.zip.ZipFile;
import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.SAXParserFactory;

import static io.micronaut.aot.logback.LogbackTestSupport.tree;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The differential gate of {@link LogbackPrecompiler} for a closed class path: every accepted configuration builds
 * exactly the context Joran builds and writes exactly what Joran's writes, every configuration outside the subset
 * generates nothing and says why, and the generated configurator follows the runtime rules.
 *
 * <p>The generated classes are loaded by a child of the test class loader, so these tests use the closed variant:
 * a guarded configurator looks through the loader that defines Logback, which here sees this module's own
 * {@code logback-test.xml}, and would fall back whatever the rule. {@link LogbackGuardTest} runs the same gate for
 * the guarded variant on a class path of its own.</p>
 */
class LogbackPrecompilerTest {

    private static final List<String> RUNTIME_PROPERTIES = List.of("logger.config", "logback.configurationFile",
            LogbackPrecompiler.OPT_OUT_PROPERTY, "logback.debug", "logback.statusListenerClass");

    @TempDir
    Path temporary;

    @AfterEach
    void clearTheRuntimeProperties() {
        RUNTIME_PROPERTIES.forEach(System::clearProperty);
    }

    // ------------------------------------------------------------------ accept corpus

    @ParameterizedTest
    @ValueSource(strings = {"benchmark-large.xml", "launch-default.xml", "rich.xml", "file-appender.xml"})
    void anAcceptedConfigurationBehavesExactlyLikeJoran(String file) throws Exception {
        Path logFile = temporary.resolve("appender.log");
        Path xml = corpus("accept/" + file, logFile);
        LogbackPrecompiler.Result result = compile(xml);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertTrue(result.message().startsWith("Precompiled logback.xml (application output) into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": "), result::message);
        Path classes = write(result);

        // The tree, the appenders, their encoders and every warning, with both contexts alive.
        String[] trees;
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            trees = LogbackProbe.trees(loader, xml);
        }
        assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY.toString(), trees[0]);
        assertEquals(trees[1], trees[2]);
        assertTrue(trees[1].contains("PatternLayoutEncoder:pattern="), trees[1]);

        // What every appender writes, one context at a time: a file appender of each writes the same file.
        String[] outputs;
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            outputs = LogbackProbe.outputs(loader, xml, logFile);
        }
        assertEquals(outputs[0], outputs[1]);
        assertTrue(outputs[0].length() > "--- stderr ---\n(no file)".length(), outputs[0]);
    }

    @Test
    void theHarnessCatchesAConfiguratorThatDiffersFromJoran() throws Exception {
        Path xml = corpus("accept/rich.xml", null);
        // A negative control: the same description with the root logger's level changed.
        LogbackPrecompiler.descriptionHook = description -> {
            Map<String, Object> changed = new LinkedHashMap<>(description);
            List<Object> operations = new ArrayList<>();
            for (Object operation : (List<?>) description.get("operations")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) operation);
                if ("ROOT".equals(copy.get("name"))) {
                    copy.put("level", "ERROR");
                }
                operations.add(copy);
            }
            changed.put("operations", operations);
            return changed;
        };
        Path classes;
        try {
            classes = write(compile(xml));
        } finally {
            LogbackPrecompiler.descriptionHook = UnaryOperator.identity();
        }
        LoggerContext joran = LogbackDifferential.joran(xml);
        LoggerContext generated = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, generated);
        }

        assertNotEquals(LogbackDifferential.describe(joran, generated), LogbackDifferential.describe(generated, joran));
        assertNotEquals(LogbackDifferential.emit(joran), LogbackDifferential.emit(generated));
    }

    @Test
    void theFileAppenderCorpusReallyWritesItsFile() throws Exception {
        Path logFile = temporary.resolve("written.log");
        Path xml = corpus("accept/file-appender.xml", logFile);
        Path classes = write(compile(xml));
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        LogbackDifferential.emit(context);

        assertTrue(LogbackDifferential.read(logFile).contains("héllo wörld"), LogbackDifferential.read(logFile));
    }

    // ------------------------------------------------------------------ reject corpus

    @ParameterizedTest
    @CsvSource({
        "property.xml, <property>",
        "scan.xml, scan=\"true\"",
        "include.xml, <include>",
        "custom-appender.xml, com.example.logging.CustomAppender",
        "conversion-rule.xml, <conversionRule>",
        "unknown-level.xml, VERBOSE",
        "debug.xml, debug=\"true\"",
        "rolling-policy.xml, <rollingPolicy>",
        // What Joran decides in its dependency-analysis phase, or by not calling a setter at all.
        "duplicate-file.xml, '<appender name=\"TWO\"> has the same <file> as <appender name=\"ONE\">'",
        "empty-property.xml, '<target> inside <appender name=\"STDOUT\"> is empty'",
        "name-property.xml, '<name> inside <appender name=\"STDOUT\"> renames the appender'"
    })
    void aConfigurationOutsideTheSubsetGeneratesNothingAndNamesTheElement(String file, String element)
            throws Exception {
        LogbackPrecompiler.Result result = compile(corpus("reject/" + file, null));

        assertEquals(LogbackPrecompiler.Status.STOOD_DOWN, result.status());
        assertEquals(Map.of(), result.entries());
        String line = result.message();
        assertTrue(line.startsWith("No Logback configuration was precompiled because logback.xml (application"
                + " output) is outside what the precompiler can reproduce exactly: "), line);
        assertTrue(line.contains(element), line);
        assertTrue(line.endsWith("; Logback will configure itself with Joran at startup"), line);
        assertFalse(line.contains("\n"), line);
    }

    // ------------------------------------------------------------------ the classes

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void generatingTwiceGivesTheSameBytes(boolean closed) throws Exception {
        Path xml = corpus("accept/rich.xml", null);

        Map<String, byte[]> first = compile(xml, closed).entries();
        Map<String, byte[]> second = compile(xml, closed).entries();

        assertFalse(first.isEmpty());
        assertEquals(List.copyOf(first.keySet()), List.copyOf(second.keySet()));
        for (String name : first.keySet()) {
            assertArrayEquals(first.get(name), second.get(name), name);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyClassIsJava25VerifiesAndLinksNothingDynamically(boolean closed) throws Exception {
        Map<String, byte[]> entries = compile(corpus("accept/rich.xml", null), closed).entries();

        // A closed result has three entries and a guarded one four.
        List<String> classes = closed
                ? List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY, LogbackPrecompiler.FALLBACK_ENTRY)
                : List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY, LogbackPrecompiler.FALLBACK_ENTRY,
                        LogbackPrecompiler.GUARD_ENTRY);
        List<String> expected = new ArrayList<>(classes);
        expected.add(LogbackPrecompiler.SERVICE_ENTRY);
        assertEquals(expected, List.copyOf(entries.keySet()));
        assertEquals(LogbackPrecompiler.CONFIGURATOR_CLASS + "\n",
                new String(entries.get(LogbackPrecompiler.SERVICE_ENTRY), StandardCharsets.UTF_8));
        for (String name : classes) {
            byte[] bytes = entries.get(name);
            ClassModel model = ClassFile.of().parse(bytes);
            assertEquals(69, model.majorVersion(), name);
            assertEquals(0, model.minorVersion(), name);
            assertEquals(List.of(), ClassFile.of().verify(bytes), name);
            assertTrue(model.findAttribute(Attributes.bootstrapMethods()).isEmpty(), name + " uses invokedynamic");
        }
    }

    @Test
    void theFallbackAndTheGuardAreCopiedVerbatimFromTheFrontEnd() throws Exception {
        Map<String, byte[]> entries = compile(corpus("accept/benchmark-large.xml", null), false).entries();
        Path jar = temporary.resolve("frontend.jar");
        try (InputStream in = LogbackPrecompiler.class.getResourceAsStream(LogbackPrecompiler.FRONTEND_RESOURCE)) {
            Files.copy(in, jar);
        }

        try (ZipFile frontEnd = new ZipFile(jar.toFile())) {
            for (String name : List.of(LogbackPrecompiler.FALLBACK_ENTRY, LogbackPrecompiler.GUARD_ENTRY)) {
                try (InputStream in = frontEnd.getInputStream(frontEnd.getEntry(name))) {
                    assertArrayEquals(in.readAllBytes(), entries.get(name), name);
                }
            }
        }
    }

    // ------------------------------------------------------------------ runtime rules

    @Test
    void aSecondCallAfterAResetWithLoggerConfigAppliesThatFile() throws Exception {
        Path classes = write(compile(corpus("accept/benchmark-large.xml", null)));
        Path location = corpus("accept/rich.xml", null);
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            context.reset();
            System.setProperty("logger.config", location.toString());
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @Test
    void loggerConfigIsIgnoredOnTheFirstCall() throws Exception {
        Path xml = corpus("accept/benchmark-large.xml", null);
        Path classes = write(compile(xml));
        System.setProperty("logger.config", corpus("accept/rich.xml", null).toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        assertSameTree(LogbackDifferential.joran(xml), context);
    }

    @Test
    void logbackConfigurationFileHandsTheFirstCallToJoran() throws Exception {
        Path classes = write(compile(corpus("accept/benchmark-large.xml", null)));
        Path location = corpus("accept/launch-default.xml", null);
        System.setProperty("logback.configurationFile", location.toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @ParameterizedTest
    @CsvSource({
        "micronaut.logback.precompiled, false",
        "logback.debug, true",
        "logback.statusListenerClass, ch.qos.logback.core.status.NopStatusListener"
    })
    void theRuntimeOptOutOrARequestForStatusOutputHandsOverToLogbacksDefaultLookup(String property, String value)
            throws Exception {
        Path classes = write(compile(corpus("accept/benchmark-large.xml", null)));
        System.setProperty(property, value);
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        // Logback's default lookup finds this test class path's logback-test.xml.
        assertSameTree(LogbackDifferential.joran(testResource("/logback-test.xml")), context);
        assertTrue(LogbackDifferential.describe(context).contains("AOT-LOGBACK-TESTS"));
    }

    @Test
    void anyOtherValueOfTheOptOutPropertyKeepsTheLiteralPath() throws Exception {
        Path xml = corpus("accept/benchmark-large.xml", null);
        Path classes = write(compile(xml));
        System.setProperty(LogbackPrecompiler.OPT_OUT_PROPERTY, "true");
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
        }

        assertSameTree(LogbackDifferential.joran(xml), context);
        assertFalse(LogbackDifferential.describe(context).contains("AOT-LOGBACK-TESTS"));
    }

    @Test
    void theOptOutWithLoggerConfigEndsInThatFileAfterARefresh() throws Exception {
        Path classes = write(compile(corpus("accept/benchmark-large.xml", null)));
        Path location = corpus("accept/rich.xml", null);
        System.setProperty(LogbackPrecompiler.OPT_OUT_PROPERTY, "false");
        System.setProperty("logger.config", location.toString());
        LoggerContext context = new LoggerContext();
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            context.reset();
            LogbackDifferential.configure(loader, context);
        }

        assertSameTree(LogbackDifferential.joran(location), context);
    }

    @Test
    void aSecondCallWithNothingSetBuildsTheSameTreeAndOutput() throws Exception {
        Path xml = corpus("accept/rich.xml", null);
        Path classes = write(compile(xml));
        LoggerContext context = new LoggerContext();
        String first;
        try (URLClassLoader loader = LogbackDifferential.loader(classes)) {
            LogbackDifferential.configure(loader, context);
            first = LogbackDifferential.describe(context);
            context.reset();
            assertEquals(Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY,
                    LogbackDifferential.configure(loader, context));
        }

        String second = LogbackDifferential.describe(context);
        // reset() drops the statuses the first call recorded, so only the tree itself is compared.
        assertEquals(tree(first), tree(second));
        assertEquals(LogbackDifferential.emit(LogbackDifferential.joran(xml)), LogbackDifferential.emit(context));
    }

    // ------------------------------------------------------------------ isolation and failures

    @Test
    void theFrontEndDoesNotSeeTheCallersContextClassLoader() throws Exception {
        // What a build tool's class path may hold: a JAXP parser factory registration. This one names a class
        // that does not exist, so a lookup through it throws FactoryConfigurationError.
        Path foreign = temporary.resolve("foreign");
        Path services = foreign.resolve("META-INF/services");
        Files.createDirectories(services);
        Files.writeString(services.resolve("javax.xml.parsers.SAXParserFactory"),
                "com.example.build.MissingSaxParserFactory\n", StandardCharsets.UTF_8);
        Thread thread = Thread.currentThread();
        ClassLoader caller = thread.getContextClassLoader();
        LogbackPrecompiler.Result result;
        try (URLClassLoader buildTool = new URLClassLoader(new URL[] {foreign.toUri().toURL()}, caller)) {
            thread.setContextClassLoader(buildTool);
            assertThrows(FactoryConfigurationError.class, SAXParserFactory::newInstance,
                    "the fixture does not break a JAXP lookup through the context class loader");

            result = compile(corpus("accept/benchmark-large.xml", null));

            assertSame(buildTool, thread.getContextClassLoader(), "the caller's context class loader is restored");
        } finally {
            thread.setContextClassLoader(caller);
        }

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(3, result.entries().size(), result::message);
    }

    /**
     * A caller whose own class path holds the module next to a Logback of its own, as a build tool plugin's may:
     * here one whose classes cannot even be defined. The front end runs on the Logback of the runtime class path
     * it is given, so it never meets them.
     */
    @Test
    void theFrontEndRunsOnTheLogbackOfTheRuntimeClassPathNotOnTheCallers() throws Exception {
        Path callersLogback = temporary.resolve("callers-logback");
        for (String name : List.of("ch/qos/logback/classic/LoggerContext.class",
                "ch/qos/logback/classic/joran/JoranConfigurator.class", "ch/qos/logback/core/Context.class",
                "ch/qos/logback/core/spi/ContextAwareBase.class", "org/slf4j/ILoggerFactory.class")) {
            LogbackTestSupport.write(callersLogback.resolve(name), "not a class");
        }
        List<URL> caller = new ArrayList<>();
        caller.add(LogbackTestSupport.jarOf(LogbackPrecompiler.class).toUri().toURL());
        URL frontEnd = LogbackPrecompiler.class.getResource(LogbackPrecompiler.FRONTEND_RESOURCE);
        if ("file".equals(frontEnd.getProtocol())) {
            // The build runs the tests on directories: the front end jar is in the resources, not with the classes.
            Path resources = Path.of(frontEnd.toURI());
            for (int i = Path.of(LogbackPrecompiler.FRONTEND_RESOURCE).getNameCount(); i > 0; i--) {
                resources = resources.getParent();
            }
            caller.add(resources.toUri().toURL());
        }
        caller.add(callersLogback.toUri().toURL());
        Application application = LogbackTestSupport.application(temporary,
                Map.of("logback.xml", LogbackTestSupport.corpus("accept/rich.xml", null)));
        String status;
        String message;

        try (URLClassLoader loader = new URLClassLoader(caller.toArray(URL[]::new),
                ClassLoader.getPlatformClassLoader())) {
            assertThrows(ClassFormatError.class,
                    () -> Class.forName("ch.qos.logback.classic.LoggerContext", false, loader),
                    "the fixture does not put a broken Logback on the caller's class path");
            Class<?> precompiler = Class.forName(LogbackPrecompiler.class.getName(), true, loader);
            assertSame(loader, precompiler.getClassLoader(), "the module is not loaded by the caller's loader");
            Class<?> requestType = Class.forName(LogbackPrecompiler.Request.class.getName(), true, loader);
            Object builder = requestType.getMethod("builder").invoke(null);
            Class<?> builderType = builder.getClass();
            builderType.getMethod("applicationOutput", List.class)
                    .invoke(builder, List.of(application.resources(), application.classes()));
            builderType.getMethod("runtimeClasspath", List.class).invoke(builder, LogbackTestSupport.logbackJars());
            builderType.getMethod("targetRelease", int.class).invoke(builder, 25);

            Object result = precompiler.getMethod("precompile", requestType)
                    .invoke(null, builderType.getMethod("build").invoke(builder));

            status = String.valueOf(result.getClass().getMethod("status").invoke(result));
            message = (String) result.getClass().getMethod("message").invoke(result);
        }

        assertEquals("GENERATED", status, message);
        assertTrue(message.startsWith("Precompiled logback.xml (application output) into "), message);
    }

    @Test
    void anErrorOfTheFrontEndIsAFailureAndGeneratesNothing() throws Exception {
        LogbackPrecompiler.descriptionHook = description -> {
            throw new ServiceConfigurationError("forced provider failure");
        };
        LogbackPrecompiler.Result result;
        try {
            result = compile(corpus("accept/benchmark-large.xml", null));
        } finally {
            LogbackPrecompiler.descriptionHook = UnaryOperator.identity();
        }

        assertEquals(LogbackPrecompiler.Status.FAILED, result.status());
        assertEquals(Map.of(), result.entries());
        assertTrue(result.message().contains("it could not be compiled: "
                + ServiceConfigurationError.class.getName() + ": forced provider failure"), result::message);
    }

    @Test
    void aVirtualMachineErrorIsNotTurnedIntoAFailure() throws Exception {
        Path xml = corpus("accept/benchmark-large.xml", null);
        LogbackPrecompiler.descriptionHook = description -> {
            throw new OutOfMemoryError("forced");
        };
        try {
            assertThrows(OutOfMemoryError.class, () -> compile(xml));
        } finally {
            LogbackPrecompiler.descriptionHook = UnaryOperator.identity();
        }
    }

    @Test
    void theTestedRangeIsNumericAndHalfOpen() {
        assertTrue(LogbackPrecompiler.supported("1.5.37"));
        assertTrue(LogbackPrecompiler.supported("1.5.40"));
        assertFalse(LogbackPrecompiler.supported("1.5.36"));
        assertFalse(LogbackPrecompiler.supported("1.6.0"));
        assertFalse(LogbackPrecompiler.supported("1.6"));
        assertFalse(LogbackPrecompiler.supported("1.4.14"));
        assertFalse(LogbackPrecompiler.supported("1.5.38-SNAPSHOT"));
    }

    // ------------------------------------------------------------------ plumbing

    private static void assertSameTree(LoggerContext expected, LoggerContext actual) {
        assertEquals(tree(LogbackDifferential.describe(expected, actual)),
                tree(LogbackDifferential.describe(actual, expected)));
    }

    /**
     * Copies a corpus file into the temporary directory, replacing the log file token.
     *
     * @param name    the file, relative to the corpus
     * @param logFile what the token becomes, or {@code null} when the file has none
     * @return the copy
     */
    private Path corpus(String name, Path logFile) throws IOException {
        return LogbackTestSupport.write(temporary.resolve("corpus").resolve(name),
                LogbackTestSupport.corpus(name, logFile));
    }

    private static Path testResource(String name) throws URISyntaxException {
        return Path.of(LogbackPrecompilerTest.class.getResource(name).toURI());
    }

    /** Compiles a file as the {@code logback.xml} of an application, for a closed class path. */
    private LogbackPrecompiler.Result compile(Path logbackXml) throws IOException {
        return compile(logbackXml, true);
    }

    private LogbackPrecompiler.Result compile(Path logbackXml, boolean closed) throws IOException {
        return LogbackTestSupport.application(temporary, Map.of("logback.xml", Files.readString(logbackXml)))
                .precompile(closed);
    }

    /** Writes the entries of a result into a directory of its own, as a build plugin does. */
    private Path write(LogbackPrecompiler.Result result) throws IOException {
        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        Path directory = Files.createTempDirectory(temporary, "generated");
        LogbackTestSupport.write(result, directory);
        return directory;
    }
}
