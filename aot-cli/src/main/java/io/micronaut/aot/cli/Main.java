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

import io.micronaut.aot.MicronautAotOptimizer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Entry point for the Micronaut AOT command line interface.
 */
@Command(name = "micronaut-aot",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Generates classes for Micronaut AOT (build time optimizations)")
public class Main implements Runnable {
    @Option(names = {"-classpath", "-cp"}, description = "The Micronaut application classpath", required = true)
    private List<File> classpath;

    @Option(names = {"-entrypoint", "-e"}, description = "The application entry point", required = true)
    private String entryPoint;

    @Option(names = {"-package", "-p"}, description = "The target package for generated classes", required = true)
    private String packageName;

    @Option(names = {"-output", "-o"}, description = "The output directory", required = true)
    private File outputDirectory;

    @Option(names = {"-cache-env", "-ce"}, description = "If set to true, the environment variables and system properties will be deemed immutable during application execution.")
    private boolean cacheEnvironment;

    @Option(names = {"-precheck-requirements", "-pr"}, description = "If enabled, the AOT optimizer will verify bean requirements statically and eliminate from classpath those which do not meet the requirements.")
    private boolean precheckRequirements;

    @Option(names = {"-preload-environment", "-pe"}, description = "If enabled, the AOT optimizer will use the current environment variables as values for runtime.")
    private boolean preloadEnvironment;

    @Option(names = {"-scan-reactive", "-sr"}, description = "If enabled, the AOT optimizer will pre-compute the set of reactive types")
    private boolean scanForReactiveTypes;

    @Option(names = {"-replace-logback", "-rl"}, description = "If enabled, the AOT optimizer will replace the logback.xml configuration file with a Java configuration")
    private boolean replaceLogback;

    @Option(names = {"-runtime"}, description = "The target runtime. Possible values: ${COMPLETION-CANDIDATES}")
    private MicronautAotOptimizer.Runtime runtime = MicronautAotOptimizer.Runtime.GRAALVM;

    @Option(names = {"-missing-types", "-mt"}, description = "A set of classes which the AOT compiler should lookup for. If they aren't on classpath, they will be identified as missing.")
    private List<String> missingTypes = Collections.emptyList();

    @Option(names = {"-service-types", "-st"}, description = "A set of service type names which the AOT compiler should scan and generate service loaders for")
    private List<String> serviceTypes = Collections.emptyList();

    @Override
    public void run() {
        MicronautAotOptimizer.Runner runner = MicronautAotOptimizer.runner(
                        packageName,
                        entryPoint,
                        new File(outputDirectory, "sources"),
                        new File(outputDirectory, "classes"),
                        new File(outputDirectory, "logs")
                )
                .forRuntime(runtime)
                .addClasspath(classpath)
                .cacheEnvironment(cacheEnvironment)
                .preCheckRequirements(precheckRequirements)
                .preloadEnvironment(preloadEnvironment)
                .scanForReactiveTypes(scanForReactiveTypes)
                .checkMissingTypes(missingTypes.toArray(new String[0]))
                .scanForServiceClasses(serviceTypes.toArray(new String[0]))
                .replaceLogbackXml(replaceLogback);
        runner.execute();
    }

    public static void main(String[] args) {
        new CommandLine(new Main()).execute(args);
    }
}
