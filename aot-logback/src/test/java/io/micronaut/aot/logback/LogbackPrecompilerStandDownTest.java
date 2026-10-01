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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.micronaut.aot.logback.LogbackTestSupport.JORAN_AT_STARTUP;
import static io.micronaut.aot.logback.LogbackTestSupport.LOGBACK_XML;
import static io.micronaut.aot.logback.LogbackTestSupport.STAND_DOWN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When {@link LogbackPrecompiler} generates nothing, and what it says: through {@code precompile(Request)},
 * with directories and jars as class path entries.
 */
class LogbackPrecompilerStandDownTest {

    @TempDir
    Path temporary;

    // ------------------------------------------------------------------ what is compiled

    @Test
    void precompilesTheLogbackXmlOfTheApplicationOutput() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertTrue(result.message().startsWith("Precompiled logback.xml (application output) into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": 1 appender, 1 pattern, "), result::message);
        assertTrue(result.message().endsWith(" ms"), result::message);
        assertEquals(LOGBACK_XML, Files.readString(application.resources().resolve("logback.xml")),
                "logback.xml stays where it is for the fallbacks");
    }

    /** A {@code logback.xml} that only a dependency carries is read from it, and the message names it. */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void precompilesALogbackXmlThatOnlyADependencyCarries(boolean jar) throws IOException {
        Application application = application(Map.of());
        Path dependency;
        if (jar) {
            dependency = LogbackTestSupport.jar(temporary.resolve("libs/logging-configuration.jar"), null,
                    Map.of("logback.xml", LOGBACK_XML.getBytes(StandardCharsets.UTF_8)));
        } else {
            dependency = temporary.resolve("other-project/build/resources/main");
            LogbackTestSupport.write(dependency.resolve("logback.xml"), LOGBACK_XML);
        }
        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(dependency);

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(runtimeClasspath).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertTrue(result.message().startsWith("Precompiled logback.xml ("
                + (jar ? "logging-configuration.jar" : dependency.toString()) + ") into "
                + LogbackPrecompiler.CONFIGURATOR_CLASS + ": 1 appender, 1 pattern, "), result::message);
    }

    @Test
    void aJarIsAnApplicationOutputToo() throws IOException {
        Path jar = LogbackTestSupport.jar(temporary.resolve("application.jar"), null, Map.of(
                "com/example/Application.class", new byte[] {1},
                "logback.xml", LOGBACK_XML.getBytes(StandardCharsets.UTF_8)));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(LogbackPrecompiler.Request.builder()
                .applicationOutput(List.of(jar))
                .runtimeClasspath(LogbackTestSupport.logbackJars())
                .targetRelease(25)
                .build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertTrue(result.message().startsWith("Precompiled logback.xml (application output) into "),
                result::message);
    }

    /**
     * A configuration file of an application root is read even when an earlier root has one of the same name:
     * where the roots are class path entries of their own, Micronaut can merge both. The message names the root
     * of a file that an earlier root shadows.
     */
    @Test
    void theConfigurationFileOfEveryApplicationRootIsRead() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "application.properties", "micronaut.application.name=demo\n"));
        LogbackTestSupport.write(application.classes().resolve("application.properties"),
                "logger.config=custom.xml\n");

        LogbackPrecompiler.Result resourcesFirst = application.precompile(false);
        LogbackPrecompiler.Result classesFirst = LogbackPrecompiler.precompile(application.request()
                .applicationOutput(List.of(application.classes(), application.resources())).build());

        assertStoodDown(resourcesFirst, "the packaged application.properties of " + application.classes()
                + " may set logger.config");
        assertStoodDown(classesFirst, "the packaged application.properties may set logger.config");
    }

    /**
     * A configuration file of a dependency is read as one of the application: Micronaut reads it when the
     * application has none of that name, and can merge it with the application's own when it has one.
     */
    @ParameterizedTest
    @ValueSource(strings = {"jar application.yml", "directory application.yml", "jar config/bootstrap.properties",
        "jar application-prod.toml"})
    void standsDownOnAConfigurationFileOfADependencyThatMaySetALocation(String condition) throws IOException {
        // The application's own application.yml names no location, and does not hide the dependency's.
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "application.yml", "micronaut:\n  application:\n    name: demo\n"));
        boolean jar = condition.startsWith("jar ");
        String name = condition.substring(condition.indexOf(' ') + 1);
        String content = switch (name) {
            case "application.yml" -> "logger:\n  levels:\n    com.example: DEBUG\n  config: custom.xml\n";
            case "config/bootstrap.properties" -> "logback.configurationFile=custom.xml\n";
            case "application-prod.toml" -> "logger = { config = \"custom.xml\" }\n";
            default -> throw new IllegalArgumentException(condition);
        };
        Path dependency = dependency(jar, Map.of(name, content, "com/example/Defaults.class", "not a class"));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(withLogback(dependency)).build());

        assertStoodDown(result, "the packaged " + name + " of "
                + (jar ? "logging-defaults.jar" : dependency.toString()) + " may set logger.config or"
                + " logback.configurationFile, which Micronaut applies only without a Configurator service");
    }

    /**
     * In a dependency, as in the application, only an {@code application*} or {@code bootstrap*} file that names a
     * location counts: at the root, where Micronaut reads it, or under {@code config/}, a conservative extra that
     * Micronaut reads only when {@code overrideConfigLocations} adds it. Logger levels, and files elsewhere, change
     * nothing.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aDependencyWhoseConfigurationNamesNoLocationIsStillPrecompiled(boolean jar) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        Path dependency = dependency(jar, Map.of(
                "application.yml", "logger:\n  levels:\n    com.example: DEBUG\n",
                "bootstrap.properties", "micronaut.application.name=library\n",
                "META-INF/application.yml", "logger:\n  config: custom.xml\n",
                "com/example/application.properties", "logger.config=custom.xml\n"));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(withLogback(dependency)).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
    }

    /** The application output is read before the runtime class path, so a message names its file first. */
    @Test
    void theApplicationsOwnConfigurationFileIsNamedBeforeADependencys() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "application.properties", "logger.config=custom.xml\n"));
        Path dependency = dependency(true, Map.of("application.properties", "logger.config=other.xml\n"));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(withLogback(dependency)).build());

        assertStoodDown(result, "because the packaged application.properties may set logger.config");
    }

    /** A directory is listed in the order of its names, so what a message names does not depend on the file system. */
    @Test
    void theMessageNamesTheFirstEntryInTheOrderOfTheNames() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        for (String name : List.of("config/application.yml", "bootstrap.yml", "application.yml",
                "application-prod.yml")) {
            files.put(name, "logger:\n  config: custom.xml\n");
        }

        assertStoodDown(application(files).precompile(false), "the packaged application-prod.yml may set");
    }

    /** Logback exploded into directories is the same class path, and its version is still read. */
    @Test
    void logbackInDirectoriesIsReadAsItIsInJars() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        List<Path> directories = new ArrayList<>();
        for (Path jar : LogbackTestSupport.logbackJars()) {
            directories.add(LogbackTestSupport.explode(jar,
                    temporary.resolve("exploded").resolve(String.valueOf(jar.getFileName()))));
        }
        LogbackPrecompiler.Request request = application.request().runtimeClasspath(directories).build();

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(request);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertArrayEquals(application.precompile(false).entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY),
                result.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY));

        // The manifest of a directory is what gives its version.
        Path manifest = directories.get(0).resolve("META-INF/MANIFEST.MF");
        Files.writeString(manifest, Files.readString(manifest)
                .replaceAll("Implementation-Version: \\S+", "Implementation-Version: 1.4.14"));
        assertStoodDown(LogbackPrecompiler.precompile(request), "logback-classic 1.4.14 and logback-core ");
        Files.delete(manifest);
        assertStoodDown(LogbackPrecompiler.precompile(request),
                "logback-classic or logback-core declares no Implementation-Version");
    }

    /**
     * A packaged configuration that sets logger levels, as most do, does not stand the precompiler down: only a
     * {@code config} key next to the word {@code logger} does.
     */
    @Test
    void aPackagedConfigurationWithoutAConfigKeyIsStillPrecompiled() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "application.yml", """
                        micronaut:
                          application:
                            name: demo
                          config-client:
                            enabled: false
                        logger:
                          levels:
                            com.example: DEBUG
                        """,
                "application-test.toml", "[logger.levels]\n\"com.example\" = \"DEBUG\"\n",
                "config/application.json", "{\"logger\":{\"levels\":{\"com.example\":\"DEBUG\"}}}\n"));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(List.of(), result.warnings());
    }

    /**
     * A packaged configuration that imports other configuration with {@code micronaut.config.import} stands the
     * precompiler down, in every format and whatever the import names: a class path resource of any name, which
     * Micronaut reads and which may set {@code logger.config}, as well as a file, which the build cannot read.
     * The imported file itself is not read.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application.properties classpath", "application.properties indexed",
        "application.properties structured", "application.properties file", "application.yml nested list",
        "application.yml flat key", "application.yml flow style", "application.json on one line",
        "application.toml table", "application.toml dotted key", "application.toml structured table",
        "application.groovy closure",
        "bootstrap.yml of a dependency jar"})
    void standsDownOnAPackagedConfigurationThatImportsConfiguration(String condition) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        // What Micronaut would import: no application* or bootstrap* name, so the scan never reads it.
        files.put("logging-settings.properties", "logger.config=custom.xml\n");
        String name = condition.substring(0, condition.indexOf(' '));
        String content = switch (condition) {
            case "application.properties classpath" -> """
                    micronaut.application.name=demo
                    micronaut.config.import=classpath://logging-settings.properties
                    """;
            case "application.properties indexed" -> "micronaut.config.import[0]=optional:classpath*://shared\n";
            case "application.properties structured" -> "micronaut.config.import.vault.provider=vault\n";
            case "application.properties file" -> "micronaut.config.import=optional:file:///etc/demo/logging\n";
            case "application.yml nested list" -> """
                    micronaut:
                      application:
                        name: demo
                      config:
                        import:
                          - "classpath://logging-settings.properties"
                    """;
            case "application.yml flat key" -> "micronaut.config.import: classpath*://logging-settings\n";
            case "application.yml flow style" -> "micronaut: {config: {import: [classpath://logging-settings]}}\n";
            case "application.json on one line" ->
                    "{\"micronaut\":{\"config\":{\"import\":\"classpath://logging-settings.properties\"}}}";
            case "application.toml table" -> "[micronaut.config]\nimport = [\"classpath://logging-settings\"]\n";
            case "application.toml dotted key" -> "[micronaut]\nconfig.import = \"classpath://logging-settings\"\n";
            case "application.toml structured table" ->
                    "[micronaut.config.import]\nprovider = \"vault\"\npath = \"secret/demo\"\n";
            case "application.groovy closure" -> "micronaut { config { 'import' = 'classpath://logging-settings' } }\n";
            case "bootstrap.yml of a dependency jar" ->
                    "micronaut:\n  config:\n    import: classpath://logging-settings\n";
            default -> throw new IllegalArgumentException(condition);
        };
        Path dependency = null;
        if (condition.endsWith("dependency jar")) {
            dependency = dependency(true, Map.of(name, content));
        } else {
            files.put(name, content);
        }
        Application application = application(files);

        LogbackPrecompiler.Result result = dependency == null ? application.precompile(false)
                : LogbackPrecompiler.precompile(
                        application.request().runtimeClasspath(withLogback(dependency)).build());

        assertStoodDown(result, "because the packaged " + name
                + (dependency == null ? "" : " of logging-defaults.jar") + " may import configuration with"
                + " micronaut.config.import, which is not read here and may set logger.config or"
                + " logback.configurationFile; Micronaut applies such a location only without a Configurator service");
    }

    /**
     * Only an {@code import} key, in a file that has the word {@code config}, counts as an import: the word in a
     * comment or a value, a key that only starts with it, a Groovy {@code import} statement and an {@code import}
     * key in a file without that word do not stand the precompiler down.
     */
    @Test
    void aPackagedConfigurationThatOnlyMentionsImportIsStillPrecompiled() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "application.yml", """
                        # Import nothing here: the settings are below.
                        micronaut:
                          application:
                            name: import-service
                          config-client:
                            enabled: false
                        jpa:
                          default:
                            properties:
                              hibernate.hbm2ddl.import_files: data.sql
                        """,
                "application.properties", "app.important=true\napp.import-batch-size=10\n",
                "application.groovy",
                "import java.time.Duration\n\napp { config = Duration.ofSeconds(5).toString() }\n",
                "application.json", "{\"imports\":{\"config\":\"none\"}}\n",
                "application-dev.yml", "seed:\n  import: data.sql\n"));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(List.of(), result.warnings());
    }

    // ------------------------------------------------------------------ warnings

    /**
     * A packaged configuration that enables Micronaut's distributed configuration client does not stand the
     * precompiler down, but adds a warning that names the file and its root or jar: a location that the
     * configuration server supplies would not be applied. Every format the scan reads counts, in an application
     * root and in a dependency, at the root and under {@code config/}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"application.properties", "bootstrap.properties camel case", "bootstrap.yml nested",
        "bootstrap.yaml flat key", "application-prod.yml yes", "application.yml flow style",
        "application.json on one line", "application.toml table", "bootstrap.toml inline table",
        "application.groovy closure", "config/bootstrap.yml", "bootstrap.yml of the class directory",
        "bootstrap.yml of a dependency jar", "application.properties of a dependency directory"})
    void warnsOnAPackagedConfigurationThatEnablesTheConfigurationClient(String condition) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        files.put("application.yml", "micronaut:\n  application:\n    name: demo\n");
        String name = condition.contains(" ") ? condition.substring(0, condition.indexOf(' ')) : condition;
        String content = switch (condition) {
            case "application.properties", "application.properties of a dependency directory" ->
                    "micronaut.application.name=demo\nmicronaut.config-client.enabled=true\n";
            case "bootstrap.properties camel case" -> "micronaut.configClient.enabled = TRUE \n";
            case "bootstrap.yml nested", "config/bootstrap.yml", "bootstrap.yml of the class directory",
                 "bootstrap.yml of a dependency jar" -> """
                    micronaut:
                      application:
                        name: demo
                      config-client:
                        enabled: true
                    """;
            case "bootstrap.yaml flat key" -> "micronaut.config-client.enabled: true\n";
            case "application-prod.yml yes" -> "micronaut:\n  config-client:\n    enabled: yes\n";
            case "application.yml flow style" -> "micronaut: {config-client: {enabled: 'true'}}\n";
            case "application.json on one line" -> "{\"micronaut\":{\"config-client\":{\"enabled\":true}}}";
            case "application.toml table" -> "[micronaut.config-client]\nenabled = true\n";
            case "bootstrap.toml inline table" -> "[micronaut]\nconfig-client = { enabled = true }\n";
            case "application.groovy closure" -> "micronaut { 'config-client' { enabled = true } }\n";
            default -> throw new IllegalArgumentException(condition);
        };
        Path dependency = null;
        if (condition.endsWith("dependency jar")) {
            dependency = dependency(true, Map.of(name, content));
        } else if (condition.endsWith("dependency directory")) {
            dependency = dependency(false, Map.of(name, content));
        } else if (!condition.endsWith("class directory")) {
            files.put(name, content);
        }
        Application application = application(files);
        if (condition.endsWith("class directory")) {
            LogbackTestSupport.write(application.classes().resolve(name), content);
        }
        String where;
        if (condition.endsWith("dependency jar")) {
            where = "logging-defaults.jar";
        } else if (dependency != null) {
            where = dependency.toString();
        } else {
            where = (condition.endsWith("class directory") ? application.classes() : application.resources())
                    .toString();
        }

        LogbackPrecompiler.Result result = dependency == null ? application.precompile(false)
                : LogbackPrecompiler.precompile(
                        application.request().runtimeClasspath(withLogback(dependency)).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(4, result.entries().size());
        assertTrue(result.message().startsWith("Precompiled logback.xml (application output) into "),
                result::message);
        assertEquals(List.of(configClientWarning(name + " of " + where)), result.warnings());
    }

    /** One line for each file that enables the client, in class path order, and each one names its file. */
    @Test
    void warnsOnceForEachFileThatEnablesTheConfigurationClient() throws IOException {
        String enabled = "micronaut.config-client.enabled=true\n";
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "bootstrap.properties", enabled, "application.properties", enabled));
        LogbackTestSupport.write(application.classes().resolve("bootstrap.properties"), enabled);
        Path dependency = dependency(true, Map.of("bootstrap.properties", enabled));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(withLogback(dependency)).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(List.of(
                configClientWarning("application.properties of " + application.resources()),
                configClientWarning("bootstrap.properties of " + application.resources()),
                configClientWarning("bootstrap.properties of " + application.classes()),
                configClientWarning("bootstrap.properties of logging-defaults.jar")), result.warnings());
        for (String line : result.warnings()) {
            assertFalse(line.contains("\n"), line);
        }
    }

    /**
     * A client that is turned off, a mention of {@code config-client} without {@code enabled} set to {@code true},
     * an {@code enabled} key of something else, and a file the scan does not read give no warning.
     */
    @ParameterizedTest
    @ValueSource(strings = {"bootstrap.yml enabled false", "bootstrap.properties enabled false",
        "bootstrap.properties another key enabled", "bootstrap.yml mention without enabled",
        "application.yml enabled without the client", "application.json enabled false",
        "application.toml enabled false", "application.groovy enabled false",
        "META-INF/bootstrap.yml not read", "com/example/application.properties not read"})
    void aConfigurationClientThatIsNotEnabledGivesNoWarning(String condition) throws IOException {
        String name = condition.substring(0, condition.indexOf(' '));
        String content = switch (condition) {
            case "bootstrap.yml enabled false" -> """
                    micronaut:
                      config-client:
                        enabled: false
                      server:
                        port: 8080
                    """;
            case "bootstrap.properties enabled false" -> "micronaut.config-client.enabled=false\n";
            case "bootstrap.properties another key enabled" ->
                    "micronaut.config-client.read-timeout=30s\nmicronaut.metrics.enabled=true\n";
            case "bootstrap.yml mention without enabled" -> "micronaut:\n  config-client:\n    read-timeout: 30s\n";
            case "application.yml enabled without the client" -> "micronaut:\n  metrics:\n    enabled: true\n";
            case "application.json enabled false" -> "{\"micronaut\":{\"config-client\":{\"enabled\":false}}}";
            case "application.toml enabled false" -> "[micronaut.config-client]\nenabled = false\n";
            case "application.groovy enabled false" -> "micronaut { 'config-client' { enabled = false } }\n";
            case "META-INF/bootstrap.yml not read" -> "micronaut:\n  config-client:\n    enabled: true\n";
            case "com/example/application.properties not read" -> "micronaut.config-client.enabled=true\n";
            default -> throw new IllegalArgumentException(condition);
        };
        Application application = application(Map.of("logback.xml", LOGBACK_XML, name, content));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        assertEquals(List.of(), result.warnings());
    }

    /**
     * A stand-down or a failure has no warnings, also when a file enables the client: in the same file as the
     * reason, before it in class path order, or anywhere when a later rule stands down or fails.
     */
    @ParameterizedTest
    @ValueSource(strings = {"the same file sets logger.config", "an earlier file enables the client",
        "a dependency imports configuration", "subset rejection", "no logback.xml", "taken generated name"})
    void aResultThatGeneratesNothingHasNoWarnings(String condition) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        files.put("application.yml", "micronaut:\n  config-client:\n    enabled: true\n");
        List<Path> runtimeClasspath = LogbackTestSupport.logbackJars();
        LogbackPrecompiler.Status status = LogbackPrecompiler.Status.STOOD_DOWN;
        switch (condition) {
            case "the same file sets logger.config" -> files.put("application.yml",
                    "micronaut:\n  config-client:\n    enabled: true\nlogger:\n  config: custom.xml\n");
            // application.yml comes before bootstrap.properties in the order of the names.
            case "an earlier file enables the client" ->
                    files.put("bootstrap.properties", "logger.config=custom.xml\n");
            case "a dependency imports configuration" -> runtimeClasspath = withLogback(dependency(true,
                    Map.of("bootstrap.yml", "micronaut:\n  config:\n    import: classpath://logging\n")));
            case "subset rejection" -> files.put("logback.xml", LOGBACK_XML.replace("<configuration>",
                    "<configuration>\n    <property name=\"APP\" value=\"demo\"/>"));
            case "no logback.xml" -> files.remove("logback.xml");
            case "taken generated name" -> {
                files.put(LogbackPrecompiler.CONFIGURATOR_ENTRY, "not ours");
                status = LogbackPrecompiler.Status.FAILED;
            }
            default -> throw new IllegalArgumentException(condition);
        }

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application(files).request().runtimeClasspath(runtimeClasspath).build());

        assertEquals(status, result.status(), result::message);
        assertEquals(Map.of(), result.entries());
        assertEquals(List.of(), result.warnings());
    }

    // ------------------------------------------------------------------ stand-downs

    @ParameterizedTest
    @ValueSource(strings = {"no logback.xml", "logback-test.xml", "logback.groovy", "versioned logback.xml",
        "application Configurator", "dependency Configurator", "application.properties logger.config",
        "application.yml logger config", "bootstrap.yml configurationFile", "application.toml logger table",
        "application.toml inline table", "application.yml flow style", "application.json on one line",
        "application.groovy closure", "application.yml merged anchor", "Logback outside the range",
        "mismatched versions", "subset rejection"})
    void standsDownWithOneInformationalLine(String condition) throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("logback.xml", LOGBACK_XML);
        List<Path> dependencies = new ArrayList<>(LogbackTestSupport.logbackJars());
        String reason;
        switch (condition) {
            case "no logback.xml" -> {
                files.remove("logback.xml");
                reason = "there is no logback.xml";
            }
            case "logback-test.xml" -> {
                files.put("logback-test.xml", LOGBACK_XML);
                reason = "application output has logback-test.xml";
            }
            case "logback.groovy" -> {
                files.put("logback.groovy", "root(WARN)\n");
                reason = "application output has logback.groovy";
            }
            case "versioned logback.xml" -> {
                files.put("META-INF/versions/21/logback.xml", LOGBACK_XML);
                reason = "application output has META-INF/versions/21/logback.xml";
            }
            case "application Configurator" -> {
                files.put(LogbackPrecompiler.SERVICE_ENTRY, "com.example.MyConfigurator\n");
                reason = "application output already registers a Logback Configurator ("
                        + LogbackPrecompiler.SERVICE_ENTRY + ")";
            }
            case "dependency Configurator" -> {
                dependencies.add(LogbackTestSupport.jar(temporary.resolve("libs/aot-configurator.jar"), null,
                        Map.of(LogbackPrecompiler.SERVICE_ENTRY,
                                "io.micronaut.aot.StaticLogbackConfiguration\n".getBytes(StandardCharsets.UTF_8))));
                reason = "aot-configurator.jar already registers a Logback Configurator";
            }
            case "application.properties logger.config" -> {
                files.put("application.properties", "micronaut.application.name=demo\nlogger.config=custom.xml\n");
                reason = "the packaged application.properties may set logger.config";
            }
            case "application.yml logger config" -> {
                files.put("application.yml", "logger:\n  levels:\n    com.example: DEBUG\n  config: custom.xml\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "bootstrap.yml configurationFile" -> {
                files.put("config/bootstrap.yml", "logback:\n  configurationFile: custom.xml\n");
                reason = "the packaged config/bootstrap.yml may set";
            }
            case "application.toml logger table" -> {
                files.put("application.toml", """
                        [micronaut.application]
                        name = "demo"

                        [logger]
                        config = "custom.xml"
                        """);
                reason = "the packaged application.toml may set logger.config";
            }
            case "application.toml inline table" -> {
                files.put("application-prod.toml", "logger = { config = \"custom.xml\" }\n");
                reason = "the packaged application-prod.toml may set logger.config";
            }
            case "application.yml flow style" -> {
                files.put("application.yml", "logger: {config: custom.xml}\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "application.json on one line" -> {
                files.put("application.json", "{\"logger\":{\"config\":\"custom.xml\"}}");
                reason = "the packaged application.json may set logger.config";
            }
            case "application.groovy closure" -> {
                files.put("application.groovy", "logger { config = 'custom.xml' }\n");
                reason = "the packaged application.groovy may set logger.config";
            }
            case "application.yml merged anchor" -> {
                // The config key stands before the logger key, and on another level.
                files.put("application.yml", "shared: &shared\n  Config: custom.xml\nLogger:\n  <<: *shared\n");
                reason = "the packaged application.yml may set logger.config";
            }
            case "Logback outside the range" -> {
                dependencies = fakeLogback("1.4.14", "1.4.14");
                reason = "Logback 1.4.14 is outside the tested range [1.5.37, 1.6)";
            }
            case "mismatched versions" -> {
                dependencies = fakeLogback("1.5.37", "1.5.38");
                reason = "logback-classic 1.5.37 and logback-core 1.5.38 differ";
            }
            case "subset rejection" -> {
                files.put("logback.xml", LOGBACK_XML.replace("<configuration>",
                        "<configuration>\n    <property name=\"APP\" value=\"demo\"/>"));
                reason = "logback.xml (application output) is outside what the precompiler can reproduce exactly:"
                        + " <property> (line 2) is not supported";
            }
            default -> throw new IllegalArgumentException(condition);
        }
        Application application = application(files);

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(dependencies).build());

        assertStoodDown(result, reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no logback-classic", "no logback-core", "no slf4j-api", "no Implementation-Version"})
    void standsDownWithoutTheLoggingLibraries(String condition) throws IOException {
        List<Path> dependencies = new ArrayList<>(LogbackTestSupport.logbackJars());
        String reason;
        switch (condition) {
            case "no logback-classic" -> {
                dependencies.remove(0);
                reason = "logback-classic is not on the class path";
            }
            case "no logback-core" -> {
                dependencies.remove(1);
                reason = "logback-core is not on the class path";
            }
            case "no slf4j-api" -> {
                dependencies.remove(2);
                reason = "slf4j-api is not on the class path";
            }
            case "no Implementation-Version" -> {
                dependencies = fakeLogback(null, "1.5.37");
                reason = "logback-classic or logback-core declares no Implementation-Version";
            }
            default -> throw new IllegalArgumentException(condition);
        }

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application(Map.of("logback.xml", LOGBACK_XML)).request().runtimeClasspath(dependencies).build());

        assertStoodDown(result, reason);
    }

    @Test
    void standsDownWhenTheApplicationCarriesALoggingClassOfItsOwn() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        Files.createDirectories(application.classes().resolve("org/slf4j/impl"));
        Files.write(application.classes().resolve("org/slf4j/impl/StaticLoggerBinder.class"), new byte[] {1});

        assertStoodDown(application.precompile(false), "the application output carries its own Logback or SLF4J"
                + " class org/slf4j/impl/StaticLoggerBinder.class");
    }

    /**
     * Two roots of the application that both hold a {@code logback.xml}: on an open class path they are two
     * class path entries, and Logback reports the file that occurs twice. {@link LogbackGuardTest} shows that.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void standsDownWhenTwoApplicationRootsHoldALogbackXml(boolean closed) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        LogbackTestSupport.write(application.classes().resolve("logback.xml"), LOGBACK_XML);

        assertStoodDown(application.precompile(closed), "the application roots " + application.resources()
                + " and " + application.classes() + " both have a logback.xml, which Logback reports at startup");
        assertStoodDown(LogbackPrecompiler.precompile(application.request().closedClassPath(closed)
                .applicationOutput(List.of(application.classes(), application.resources())).build()),
                "the application roots " + application.classes() + " and " + application.resources()
                        + " both have a logback.xml, which Logback reports at startup");
    }

    /** What an annotation processor writes goes to the class output, not to the resources. */
    @Test
    void standsDownOnAConfiguratorServiceFileThatOnlyAClassDirectoryHolds() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        LogbackTestSupport.write(application.classes().resolve(LogbackPrecompiler.SERVICE_ENTRY),
                "com.example.MyConfigurator\n");

        assertStoodDown(application.precompile(false), "application output already registers a Logback Configurator ("
                + LogbackPrecompiler.SERVICE_ENTRY + ")");

        // Without the class directory the engine cannot know.
        LogbackPrecompiler.Result resourcesOnly = LogbackPrecompiler.precompile(
                application.request().applicationOutput(List.of(application.resources())).build());
        assertEquals(LogbackPrecompiler.Status.GENERATED, resourcesOnly.status(), resourcesOnly::message);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 17, 21, 24})
    void standsDownBelowJava25(int release) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().targetRelease(release).build());

        assertStoodDown(result, "the target release " + release + " is below 25, the Java release of the"
                + " generated classes");
    }

    @ParameterizedTest
    @ValueSource(ints = {25, 26})
    void generatesClassFilesOfJava25FromJava25On(int release) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().targetRelease(release).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
        for (String name : List.of(LogbackPrecompiler.CONFIGURATOR_ENTRY, LogbackPrecompiler.FALLBACK_ENTRY,
                LogbackPrecompiler.GUARD_ENTRY)) {
            byte[] bytes = result.entries().get(name);
            assertEquals(69, (bytes[6] & 0xFF) << 8 | bytes[7] & 0xFF, name);
        }
    }

    // ------------------------------------------------------------------ failures

    @ParameterizedTest
    @ValueSource(strings = {"LogbackConfigurator", "JoranFallback", "ClassPathGuard"})
    void aTakenGeneratedNameIsAFailure(String className) throws IOException {
        String name = LogbackPrecompiler.PACKAGE_PATH + className + ".class";
        Application application = application(Map.of("logback.xml", LOGBACK_XML, name, "not ours"));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.FAILED, result.status(), result::message);
        assertEquals("The application output already carries '" + name + "'; no Logback configuration was"
                + " precompiled and Logback will configure itself with Joran at startup", result.message());
        assertEquals(Map.of(), result.entries());
    }

    /**
     * What a caller gets that writes into a root it passes as application output, as a build step that writes
     * into the class directory does: the second call finds the first one's output. It is a failure, not a
     * stand-down, until the caller removes that output, because that output stays in use whatever has changed.
     */
    @Test
    void theOutputOfAnEarlierCallInAnApplicationRootIsAFailureUntilItIsRemoved() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        LogbackPrecompiler.Result first = application.precompile(false);
        List<Path> written = LogbackTestSupport.write(first, application.classes());
        assertEquals(4, written.size());

        LogbackPrecompiler.Result second = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.FAILED, second.status(), second::message);
        assertEquals("The application output already holds the output of an earlier precompilation ("
                + LogbackPrecompiler.SERVICE_ENTRY + " names " + LogbackPrecompiler.CONFIGURATOR_CLASS
                + "), which stays in use; no Logback configuration was precompiled. Remove that output before"
                + " precompiling again", second.message());
        assertEquals(Map.of(), second.entries());
        for (Path file : written) {
            String name = application.classes().relativize(file).toString().replace('\\', '/');
            assertArrayEquals(first.entries().get(name), Files.readAllBytes(file), name);
        }

        // The application now names a location, which that earlier output does not know about: still a failure.
        LogbackTestSupport.write(application.resources().resolve("application.properties"),
                "logger.config=custom.xml\n");
        assertEquals(second.message(), application.precompile(false).message());

        // Once the caller has removed the earlier output, the engine sees the application as it is.
        for (Path file : written) {
            Files.delete(file);
        }
        assertStoodDown(application.precompile(false), "the packaged application.properties may set logger.config");
        Files.delete(application.resources().resolve("application.properties"));
        LogbackPrecompiler.Result third = application.precompile(false);
        assertEquals(LogbackPrecompiler.Status.GENERATED, third.status(), third::message);
        assertArrayEquals(first.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY),
                third.entries().get(LogbackPrecompiler.CONFIGURATOR_ENTRY));
    }

    /** A service file is read as {@code ServiceLoader} reads it: comments and blank lines do not count. */
    @ParameterizedTest
    @ValueSource(strings = {
        "# written by a build step\n\nio.micronaut.aot.logback.generated.LogbackConfigurator # the generated one\n",
        "com.example.MyConfigurator\r\n  io.micronaut.aot.logback.generated.LogbackConfigurator"})
    void aServiceFileThatNamesTheGeneratedConfiguratorIsAnEarlierOutput(String services) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                LogbackPrecompiler.SERVICE_ENTRY, services));

        LogbackPrecompiler.Result result = application.precompile(false);

        assertEquals(LogbackPrecompiler.Status.FAILED, result.status(), result::message);
        assertTrue(result.message().startsWith("The application output already holds the output of an earlier"
                + " precompilation"), result::message);
    }

    /** Only a line that is the generated name counts: another configurator is a stand-down, as it always was. */
    @Test
    void aServiceFileThatOnlyMentionsTheGeneratedConfiguratorIsAnotherConfigurator() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML, LogbackPrecompiler.SERVICE_ENTRY,
                "# not io.micronaut.aot.logback.generated.LogbackConfigurator\ncom.example.MyConfigurator\n"));

        assertStoodDown(application.precompile(false), "application output already registers a Logback Configurator ("
                + LogbackPrecompiler.SERVICE_ENTRY + ")");
    }

    @Test
    void theNameOfTheGuardIsFreeOnAClosedClassPath() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                LogbackPrecompiler.GUARD_ENTRY, "not ours"));

        assertEquals(LogbackPrecompiler.Status.GENERATED, application.precompile(true).status());
        assertEquals(LogbackPrecompiler.Status.FAILED, application.precompile(false).status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFrontEndOrEmitterFailureIsAFailureAndNothingIsThrown(boolean frontEnd) throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        LogbackPrecompiler.DESCRIPTION_HOOK.set(description -> {
            if (frontEnd) {
                throw new IllegalStateException("forced front-end failure");
            }
            // An int setter handed a String: the emitted class no longer verifies.
            Map<String, Object> broken = new LinkedHashMap<>(description);
            List<Object> operations = new ArrayList<>((List<?>) broken.get("operations"));
            @SuppressWarnings("unchecked")
            Map<String, Object> appender = new LinkedHashMap<>((Map<String, Object>) operations.get(0));
            appender.put("steps", List.of(Map.of("step", "property", "method", "setName",
                    "descriptor", "(I)V", "value", "not an int")));
            operations.set(0, appender);
            broken.put("operations", operations);
            return broken;
        });
        LogbackPrecompiler.Result result;
        try {
            result = application.precompile(false);
        } finally {
            LogbackPrecompiler.DESCRIPTION_HOOK.set(UnaryOperator.identity());
        }

        assertEquals(LogbackPrecompiler.Status.FAILED, result.status(), result::message);
        assertEquals(Map.of(), result.entries());
        assertTrue(result.message().startsWith(STAND_DOWN + "it could not be compiled: "), result::message);
        assertTrue(result.message().contains(frontEnd ? "forced front-end failure" : "does not verify"),
                result::message);
        assertTrue(result.message().endsWith(JORAN_AT_STARTUP), result::message);
    }

    @Test
    void aMissingClassPathEntryIsAFailure() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(temporary.resolve("libs/missing.jar"));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(runtimeClasspath).build());

        assertUnreadable(result, "missing.jar");
    }

    @Test
    void aMissingApplicationRootIsAFailure() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(application.request()
                .applicationOutput(List.of(application.resources(), temporary.resolve("classes/groovy/main")))
                .build());

        assertUnreadable(result, "main");
    }

    @Test
    void aClassPathEntryThatIsNoArchiveIsAFailure() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(LogbackTestSupport.write(temporary.resolve("libs/notes.txt"), "not an archive"));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(runtimeClasspath).build());

        assertUnreadable(result, "notes.txt is neither a directory nor an archive");
    }

    /**
     * Of all the manifests on the class path, the engine reads those of logback-classic and logback-core. One
     * that is not a manifest anywhere else, in an application root or in another dependency, changes nothing.
     */
    @Test
    void onlyTheManifestsOfTheTwoLogbackEntriesAreRead() throws IOException {
        byte[] notAManifest = "not a manifest\n".getBytes(StandardCharsets.UTF_8);
        Application application = application(Map.of("logback.xml", LOGBACK_XML,
                "META-INF/MANIFEST.MF", "not a manifest\n"));
        LogbackTestSupport.write(application.classes().resolve("META-INF/MANIFEST.MF"), "not a manifest\n");
        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(LogbackTestSupport.zip(temporary.resolve("libs/library.jar"),
                Map.of("META-INF/MANIFEST.MF", notAManifest, "com/example/Library.class", new byte[] {1})));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(runtimeClasspath).build());

        assertEquals(LogbackPrecompiler.Status.GENERATED, result.status(), result::message);
    }

    @Test
    void aLogbackManifestThatCannotBeReadIsAFailure() throws IOException {
        Application application = application(Map.of("logback.xml", LOGBACK_XML));
        List<Path> dependencies = fakeLogback("1.5.37", "1.5.37");
        dependencies.set(1, LogbackTestSupport.zip(temporary.resolve("libs/broken-logback-core.jar"), Map.of(
                "META-INF/MANIFEST.MF", "not a manifest\n".getBytes(StandardCharsets.UTF_8),
                "ch/qos/logback/core/Context.class", new byte[] {1})));

        LogbackPrecompiler.Result result = LogbackPrecompiler.precompile(
                application.request().runtimeClasspath(dependencies).build());

        assertUnreadable(result, "the META-INF/MANIFEST.MF of broken-logback-core.jar: ");
    }

    // ------------------------------------------------------------------ plumbing

    private Application application(Map<String, String> files) throws IOException {
        return LogbackTestSupport.application(temporary, files);
    }

    /** A dependency of the application that holds the given files: a jar, or another project's resources. */
    private Path dependency(boolean jar, Map<String, String> files) throws IOException {
        if (jar) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                entries.put(file.getKey(), file.getValue().getBytes(StandardCharsets.UTF_8));
            }
            return LogbackTestSupport.jar(temporary.resolve("libs/logging-defaults.jar"), null, entries);
        }
        Path directory = temporary.resolve("other-project/build/resources/main");
        for (Map.Entry<String, String> file : files.entrySet()) {
            LogbackTestSupport.write(directory.resolve(file.getKey()), file.getValue());
        }
        return directory;
    }

    /** The Logback jars of the test class path, then one more entry. */
    private static List<Path> withLogback(Path entry) {
        List<Path> runtimeClasspath = new ArrayList<>(LogbackTestSupport.logbackJars());
        runtimeClasspath.add(entry);
        return runtimeClasspath;
    }

    /** The warning for a packaged file that enables the configuration client, which it names with its root or jar. */
    private static String configClientWarning(String file) {
        return "The packaged " + file + " enables Micronaut's distributed configuration client"
                + " (micronaut.config-client.enabled): a logger.config or logback.configurationFile that distributed"
                + " configuration supplies is not applied while the generated configurator is registered. Pass the"
                + " location as the JVM system property -Dlogger.config or the LOGGER_CONFIG environment variable"
                + " instead, or turn precompilation off in the build plugin; -Dmicronaut.logback.precompiled=false"
                + " does not help, as Micronaut then still calls the configurator";
    }

    private static void assertStoodDown(LogbackPrecompiler.Result result, String reason) {
        assertEquals(LogbackPrecompiler.Status.STOOD_DOWN, result.status(), result::message);
        assertEquals(Map.of(), result.entries());
        assertEquals(List.of(), result.warnings());
        String line = result.message();
        assertTrue(line.startsWith(STAND_DOWN), line);
        assertTrue(line.contains(reason), () -> line + "\ndoes not name: " + reason);
        assertTrue(line.endsWith(JORAN_AT_STARTUP), line);
        assertFalse(line.contains("\n"), line);
    }

    private static void assertUnreadable(LogbackPrecompiler.Result result, String what) {
        assertEquals(LogbackPrecompiler.Status.FAILED, result.status(), result::message);
        assertEquals(Map.of(), result.entries());
        assertEquals(List.of(), result.warnings());
        assertTrue(result.message().startsWith(STAND_DOWN + "the class path could not be read: "), result::message);
        assertTrue(result.message().contains(what), result::message);
        assertTrue(result.message().endsWith(JORAN_AT_STARTUP), result::message);
    }

    /** Jars that look like Logback to the precompiler's survey, at the given versions, and hold nothing else. */
    private List<Path> fakeLogback(String classicVersion, String coreVersion) throws IOException {
        Path libs = Files.createTempDirectory(temporary, "fake-logback");
        return new ArrayList<>(List.of(
                LogbackTestSupport.jar(libs.resolve("fake-logback-classic.jar"), classicVersion,
                        Map.of("ch/qos/logback/classic/LoggerContext.class", new byte[] {1})),
                LogbackTestSupport.jar(libs.resolve("fake-logback-core.jar"), coreVersion,
                        Map.of("ch/qos/logback/core/Context.class", new byte[] {1})),
                LogbackTestSupport.jar(libs.resolve("fake-slf4j-api.jar"), null,
                        Map.of("org/slf4j/ILoggerFactory.class", new byte[] {1}))));
    }
}
