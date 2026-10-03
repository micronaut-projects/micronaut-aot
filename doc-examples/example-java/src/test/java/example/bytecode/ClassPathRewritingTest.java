/*
 * Copyright 2017-2026 original authors
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
package example.bytecode;

import io.micronaut.context.ApplicationContext;
import io.micronaut.core.annotation.Internal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassPathRewritingTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void stripsTheThirdPartyJarsOfAClassPath() throws Exception {
        Path core = jarOf(Internal.class);
        Path context = jarOf(ApplicationContext.class);
        Path outputDirectory = temporaryDirectory.resolve("stripped");
        Path reportFile = temporaryDirectory.resolve("strip-report.tsv");
        List<String> log = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        List<Path> packaged = ClassPathRewriting.stripThirdPartyJars(List.of(context, core), List.of(core),
            outputDirectory, reportFile, log::add, warnings::add);

        assertEquals(List.of(context, outputDirectory.resolve("1").resolve(core.getFileName())), packaged,
            "only the named jar is replaced, by a copy of the same name");
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("Stripped local-variable tables from "), log::toString);
        assertEquals(List.of(), warnings);
        assertTrue(Files.size(packaged.get(1)) < Files.size(core), "the copy is smaller");
        assertTrue(Files.readString(reportFile).startsWith("entry\tstep\t"));
    }

    private static Path jarOf(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
