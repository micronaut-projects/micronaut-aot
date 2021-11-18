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

import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.aot.core.sourcegen.AbstractSourceGenerator;
import io.micronaut.aot.core.sourcegen.ApplicationContextConfigurerGenerator;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

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
public class GraalVMOptimizationFeatureSourceGenerator extends AbstractSourceGenerator {
    public static final String ID = "graalvm.config";
    public static final String DESCRIPTION = "Generates GraalVM configuration files required to load the AOT optimizations";
    private static final String NEXT_LINE = " \\";

    private static final Option OPTION = MetadataUtils.findOption(GraalVMOptimizationFeatureSourceGenerator.class, "service.types");

    @Override
    public void generateResourceFiles(@NonNull File targetDirectory) {
        List<String> serviceTypes = context.getConfiguration().stringList(OPTION.key());
        File nativeImageDir = new File(targetDirectory, "META-INF/native-image/" + getContext().getPackageName());
        if (nativeImageDir.isDirectory() || nativeImageDir.mkdirs()) {
            File propertiesFile = new File(nativeImageDir, "native-image.properties");
            try (PrintWriter wrt = new PrintWriter(new FileWriter(propertiesFile))) {
                wrt.print("Args=");
                wrt.println("--initialize-at-build-time=" + getContext().getPackageName() + "." + ApplicationContextConfigurerGenerator.CUSTOMIZER_CLASS_NAME + NEXT_LINE);
                for (int i = 0; i < serviceTypes.size(); i++) {
                    String serviceType = serviceTypes.get(i);
                    wrt.print("     -H:ServiceLoaderFeatureExcludeServices=" + serviceType);
                    if (i < serviceTypes.size() - 1) {
                        wrt.println(NEXT_LINE);
                    } else {
                        wrt.println();
                    }
                }
                wrt.println();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
