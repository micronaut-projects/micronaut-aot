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
package io.micronaut.aot.std.sourcegen;

import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.codegen.ApplicationContextConfigurerGenerator;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.core.annotation.NonNull;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Generates the GraalVM configuration file which is going to configure
 * the native image code generation, typically asking to initialize
 * the optimized entry point at build time.
 */
@AOTModule(
    id = GraalVMOptimizationFeatureSourceGenerator.ID,
    description = GraalVMOptimizationFeatureSourceGenerator.DESCRIPTION,
    options = {
        @Option(
            key = "service.types",
            description = "The list of service types to be scanned (comma separated)",
            sampleValue = "io.micronaut.Service1,io.micronaut.Service2"
        )
    },
    enabledOn = Runtime.NATIVE
)
public class GraalVMOptimizationFeatureSourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "graalvm.config";
    public static final String DESCRIPTION =
        "Generates GraalVM configuration files required to load the AOT optimizations";
    private static final String NEXT_LINE = " \\";

    private static final Option OPTION =
        MetadataUtils.findOption(GraalVMOptimizationFeatureSourceGenerator.class, "service.types");

    @Override
    public void generate(@NonNull AOTContext context) {
        List<String> serviceTypes = context.getConfiguration().stringList(OPTION.key());
        String path = "META-INF/native-image/" + context.getPackageName() + "/native-image.properties";
        context.registerGeneratedResource(path, propertiesFile -> {
            try (PrintWriter wrt = new PrintWriter(new FileWriter(propertiesFile))) {
                wrt.print("Args=");
                wrt.println("--initialize-at-build-time=io.micronaut.context.ApplicationContextConfigurer$1" + NEXT_LINE);
                wrt.println("     --initialize-at-build-time=" + context.getPackageName() + "." +
                    ApplicationContextConfigurerGenerator.CUSTOMIZER_CLASS_NAME +
                    NEXT_LINE);
                var buildTimeInit = context.getBuildTimeInitClasses()
                    .stream()
                    .map(clazz -> "     --initialize-at-build-time=" + clazz)
                    .collect(Collectors.joining(NEXT_LINE + "\n"));
                if (!buildTimeInit.isEmpty()) {
                    wrt.println(buildTimeInit);
                }
                if (context.getConfiguration()
                    .isFeatureEnabled(NativeStaticServiceLoaderSourceGenerator.ID)) {
                    for (int i = 0; i < serviceTypes.size(); i++) {
                        String serviceType = serviceTypes.get(i);
                        wrt.print("     -H:ServiceLoaderFeatureExcludeServices=" + serviceType);
                        if (i < serviceTypes.size() - 1) {
                            wrt.println(NEXT_LINE);
                        } else {
                            wrt.println();
                        }
                    }
                }
                wrt.println();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
