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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * A class path of its own: one class loader below the platform class loader that holds a case's resources, the
 * generated classes, Logback and the {@link LogbackProbe}.
 *
 * <p>A guarded configurator and every fallback look for {@code logback.xml} and {@code logback-test.xml} through
 * the class loader that defines Logback. On the test class path that loader also sees this module's own
 * {@code logback-test.xml}, so a guarded configurator would always fall back there, and a fallback would always
 * find that file. Here Logback is defined by this loader, which sees only what a test puts on it.</p>
 */
final class IsolatedClassPath extends URLClassLoader {

    IsolatedClassPath(List<Path> entries) {
        super("isolated-logback-class-path", urls(entries), ClassLoader.getPlatformClassLoader());
    }

    private static URL[] urls(List<Path> entries) {
        URL[] urls = new URL[entries.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = entries.get(i).toUri().toURL();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return urls;
    }

    /**
     * Whether this loader has loaded a class.
     *
     * @param name the binary name
     * @return whether it has
     */
    boolean loaded(String name) {
        return findLoadedClass(name) != null;
    }

    /**
     * Calls a static method of the {@link LogbackProbe} this loader defines.
     *
     * @param method    the method name
     * @param arguments its arguments: strings and integers
     * @param <T>       what it returns
     * @return what it returned
     */
    @SuppressWarnings("unchecked")
    <T> T probe(String method, Object... arguments) throws ReflectiveOperationException {
        Class<?>[] types = new Class<?>[arguments.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = arguments[i] instanceof Integer ? int.class : String.class;
        }
        Class<?> probe = Class.forName(LogbackProbe.class.getName(), true, this);
        if (probe.getClassLoader() != this) {
            throw new AssertionError("the probe is not defined by the isolated class path");
        }
        try {
            return (T) probe.getMethod(method, types).invoke(null, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (e.getCause() instanceof Error failure) {
                throw failure;
            }
            throw e;
        }
    }
}
