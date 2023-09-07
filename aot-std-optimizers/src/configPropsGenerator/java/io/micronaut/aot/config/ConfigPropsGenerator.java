/*
 * Copyright 2003-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.aot.config;

import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.config.SourceGeneratorLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class ConfigPropsGenerator {
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("A single argument, the path to the output file, is expected");
        }
        try (var writer = new PrintWriter(Files.newBufferedWriter(Path.of(args[0]), StandardCharsets.UTF_8))) {
            var codegenerators = Stream.concat(
                    SourceGeneratorLoader.list(Runtime.JIT).stream(),
                    SourceGeneratorLoader.list(Runtime.NATIVE).stream()
                ).distinct()
                .toList();
            writer.println("WARNING: These are not configuration properties to add in your regular Micronaut configuration files," +
                           "but properties to be added to the Micronaut AOT configuration, via your build plugin." +
                           "Please refer to the appropriate Maven or Gradle plugin for more details.");
            writer.println();
            for (AOTModule module : codegenerators) {
                writer.println("=== Module " + module.id());
                writer.println();
                writer.print("This module is available ");
                var runtimes = module.enabledOn();
                if (runtimes.length == 1) {
                    writer.println("in " + runtimes[0].displayName() + " mode.");
                } else {
                    writer.println("in JIT and native modes.");
                }
                writer.println();
                writer.println(".Configuration Properties for " + module.id());
                writer.println("[cols=\"1,3,3\"]");
                writer.println("|===");
                writer.println("|Property|Description|Example value");
                writer.println("|" + module.id() + ".enabled|Enables the " + module.description() + " optimization|true");
                for (Option option : module.options()) {
                    // Add 0-width space so that text wrapping works
                    var sample = option.sampleValue().replace(",", "\u200B");
                    writer.println("|" + option.key() + "|" + option.description() + "|" + sample);
                }
                writer.println("|===");
            }
        }
    }
}
