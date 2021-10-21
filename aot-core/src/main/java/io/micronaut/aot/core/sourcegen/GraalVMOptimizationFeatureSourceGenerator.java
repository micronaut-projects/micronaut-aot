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
public class GraalVMOptimizationFeatureSourceGenerator extends AbstractSourceGenerator {

    private final String simpleServiceTypeName;
    private final List<String> serviceTypes;

    public GraalVMOptimizationFeatureSourceGenerator(SourceGenerationContext context,
                                                     String simpleServiceTypeName,
                                                     List<String> serviceTypes) {
        super(context);
        this.simpleServiceTypeName = simpleServiceTypeName;
        this.serviceTypes = serviceTypes;
    }

    @Override
    public void generateResourceFiles(File targetDirectory) {
        File nativeImageDir = new File(targetDirectory, "META-INF/native-image/" + getContext().getPackageName());
        if (nativeImageDir.isDirectory() || nativeImageDir.mkdirs()) {
            File propertiesFile = new File(nativeImageDir, "native-image.properties");
            try (PrintWriter wrt = new PrintWriter(new FileWriter(propertiesFile))) {
                wrt.println("--initialize-at-build-time=" + getContext().getPackageName() + "." + simpleServiceTypeName);
                for (String serviceType : serviceTypes) {
                    wrt.println("-H:ServiceLoaderFeatureExcludeServices=" + serviceType);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
