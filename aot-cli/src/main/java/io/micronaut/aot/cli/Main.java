/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.aot.cli;

import io.micronaut.aot.ConfigKeys;
import io.micronaut.aot.MicronautAotOptimizer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Entry point for the Micronaut AOT command line interface.
 */
@Command(name = "micronaut-aot",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Generates classes for Micronaut AOT (build time optimizations)")
public class Main implements Runnable, ConfigKeys {
    @Option(names = {"--optimizer-classpath", "-ocp"}, description = "The Micronaut AOT classpath", required = true)
    private String aotClasspathString;

    @Option(names = {"--classpath", "-cp"}, description = "The Micronaut application classpath", required = true)
    private String classpathString;

    @Option(names = {"--package", "-p"}, description = "The target package for generated classes", required = true)
    private String packageName;

    @Option(names = {"--runtime"}, description = "The target runtime. Possible values: jit, native")
    private String runtime = "jit";

    @Option(names = {"--config"}, description = "The configuration file (.properties)", required = true)
    private File config;

    @Option(names = {"--output", "-o"}, description = "The output directory", required = false)
    private File outputDirectory;

    @Override
    public void run() {
        List<URL> classpath = new ArrayList<>();
        classpath.addAll(toURLs(aotClasspathString));
        classpath.addAll(toURLs(classpathString));
        Properties props = new Properties();
        if (config.exists()) {
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(config))) {
                props.load(reader);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        props.put(CLASSPATH, classpath.stream().map(url -> {
            try {
                return new File(url.toURI()).getAbsolutePath();
            } catch (URISyntaxException e) {
                return null;
            }
        }).collect(Collectors.joining(",")));
        props.put(GENERATED_PACKAGE, packageName);
        if (outputDirectory != null) {
            props.put(OUTPUT_DIRECTORY, outputDirectory.getAbsolutePath());
        }
        props.put(RUNTIME, runtime);
        URL[] urls = classpath.toArray(new URL[0]);
        executeInIsolatedLoader(props, urls, Thread.currentThread().getContextClassLoader());
    }

    /**
     * The Micronaut AOT runtime needs to be in the same classloader as the
     * application classes. This method ensures that we create an isolated
     * loader which can still see the bootstrap loader classes, but still
     * isolates from the AOT classes which were loaded by this main class.
     * @param props the configuration of the AOT optimizer
     * @param urls the URLs to add to the classpath
     * @param ctxClassLoader the current context classloader
     */
    private void executeInIsolatedLoader(Properties props, URL[] urls, ClassLoader ctxClassLoader) {
        URLClassLoader cl = new URLClassLoader(urls, ctxClassLoader);
        try {
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> runnerClass = cl.loadClass(MicronautAotOptimizer.class.getName());
            assert runnerClass != MicronautAotOptimizer.class;
            if (outputDirectory != null) {
                runnerClass.getDeclaredMethod("execute", Properties.class)
                        .invoke(null, props);
            } else {
                runnerClass.getDeclaredMethod("exportConfiguration", String.class, File.class)
                        .invoke(null, runtime, config);
            }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(ctxClassLoader);
        }
    }

    private static List<URL> toURLs(String classpathString) {
        return Arrays.stream(classpathString.split("[,;" + File.pathSeparator + "]"))
                .map(File::new)
                .map(File::toURI).map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (MalformedURLException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static int execute(String[] args) {
        return new CommandLine(new Main()).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }
}
