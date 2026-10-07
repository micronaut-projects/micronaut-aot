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
package io.micronaut.aot.bytecode;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Micronaut application, desugared with its runtime class path, starts under {@code -Xverify:all} and answers a
 * request as the application started from the original class path does.
 *
 * <p>The application has a controller with lambdas of its own, compiled in the test with micronaut-inject-java, and
 * calls a library compiled at {@code --release 8} whose private lambda body needs a bridge. Its runtime class path is
 * the corpus's hello-netty application: Micronaut's HTTP server on Netty, Jackson and Logback. The
 * {@code endToEndTest} task passes it, and the processor path, as the system properties
 * {@code aot.bytecode.e2e.classPath} and {@code aot.bytecode.e2e.processorPath}. Both applications are started with
 * {@code java -cp}, the application's classes as a directory; a fat JAR built from the result differs only by the
 * merge, which the plugins' Shadow or Shade does.</p>
 */
@Tag("end-to-end")
class EndToEndTest {

    private static final String CONTROLLER = """
            package e2eapp;

            import io.micronaut.http.annotation.Controller;
            import io.micronaut.http.annotation.Get;
            import java.util.List;
            import java.util.function.Supplier;

            @Controller("/hello")
            public class HelloController {
                @Get(produces = "text/plain")
                public String hello() {
                    Supplier<String> greeting = () -> e2elib.Greeter.greet("e2e");
                    return greeting.get() + " " + List.of(3, 1, 2).stream().map(value -> value * 2).sorted().toList();
                }
            }
            """;

    private static final String APPLICATION = """
            package e2eapp;

            import io.micronaut.runtime.Micronaut;

            public class Application {
                public static void main(String[] args) {
                    Micronaut.run(Application.class, args);
                }
            }
            """;

    private static final String LIBRARY = """
            package e2elib;

            import java.util.function.Function;

            public class Greeter {
                public static String greet(String name) {
                    Function<String, String> greeting = value -> "Hello " + value;
                    return greeting.apply(name);
                }
            }
            """;

    @TempDir
    static Path temp;

    @Test
    void aDesugaredApplicationStartsUnderVerifyAllAndAnswersAsTheOriginal() throws Exception {
        String classPathProperty = System.getProperty("aot.bytecode.e2e.classPath");
        String processorPath = System.getProperty("aot.bytecode.e2e.processorPath");
        Assumptions.assumeTrue(classPathProperty != null && processorPath != null,
                "no runtime class path: run the endToEndTest task");
        List<Path> runtime = new ArrayList<>();
        for (String entry : classPathProperty.split(File.pathSeparator)) {
            runtime.add(Path.of(entry));
        }

        Path library = ClassFixtures.jar(temp.resolve("libs/greeter.jar"), ClassFixtures.classes(
                ClassFixtures.compile(temp.resolve("library/src"), temp.resolve("library/classes"),
                        List.of("-g", "--release", "8"), Map.of("e2elib/Greeter.java", LIBRARY))));
        List<String> options = new ArrayList<>(List.of("-g", "--release", "25", "-parameters", "-classpath",
                join(runtime) + File.pathSeparator + library, "-processorpath", processorPath));
        Path classes = ClassFixtures.compile(temp.resolve("application/src"), temp.resolve("application/classes"),
                options, Map.of("e2eapp/HelloController.java", CONTROLLER, "e2eapp/Application.java", APPLICATION));
        List<Path> classPath = new ArrayList<>();
        classPath.add(classes);
        classPath.add(library);
        classPath.addAll(runtime);

        ClassPathTransform.Result result = ClassPathTransform.run(ClassPathTransform.Request.builder()
                .classPath(classPath).outputDirectory(temp.resolve("desugared")).desugarLambdas(true).build());
        System.out.println(result.summary());

        assertNotEquals(classes, result.classPath().get(0), "the application's classes are desugared");
        assertNotEquals(library, result.classPath().get(1), "the library is desugared");
        assertTrue(ClassPathDesugaringTest.entries(result.classPath().get(1)).keySet().stream()
                .anyMatch(name -> name.contains(LambdaClasses.GENERATED_INFIX)), "the library has a generated class");
        assertTrue(ClassFileBridges.hasBridge(ClassPathDesugaringTest.entries(result.classPath().get(1))
                .get("e2elib/Greeter.class")), "the Java 8 library reaches its private lambda body by a bridge");
        try (Stream<Path> files = Files.walk(result.classPath().get(0))) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().contains(LambdaClasses.GENERATED_INFIX)),
                    "the application has generated classes");
        }
        int rewrittenEntries = 0;
        for (ClassPathTransform.Result.Entry entry : result.entries()) {
            assertEquals(List.of(), entry.notes(), entry::toString);
            rewrittenEntries += entry.sitesRewritten() > 0 ? 1 : 0;
        }
        assertTrue(rewrittenEntries > 10, "Micronaut's, Netty's and Logback's jars are desugared too: "
                + rewrittenEntries);

        String original = answer(classPath);
        String desugared = answer(result.classPath());

        assertEquals("Hello e2e [2, 4, 6]", original);
        assertEquals(original, desugared);
    }

    /**
     * Starts the application on a class path under {@code -Xverify:all}, sends it one request and stops it.
     */
    private static String answer(List<Path> classPath) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = Files.createTempFile(temp, "application", ".log");
        Process process = new ProcessBuilder(java.toString(), "-Xverify:all", "-Dmicronaut.server.port=" + port,
                "-cp", join(classPath), "e2eapp.Application")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/hello"))
                    .timeout(Duration.ofSeconds(10)).build();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                assertTrue(process.isAlive(), () -> "the application stopped: " + read(log));
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertEquals(200, response.statusCode(), () -> response.body() + "\n" + read(log));
                    return response.body();
                } catch (IOException e) {
                    Thread.sleep(200);
                }
            }
            throw new AssertionError("the application did not answer in time: " + read(log));
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    private static String read(Path log) {
        try {
            return Files.readString(log);
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static String join(List<Path> paths) {
        return String.join(File.pathSeparator, paths.stream().map(Path::toString).toList());
    }

    /**
     * Finds the bridges desugaring adds to a host below class-file version 55.
     */
    private static final class ClassFileBridges {

        private ClassFileBridges() {
        }

        static boolean hasBridge(byte[] host) {
            return ClassFile.of().parse(host).methods().stream()
                    .anyMatch(method -> method.methodName().stringValue().startsWith(LambdaClasses.BRIDGE_PREFIX));
        }
    }
}
