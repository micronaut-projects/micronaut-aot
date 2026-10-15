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
package io.micronaut.aot.serviceindex;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the module publishes: one jar and nothing else. A consumer, a build plugin or a packaging library without
 * dependencies of its own, must get neither a dependency nor a platform from it, and the main code compiles against micronaut-core
 * (for {@code @Internal}, not transitively) and jspecify only.
 *
 * <p>The build passes the generated POM, the generated Gradle module metadata and the compile class path of the
 * main source set as system properties, and says that it is the build with {@value #BUILD_MARKER}. Without that
 * marker, as in an IDE, the tests are skipped. With it, a property that is missing is a failure: these tests are
 * the only evidence that the module publishes nothing else, and must not turn into skips when the build changes.</p>
 */
class PublishedMetadataTest {

    private static final String BUILD_MARKER = "aot.serviceindex.build";

    @Test
    void thePomDeclaresNoDependencyAndNoPlatform() throws Exception {
        Path pom = file("aot.serviceindex.pom");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());

        assertEquals("io.micronaut.aot", document.getElementsByTagName("groupId").item(0).getTextContent());
        assertEquals("micronaut-aot-service-index", document.getElementsByTagName("artifactId").item(0).getTextContent());
        assertEquals(1, document.getElementsByTagName("groupId").getLength(), "only the module's own coordinates");
        for (String element : new String[] {"dependencies", "dependency", "dependencyManagement", "parent"}) {
            assertEquals(0, document.getElementsByTagName(element).getLength(), "<" + element + "> in " + pom);
        }
    }

    @Test
    void theGradleModuleMetadataDeclaresNoDependencyAndNoPlatform() throws Exception {
        String metadata = Files.readString(file("aot.serviceindex.module"), StandardCharsets.UTF_8);

        assertTrue(metadata.contains("\"module\": \"micronaut-aot-service-index\""), metadata);
        assertTrue(metadata.contains("\"name\": \"runtimeElements\""), metadata);
        assertTrue(metadata.contains("\"name\": \"apiElements\""), metadata);
        for (String key : new String[] {"\"dependencies\"", "\"dependencyConstraints\"", "\"available-at\"",
            "\"capabilities\"", "platform"}) {
            assertFalse(metadata.contains(key), key + " in " + metadata);
        }
    }

    @Test
    void theMainCodeCompilesAgainstAnnotationsOnly() {
        String classpath = property("aot.serviceindex.compileClasspath");

        List<String> names = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            names.add(Path.of(entry).getFileName().toString().replaceAll("-[0-9][^-]*\\.jar$", ""));
        }
        assertEquals(List.of("micronaut-core", "jspecify"), names, classpath);
    }

    private static Path file(String property) {
        return Path.of(property(property));
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null) {
            assertNull(System.getProperty(BUILD_MARKER), "the build did not pass " + name);
        }
        assumeTrue(value != null, "not run by the build");
        return value;
    }
}
