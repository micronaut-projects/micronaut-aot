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

import com.sun.management.UnixOperatingSystemMXBean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.micronaut.aot.serviceindex.Fixtures.GREETING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A build tool calls the generator many times in one long-lived JVM, such as a Gradle daemon: each call sees the files
 * as they are, and leaves nothing behind.
 */
class HygieneTest {

    @TempDir
    Path temporary;

    @Test
    void aSecondCallSeesTheHolderAsItIsNow() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application().services(GREETING, "fixture.First"));
        ServiceIndexGenerator.Result first = Fixtures.generateFor(jar);
        // The same file, rewritten with names of the same length: a jar cached from the first call would be stale.
        Path rewritten = Fixtures.fatJar(temporary.resolve("app.jar"),
                Fixtures.application().services(GREETING, "fixture.Other"));

        ServiceIndexGenerator.Result second = Fixtures.generateFor(rewritten);

        assertEquals(List.of("fixture.First"), Fixtures.load(List.of(jar), first.entries()).standardServices().get(GREETING));
        assertEquals(List.of("fixture.Other"), Fixtures.load(List.of(jar), second.entries()).standardServices().get(GREETING));
    }

    @Test
    void theClassLoadersOfACallCanBeCollected() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        ClassLoadingMXBean classLoading = ManagementFactory.getClassLoadingMXBean();
        Fixtures.generateFor(jar);
        long unloaded = classLoading.getUnloadedClassCount();

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);
        // the classes of micronaut-core and the generated ones that a call loads
        for (int i = 0; i < 20 && classLoading.getUnloadedClassCount() < unloaded + 5; i++) {
            System.gc();
            Thread.sleep(50);
        }

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        assertTrue(classLoading.getUnloadedClassCount() >= unloaded + 5,
                "no class of the call was unloaded: " + (classLoading.getUnloadedClassCount() - unloaded));
    }

    @Test
    void noFileStaysOpen() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        UnixOperatingSystemMXBean system = (UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Fixtures.generateFor(jar);
        long open = system.getOpenFileDescriptorCount();

        for (int i = 0; i < 5; i++) {
            assertEquals(ServiceIndexGenerator.Status.GENERATED, Fixtures.generateFor(jar).status());
        }

        assertTrue(system.getOpenFileDescriptorCount() <= open,
                "open files: " + open + " before, " + system.getOpenFileDescriptorCount() + " after");
    }

    @Test
    void theContextClassLoaderIsRestored() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader caller = new URLClassLoader(new URL[0], previous)) {
            Thread.currentThread().setContextClassLoader(caller);

            ServiceIndexGenerator.Result generated = Fixtures.generateFor(jar);
            ServiceIndexGenerator.Result failed = ServiceIndexGenerator.generate(ServiceIndexGenerator.Request.builder()
                    .entry(temporary.resolve("missing.jar")).holder(temporary.resolve("missing.jar")).build());

            assertEquals(ServiceIndexGenerator.Status.GENERATED, generated.status(), generated.message());
            assertEquals(ServiceIndexGenerator.Status.FAILED, failed.status(), failed.message());
            assertSame(caller, Thread.currentThread().getContextClassLoader());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void concurrentCallsGiveTheSameResult() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<ServiceIndexGenerator.Result>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                calls.add(executor.submit(() -> Fixtures.generateFor(jar)));
            }
            Map<String, byte[]> expected = calls.get(0).get().entries();
            for (Future<ServiceIndexGenerator.Result> call : calls) {
                ServiceIndexGenerator.Result result = call.get();
                assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
                assertEquals(expected.keySet(), result.entries().keySet());
                for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                    assertArrayEquals(entry.getValue(), result.entries().get(entry.getKey()), entry.getKey());
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
