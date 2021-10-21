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
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
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
    @Option(names = {"--optimizer-classpath", "-ocp"}, description = "The Micronaut AOT classpath", required = true, split = "[:,]")
    private List<File> aotClasspath;

    @Option(names = {"--classpath", "-cp"}, description = "The Micronaut application classpath", required = true, split = "[:,]")
    private List<File> classpath;

    @Option(names = {"--package", "-p"}, description = "The target package for generated classes", required = true)
    private String packageName;

    @Option(names = {"--output", "-o"}, description = "The output directory", required = true)
    private File outputDirectory;

    @Option(names = {"--seal-env", "-se"}, description = "If set to true, the environment variables and system properties will be deemed immutable during application execution.")
    private boolean sealEnvironment;

    @Option(names = {"--precheck-requirements", "-pr"}, description = "If enabled, the AOT optimizer will verify bean requirements statically and eliminate from classpath those which do not meet the requirements.")
    private boolean precheckRequirements;

    @Option(names = {"--preload-environment", "-pe"}, description = "If enabled, the AOT optimizer will use the current environment variables as values for runtime.")
    private boolean preloadEnvironment;

    @Option(names = {"--scan-reactive", "-sr"}, description = "If enabled, the AOT optimizer will pre-compute the set of reactive types")
    private boolean scanForReactiveTypes;

    @Option(names = {"--replace-logback", "-rl"}, description = "If enabled, the AOT optimizer will replace the logback.xml configuration file with a Java configuration")
    private boolean replaceLogback;

    @Option(names = {"--runtime"}, description = "The target runtime. Possible values: ${COMPLETION-CANDIDATES}")
    private MicronautAotOptimizer.Runtime runtime = MicronautAotOptimizer.Runtime.NATIVE;

    @Option(names = {"--missing-types", "-mt"}, description = "A set of classes which the AOT compiler should lookup for. If they aren't on classpath, they will be identified as missing.")
    private List<String> missingTypes = Collections.emptyList();

    @Option(names = {"--service-types", "-st"}, description = "A set of service type names which the AOT compiler should scan and generate service loaders for")
    private List<String> serviceTypes = Collections.emptyList();

    @Override
    public void run() {
        List<URL> classpath = new ArrayList<>();
        classpath.addAll(toURLs(aotClasspath));
        classpath.addAll(toURLs(this.classpath));
        Properties props = new Properties();
        props.put(CLASSPATH, classpath.stream().map(url -> {
            try {
                return new File(url.toURI()).getAbsolutePath();
            } catch (URISyntaxException e) {
                return null;
            }
        }).collect(Collectors.joining(":")));
        props.put(GENERATED_PACKAGE, packageName);
        props.put(OUTPUT_DIRECTORY, outputDirectory.getAbsolutePath());
        props.put(RUNTIME, runtime.toString());
        props.put(SEALED_ENVIRONMENT, sealEnvironment);
        props.put(PRELOAD_ENVIRONMENT, preloadEnvironment);
        props.put(TYPES_TO_CHECK, String.join(",", missingTypes));
        props.put(PRECHECK_BEAN_REQUIREMENTS, precheckRequirements);
        props.put(REPLACE_LOGBACK, replaceLogback);
        props.put(SCAN_REACTIVE_TYPES, scanForReactiveTypes);
        props.put(SERVICE_TYPES, String.join(",", serviceTypes));
        URL[] urls = classpath.toArray(new URL[0]);
        URLClassLoader cl = new URLClassLoader(urls, Thread.currentThread().getContextClassLoader());
        try {
            Class<?> runnerClass = cl.loadClass(MicronautAotOptimizer.class.getName());
            runnerClass.getDeclaredMethod("execute", Properties.class)
                    .invoke(null, props);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<URL> toURLs(List<File> classpath) {
        return classpath.stream().map(File::toURI).map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (MalformedURLException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
