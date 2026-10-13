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

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The application's own micronaut-core, in a class loader of the application's class path whose parent is the
 * platform class loader: nothing of the build tool's class path is visible to it.
 *
 * <p>The generator never links to micronaut-core. It checks by reflection that the members the generated code calls
 * are there, with the expected types and access, calls the builder, and runs the generated loader. Classes are loaded
 * without being initialised, and the only ones that run are micronaut-core's and the generated ones.</p>
 */
final class CoreApi implements Closeable {

    private static final String SERVICE_PACKAGE = "io.micronaut.core.io.service.";
    private static final String BUILDER = SERVICE_PACKAGE + "ServiceIndexBuilder";
    private static final String INDEX = SERVICE_PACKAGE + "ServiceIndex";
    private static final String ENTRY = INDEX + "$ClassPathEntry";
    private static final String LOADER = "io.micronaut.core.optim.StaticOptimizations$Loader";
    /** A class that every micronaut-core has. */
    private static final String CORE_CLASS = "io/micronaut/core/io/service/SoftServiceLoader.class";
    /** micronaut-core's own version file, which a fat jar keeps, unlike the manifest of micronaut-core's jar. */
    private static final String VERSION_FILE = "micronaut-version.properties";

    private final URLClassLoader loader;
    private @Nullable Api api;

    private CoreApi(URLClassLoader loader) {
        this.loader = loader;
    }

    /**
     * Opens a class loader of a class path.
     *
     * @param files the entries
     * @return the class loader's micronaut-core, not checked yet
     * @throws MalformedURLException if an entry is not a valid URL
     */
    static CoreApi open(List<Path> files) throws MalformedURLException {
        URL[] urls = new URL[files.size()];
        for (int i = 0; i < urls.length; i++) {
            urls[i] = files.get(i).toUri().toURL();
        }
        return new CoreApi(new URLClassLoader("micronaut-aot-service-index", urls,
                ClassLoader.getPlatformClassLoader()));
    }

    /**
     * @return the class loader of the class path, which the index is built for
     */
    ClassLoader loader() {
        return loader;
    }

    /**
     * Checks that micronaut-core has the service index API that the generated code calls.
     *
     * @throws StandDown if it has no micronaut-core, or one without that API
     */
    void check() throws StandDown {
        Class<?> builder;
        try {
            builder = Class.forName(BUILDER, false, loader);
        } catch (ClassNotFoundException e) {
            if (loader.findResource(CORE_CLASS) == null) {
                throw new StandDown("there is no micronaut-core on the class path");
            }
            throw new StandDown(core() + " has no ServiceIndexBuilder: the service index needs micronaut-core 5.3.0"
                    + " or later");
        } catch (LinkageError e) {
            throw new StandDown(core() + " cannot be loaded by this JVM (" + e + ")");
        }
        String member = "ServiceIndex";
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> index = Class.forName(INDEX, false, loader);
            if (index.isInterface()) {
                throw new NoSuchMethodException(INDEX + " is an interface");
            }
            member = "ServiceIndexBuilder.build(ClassLoader, Collection)";
            MethodHandle build = lookup.findStatic(builder, "build",
                    MethodType.methodType(index, ClassLoader.class, Collection.class));
            member = "ServiceIndex.ofTrusted(ClassLoader, Map, Map, List)";
            lookup.findStatic(index, "ofTrusted",
                    MethodType.methodType(index, ClassLoader.class, Map.class, Map.class, List.class));
            member = "ServiceIndex.micronautServices()";
            MethodHandle micronautServices = lookup.findVirtual(index, "micronautServices",
                    MethodType.methodType(Map.class));
            member = "ServiceIndex.standardServices()";
            MethodHandle standardServices = lookup.findVirtual(index, "standardServices",
                    MethodType.methodType(Map.class));
            member = "ServiceIndex.classPath()";
            MethodHandle classPath = lookup.findVirtual(index, "classPath", MethodType.methodType(List.class));
            member = "ServiceIndex$ClassPathEntry";
            Class<?> entry = Class.forName(ENTRY, false, loader);
            member = "ServiceIndex$ClassPathEntry.<init>(String, long)";
            lookup.findConstructor(entry, MethodType.methodType(void.class, String.class, long.class));
            member = "ServiceIndex$ClassPathEntry.name()";
            MethodHandle entryName = lookup.findVirtual(entry, "name", MethodType.methodType(String.class));
            member = "ServiceIndex$ClassPathEntry.size()";
            MethodHandle entrySize = lookup.findVirtual(entry, "size", MethodType.methodType(long.class));
            member = "StaticOptimizations$Loader.load()";
            Class<?> loaderType = Class.forName(LOADER, false, loader);
            if (!loaderType.isInterface()) {
                throw new NoSuchMethodException(LOADER + " is not an interface");
            }
            MethodHandle load = lookup.findVirtual(loaderType, "load", MethodType.methodType(Object.class));
            api = new Api(index, build, micronautServices, standardServices, classPath, entryName, entrySize, load);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new StandDown(core() + " has another service index API than the one the generated code calls: "
                    + member + " is missing, or has another type or access (" + e + ")");
        }
    }

    /**
     * Builds the index of the class path with micronaut-core's {@code ServiceIndexBuilder}.
     *
     * @param types     the types whose {@code META-INF/services} files to index
     * @param classPath the class path the index is to list instead of the one the builder lists, which is the build
     *                  machine's
     * @return what the index holds
     * @throws IOException if micronaut-core cannot read the class path
     * @throws Throwable   if micronaut-core fails otherwise
     */
    IndexData build(Collection<String> types, @Nullable List<IndexData.Entry> classPath) throws Throwable {
        Api checked = checked();
        Object index = checked.build.invoke((ClassLoader) loader, types);
        IndexData built = describe(checked, index);
        return new IndexData(built.micronautServices(), built.standardServices(), classPath);
    }

    /**
     * Runs a generated loader, as micronaut-core does at run time: its classes are defined by a child of the class
     * loader of the class path, before any class of an earlier index that the class path may still hold.
     *
     * @param classes   the generated classes, by entry name
     * @param className the binary name of the loader
     * @return what the index it returns holds
     * @throws LinkageError if the generated code does not link against micronaut-core
     * @throws Throwable    if it fails otherwise
     */
    IndexData run(Map<String, byte[]> classes, String className) throws Throwable {
        Api checked = checked();
        Class<?> type = Class.forName(className, false, new GeneratedClasses(loader, classes));
        Object instance = MethodHandles.publicLookup().findConstructor(type, MethodType.methodType(void.class))
                .invoke();
        Object index = checked.load.invoke(instance);
        if (!checked.index.isInstance(index)) {
            throw new IllegalStateException("the generated loader returned " + index + " instead of a ServiceIndex");
        }
        return describe(checked, index);
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }

    private Api checked() {
        if (api == null) {
            throw new IllegalStateException("micronaut-core was not checked");
        }
        return api;
    }

    @SuppressWarnings("unchecked")
    private static IndexData describe(Api api, Object index) throws Throwable {
        Map<String, List<String>> micronaut = new LinkedHashMap<>();
        ((Map<String, ? extends Collection<String>>) api.micronautServices.invoke(index))
                .forEach((type, names) -> micronaut.put(type, List.copyOf(names)));
        Map<String, List<String>> standard = new LinkedHashMap<>();
        ((Map<String, ? extends Collection<String>>) api.standardServices.invoke(index))
                .forEach((type, names) -> standard.put(type, List.copyOf(names)));
        List<?> entries = (List<?>) api.classPath.invoke(index);
        List<IndexData.Entry> classPath = null;
        if (entries != null) {
            classPath = new ArrayList<>(entries.size());
            for (Object entry : entries) {
                classPath.add(new IndexData.Entry((String) api.entryName.invoke(entry),
                        (long) api.entrySize.invoke(entry)));
            }
        }
        return new IndexData(micronaut, standard, classPath);
    }

    /**
     * @return micronaut-core and its version, for a message
     */
    private String core() {
        URL url = loader.findResource(VERSION_FILE);
        if (url != null) {
            try {
                URLConnection connection = url.openConnection();
                // Not from the JVM's cache of jar files, which would keep the file open and could serve the content
                // of an earlier build.
                connection.setUseCaches(false);
                Properties properties = new Properties();
                try (InputStream in = connection.getInputStream()) {
                    properties.load(in);
                }
                String version = properties.getProperty("micronaut.version");
                if (version != null && !version.isBlank()) {
                    return "micronaut-core " + version.strip();
                }
            } catch (IOException | IllegalArgumentException e) {
                // the version is only for the message
            }
        }
        return "the micronaut-core of the class path";
    }

    /**
     * The members of micronaut-core that the generator and the generated code use, once checked.
     *
     * @param index             {@code ServiceIndex}
     * @param build             {@code ServiceIndexBuilder.build(ClassLoader, Collection)}
     * @param micronautServices {@code ServiceIndex.micronautServices()}
     * @param standardServices  {@code ServiceIndex.standardServices()}
     * @param classPath         {@code ServiceIndex.classPath()}
     * @param entryName         {@code ServiceIndex.ClassPathEntry.name()}
     * @param entrySize         {@code ServiceIndex.ClassPathEntry.size()}
     * @param load              {@code StaticOptimizations.Loader.load()}
     */
    private record Api(Class<?> index, MethodHandle build, MethodHandle micronautServices,
                       MethodHandle standardServices, MethodHandle classPath, MethodHandle entryName,
                       MethodHandle entrySize, MethodHandle load) {
    }

    /**
     * Defines the generated classes, and delegates every other class to the class loader of the class path, except a
     * generated name that it does not define: an earlier index that the class path still holds is never linked to.
     */
    private static final class GeneratedClasses extends ClassLoader {

        private final Map<String, byte[]> classes;

        GeneratedClasses(ClassLoader parent, Map<String, byte[]> entries) {
            super("micronaut-aot-service-index-check", parent);
            classes = new LinkedHashMap<>();
            entries.forEach((name, bytes) -> {
                if (name.endsWith(".class")) {
                    classes.put(name.substring(0, name.length() - ".class".length()).replace('/', '.'), bytes);
                }
            });
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) {
                if (name.startsWith(ServiceIndexGenerator.GENERATED_PACKAGE + ".")) {
                    throw new ClassNotFoundException(name + " is not generated");
                }
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
