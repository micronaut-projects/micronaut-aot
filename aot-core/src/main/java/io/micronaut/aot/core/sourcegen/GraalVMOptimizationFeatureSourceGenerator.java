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
package io.micronaut.aot.core.sourcegen;

import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.core.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Generates the GraalVM configuration file which is going to configure
 * the native image code generation, typically asking to initialize
 * the optimized entry point at build time.
 */
public class GraalVMOptimizationFeatureSourceGenerator extends AbstractSourceGenerator {
    public static final String ID = "graalvm.config";
    public static final Option OPTION = AbstractStaticServiceLoaderSourceGenerator.SERVICE_TYPES;
    public static final String DESCRIPTION = "Generates GraalVM configuration files required to load the AOT optimizations";
    private static final String NEXT_LINE = " \\";

    @Override
    @NonNull
    public Set<Option> getConfigurationOptions() {
        return Collections.singleton(OPTION);
    }

    @Override
    @NonNull
    public String getId() {
        return ID;
    }

    @Override
    @NonNull
    public Optional<String> getDescription() {
        return Optional.of(DESCRIPTION);
    }

    @Override
    public boolean isEnabledOn(@NonNull Runtime runtime) {
        return runtime == Runtime.NATIVE;
    }

    @Override
    public void generateResourceFiles(@NonNull File targetDirectory) {
        List<String> serviceTypes = context.getConfiguration().stringList(OPTION.getKey());
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
