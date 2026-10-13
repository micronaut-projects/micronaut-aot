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
package io.micronaut.aot.serviceindex.probe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;

/**
 * Runs the {@link ServiceIndexProbe} in a class loader that is neither the system class loader nor a
 * {@link URLClassLoader}, as Micronaut Runner's is: micronaut-core cannot know its class path.
 *
 * <p>The first argument is the application's class path, the others are the probe's. The class loader defines every
 * class of that class path itself, the probe and micronaut-core included, and is the context class loader.</p>
 */
public final class IsolatedLauncher {

    private IsolatedLauncher() {
    }

    /**
     * Runs the probe.
     *
     * @param args the class path, then the probe's arguments
     * @throws Exception if the probe fails
     */
    public static void main(String[] args) throws Exception {
        String[] entries = args[0].split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = Path.of(entries[i]).toUri().toURL();
        }
        // Reads the class path for the application's class loader, and loads nothing itself.
        URLClassLoader files = new URLClassLoader(urls, null);
        ClassLoader application = new ClassLoader("application", ClassLoader.getPlatformClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                try (InputStream in = files.getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (in == null) {
                        throw new ClassNotFoundException(name);
                    }
                    byte[] bytes = in.readAllBytes();
                    return defineClass(name, bytes, 0, bytes.length);
                } catch (IOException e) {
                    throw new ClassNotFoundException(name, e);
                }
            }

            @Override
            protected URL findResource(String name) {
                return files.findResource(name);
            }

            @Override
            protected Enumeration<URL> findResources(String name) throws IOException {
                return files.findResources(name);
            }
        };
        Thread.currentThread().setContextClassLoader(application);
        application.loadClass(ServiceIndexProbe.class.getName())
                .getMethod("main", String[].class)
                .invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
    }
}
