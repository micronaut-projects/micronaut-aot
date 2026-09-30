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
package io.micronaut.aot.logback;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the module publishes: one jar and nothing else. A consumer, a build plugin or a packaging library without
 * dependencies of its own, must get neither a dependency nor a platform from it.
 *
 * <p>The build passes the generated POM, the generated Gradle module metadata and the compile class path of the
 * main source set as system properties; without them, as in an IDE, the tests are skipped.</p>
 */
class PublishedMetadataTest {

    @Test
    void thePomDeclaresNoDependencyAndNoPlatform() throws Exception {
        Path pom = file("aot.logback.pom");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());

        assertEquals("io.micronaut.aot", document.getElementsByTagName("groupId").item(0).getTextContent());
        assertEquals("micronaut-aot-logback", document.getElementsByTagName("artifactId").item(0).getTextContent());
        assertEquals(1, document.getElementsByTagName("groupId").getLength(), "only the module's own coordinates");
        for (String element : new String[] {"dependencies", "dependency", "dependencyManagement", "parent"}) {
            assertEquals(0, document.getElementsByTagName(element).getLength(), "<" + element + "> in " + pom);
        }
    }

    @Test
    void theGradleModuleMetadataDeclaresNoDependencyAndNoPlatform() throws Exception {
        String metadata = Files.readString(file("aot.logback.module"), StandardCharsets.UTF_8);

        assertTrue(metadata.contains("\"module\": \"micronaut-aot-logback\""), metadata);
        assertTrue(metadata.contains("\"name\": \"runtimeElements\""), metadata);
        assertTrue(metadata.contains("\"name\": \"apiElements\""), metadata);
        for (String key : new String[] {"\"dependencies\"", "\"dependencyConstraints\"", "\"available-at\"",
            "\"capabilities\"", "platform"}) {
            assertFalse(metadata.contains(key), key + " in " + metadata);
        }
    }

    @Test
    void theMainCodeCompilesWithoutLogbackAndSlf4j() {
        String classpath = System.getProperty("aot.logback.compileClasspath");
        assumeTrue(classpath != null, "not run by the build");

        for (String entry : classpath.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            assertFalse(name.contains("logback") && !name.contains("micronaut"), entry);
            assertFalse(name.contains("slf4j"), entry);
        }
    }

    private static Path file(String property) {
        String value = System.getProperty(property);
        assumeTrue(value != null, "not run by the build");
        return Path.of(value);
    }
}
