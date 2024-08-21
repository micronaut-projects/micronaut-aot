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

import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Reads the version.txt generated file to feed it to picocli.
 */
public class VersionProvider implements CommandLine.IVersionProvider {

    private static final String VERSION;

    static {
        String text = "unknown";
        try (InputStream stream = VersionProvider.class.getResourceAsStream("/version.txt")) {
            text = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
        } catch (Exception ex) {
            // noop
        }
        VERSION = text;
    }

    @Override
    public String[] getVersion() {
        return new String[] {
            VERSION
        };
    }
}
