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
import ch.qos.logback.core.Context;
import org.slf4j.ILoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * What the tests of {@link LogbackPrecompiler} share: the corpus, an application laid out in directories, the
 * Logback jars of the test class path and the classes an {@link IsolatedClassPath} or a forked JVM needs.
 */
final class LogbackTestSupport {

    /** A configuration inside the literal subset: one console appender with one pattern. */
    static final String LOGBACK_XML = """
            <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <encoder>
                        <pattern>%msg%n</pattern>
                    </encoder>
                </appender>
                <root level="WARN">
                    <appender-ref ref="STDOUT"/>
                </root>
            </configuration>
            """;

    static final String STAND_DOWN = "No Logback configuration was precompiled because ";

    static final String JORAN_AT_STARTUP = "; Logback will configure itself with Joran at startup";

    private static final String CORPUS = "/logback-corpus/";

    private static final String LOG_FILE_TOKEN = "@LOG_FILE@";

    private LogbackTestSupport() {
    }

    /**
     * Reads a corpus file, replacing the log file token.
     *
     * @param name    the file, relative to the corpus
     * @param logFile what the token becomes, or {@code null} when the file has none
     * @return its text
     */
    static String corpus(String name, Path logFile) throws IOException {
        String xml;
        try (InputStream in = LogbackTestSupport.class.getResourceAsStream(CORPUS + name)) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (logFile != null) {
            // Forward slashes: a Windows backslash is not a character to put through variable substitution.
            xml = xml.replace(LOG_FILE_TOKEN, logFile.toString().replace('\\', '/'));
        }
        return xml;
    }

    /**
     * Lays out an application below a new directory of {@code parent}: a resources root holding {@code files} and
     * a class directory holding one class.
     *
     * @param parent a temporary directory
     * @param files  the resources, by name
     * @return the application
     */
    static Application application(Path parent, Map<String, String> files) throws IOException {
        Path directory = Files.createTempDirectory(parent, "application");
        Path resources = Files.createDirectories(directory.resolve("resources"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            write(resources.resolve(file.getKey()), file.getValue());
        }
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes.resolve("com/example"));
        Files.write(classes.resolve("com/example/Application.class"), new byte[] {1});
        return new Application(directory, resources, classes);
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Writes every entry of a result below a directory, by its name, as a build plugin does.
     *
     * @param result    the result
     * @param directory the directory, created when it does not exist
     * @return the files written, in the order of the entries
     */
    static List<Path> write(LogbackPrecompiler.Result result, Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            Path file = directory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            files.add(Files.write(file, entry.getValue()));
        }
        return files;
    }

    /** The logback-classic, logback-core and slf4j-api jars of this test class path. */
    static List<Path> logbackJars() {
        List<Path> jars = new ArrayList<>();
        for (Class<?> type : List.of(LoggerContext.class, Context.class, ILoggerFactory.class)) {
            jars.add(jarOf(type));
        }
        return jars;
    }

    static Path jarOf(Class<?> type) {
        try {
            return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes a jar.
     *
     * @param jar                   where
     * @param implementationVersion its {@code Implementation-Version}, or {@code null} for none
     * @param entries               its entries, by name
     * @return the jar
     */
    static Path jar(Path jar, String implementationVersion, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (implementationVersion != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, implementationVersion);
        }
        Files.createDirectories(jar.getParent());
        try (OutputStream file = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(file, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Writes an archive of exactly the given entries: unlike {@link #jar(Path, String, Map)} it writes no manifest
     * of its own, so a test can give it one that is not a manifest.
     *
     * @param archive where
     * @param entries its entries, by name
     * @return the archive
     */
    static Path zip(Path archive, Map<String, byte[]> entries) throws IOException {
        Files.createDirectories(archive.getParent());
        try (OutputStream file = Files.newOutputStream(archive);
             ZipOutputStream out = new ZipOutputStream(file)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return archive;
    }

    /**
     * Unpacks a jar into a directory, which is then the same class path entry as the jar.
     *
     * @param jar       the jar
     * @param directory where, created when it does not exist
     * @return the directory
     */
    static Path explode(Path jar, Path directory) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException(entry.getName() + " leaves " + directory);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target);
                }
            }
        }
        return directory;
    }

    /**
     * Copies the classes that run on a class path of their own, {@link LogbackProbe} and
     * {@link LogbackDifferential}, into a directory that holds nothing else: the test class path itself must not
     * be visible there.
     *
     * @param directory the directory, which becomes a class path entry
     * @return the directory
     */
    static Path harness(Path directory) throws IOException {
        for (Class<?> type : List.of(LogbackProbe.class, LogbackDifferential.class)) {
            String resource = type.getName().replace('.', '/') + ".class";
            Path target = directory.resolve(resource);
            Files.createDirectories(target.getParent());
            try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
                Files.write(target, in.readAllBytes());
            }
        }
        return directory;
    }

    /** A description without its status lines, which differ between Joran and a fallback that also ran. */
    static String tree(String description) {
        StringBuilder tree = new StringBuilder();
        for (String line : description.split("\n")) {
            if (!line.startsWith("status ")) {
                tree.append(line).append('\n');
            }
        }
        return tree.toString();
    }

    /**
     * An application laid out in directories.
     *
     * @param directory what holds everything about it
     * @param resources its resources root
     * @param classes   its class directory
     */
    record Application(Path directory, Path resources, Path classes) {

        /**
         * A request for this application on the Logback of the test class path, for Java 25.
         *
         * @return the builder, for a test to change
         */
        LogbackPrecompiler.Request.Builder request() {
            return LogbackPrecompiler.Request.builder()
                    .applicationOutput(List.of(resources, classes))
                    .runtimeClasspath(logbackJars())
                    .targetRelease(25)
                    .workDirectory(directory.resolve("work"));
        }

        /**
         * Precompiles for a closed class path or for an open one.
         *
         * @param closed whether the class path is closed
         * @return the result
         */
        LogbackPrecompiler.Result precompile(boolean closed) {
            return LogbackPrecompiler.precompile(request().closedClassPath(closed).build());
        }

        /**
         * Precompiles and writes the result.
         *
         * @param closed whether the class path is closed
         * @return the directory holding the generated entries
         */
        Path generate(boolean closed) throws IOException {
            LogbackPrecompiler.Result result = precompile(closed);
            if (result.status() != LogbackPrecompiler.Status.GENERATED) {
                throw new AssertionError("nothing was generated: " + result.message());
            }
            Path generated = directory.resolve("generated");
            write(result, generated);
            return generated;
        }
    }
}
