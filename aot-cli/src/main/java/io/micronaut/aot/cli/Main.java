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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    static final String SLF4J_INTERNAL_VERBOSITY = "slf4j.internal.verbosity";
    static final String SLF4J_PROVIDER = "slf4j.provider";
    static final String LOGBACK_SERVICE_PROVIDER = "ch.qos.logback.classic.spi.LogbackServiceProvider";

    @Option(names = {"--classpath", "-cp"}, description = "The Micronaut application classpath", required = true)
    private String classpathString = "";

    @Option(names = {"--package", "-p"}, description = "The target package for generated classes", required = true)
    private String packageName = "";

    @Option(names = {"--runtime"}, description = "The target runtime. Possible values: jit, native")
    private String runtime = "jit";

    @Option(names = {"--config"}, description = "The configuration file (.properties)", required = true)
    private File config = new File("");

    @Option(names = {"--output", "-o"}, description = "The output directory", required = false)
    private File outputDirectory = new File("");

    @Option(names = {"--report"}, description = "Generate diagnostics reports")
    private boolean report;

    @Option(names = {"--report-output"}, description = "The diagnostics report output directory")
    private File reportOutputDirectory = new File("");

    @Option(names = {"--report-format"}, description = "The diagnostics report format. Supported values: json, html")
    private String reportFormat = "";

    @Override
    public void run() {
        configureSlf4j();
        List<URL> classpath = toURLs(classpathString);
        var props = new Properties();
        if (config.exists()) {
            try (var reader = new InputStreamReader(new FileInputStream(config))) {
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
        if (!outputDirectory.getPath().isEmpty()) {
            props.put(OUTPUT_DIRECTORY, outputDirectory.getAbsolutePath());
        }
        if (report) {
            props.put(REPORT_ENABLED, "true");
        }
        if (!reportOutputDirectory.getPath().isEmpty()) {
            props.put(REPORT_OUTPUT, reportOutputDirectory.getAbsolutePath());
        }
        if (!reportFormat.isEmpty()) {
            props.put(REPORT_FORMAT, reportFormat);
        }
        props.put(RUNTIME, runtime);
        URL[] urls = classpath.toArray(new URL[0]);
        executeInIsolatedLoader(props, urls, Thread.currentThread().getContextClassLoader());
        if (!outputDirectory.getPath().isEmpty() && Boolean.parseBoolean(props.getProperty(REPORT_ENABLED))) {
            for (File reportFile : reportFiles(props, outputDirectory)) {
                System.out.println("Micronaut AOT diagnostics report written to " + reportFile.getAbsolutePath());
            }
        }
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
        var cl = new URLClassLoader(urls, new FilteringClassLoader(ctxClassLoader));
        try {
            Thread.currentThread().setContextClassLoader(cl);
            Class<?> runnerClass = cl.loadClass("io.micronaut.aot.MicronautAotOptimizer");
            assert runnerClass != MicronautAotOptimizer.class;
            if (!outputDirectory.getPath().isEmpty()) {
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

    static void configureSlf4j() {
        setSystemPropertyIfMissing(SLF4J_INTERNAL_VERBOSITY, "WARN");
        setSystemPropertyIfMissing(SLF4J_PROVIDER, LOGBACK_SERVICE_PROVIDER);
    }

    private static void setSystemPropertyIfMissing(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
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
                .toList();
    }

    public static int execute(String[] args) {
        return new CommandLine(new Main()).execute(args);
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    private static List<File> reportFiles(Properties props, File outputDirectory) {
        String reportOutput = props.getProperty(REPORT_OUTPUT);
        File reportDirectory = reportOutput == null || reportOutput.isEmpty()
                ? new File(outputDirectory, "reports")
                : new File(reportOutput);
        return reportFormats(props).stream()
                .map(format -> new File(reportDirectory, REPORT_FORMAT_HTML.equals(format) ? REPORT_HTML_FILE_NAME : REPORT_JSON_FILE_NAME))
                .toList();
    }

    private static List<String> reportFormats(Properties props) {
        String format = props.getProperty(REPORT_FORMAT, REPORT_FORMAT_JSON);
        List<String> formats = Arrays.stream(format.split("[,;]\\s*"))
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ENGLISH))
                .toList();
        return formats.isEmpty() ? List.of(REPORT_FORMAT_JSON) : formats;
    }

    private static class FilteringClassLoader extends ClassLoader {
        public FilteringClassLoader(ClassLoader ctxClassLoader) {
            super(ctxClassLoader);
        }

        private Class<?> filter(String name, ClassFinder finder) throws ClassNotFoundException {
            if (name.startsWith("io.micronaut")) {
                throw new ClassNotFoundException(name);
            }
            return finder.find(name);
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            return filter(name, super::loadClass);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return filter(name, n -> super.loadClass(n, resolve));
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            //noinspection Convert2MethodRef
            return filter(name, n -> super.findClass(n));
        }

        @FunctionalInterface
        private interface ClassFinder {
            Class<?> find(String name) throws ClassNotFoundException;
        }
    }
}
