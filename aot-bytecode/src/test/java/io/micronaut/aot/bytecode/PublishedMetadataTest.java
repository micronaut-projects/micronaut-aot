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
package io.micronaut.aot.bytecode;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import java.io.File;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the module publishes: one jar with one {@code @Internal} entry point, and nothing else. A consumer, a build
 * plugin or a packaging library without dependencies of its own, must get neither a dependency nor a platform from
 * it.
 *
 * <p>The build passes the generated POM, the generated Gradle module metadata and the compile class path of the
 * main source set as system properties, and says that it is the build with {@value #BUILD_MARKER}. Without that
 * marker, as in an IDE, those tests are skipped. With it, a property that is missing is a failure: these tests are
 * the only evidence that the module publishes nothing else, and must not turn into skips when the build changes.</p>
 */
class PublishedMetadataTest {

    private static final String BUILD_MARKER = "aot.bytecode.build";

    private static final String INTERNAL = "Lio/micronaut/core/annotation/Internal;";

    /** Every public type and member of the module, which are all {@code @Internal}. */
    private static final List<String> PUBLIC_API = List.of(
            "io.micronaut.aot.bytecode.ClassPathTransform",
            "io.micronaut.aot.bytecode.ClassPathTransform#run(Lio/micronaut/aot/bytecode/ClassPathTransform$Request;)"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Result;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request#builder()"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request$Builder;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder#build()"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder#classPath(Ljava/util/List;)"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request$Builder;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder#outputDirectory(Ljava/nio/file/Path;)"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request$Builder;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder#parallelism(I)"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request$Builder;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Request$Builder#stripLocalVariables(Ljava/util/Collection;)"
                    + "Lio/micronaut/aot/bytecode/ClassPathTransform$Request$Builder;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Result",
            "io.micronaut.aot.bytecode.ClassPathTransform$Result#classPath()Ljava/util/List;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Result#report()Ljava/lang/String;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Result#summary()Ljava/lang/String;",
            "io.micronaut.aot.bytecode.ClassPathTransform$Result#warnings()Ljava/util/List;");

    @Test
    void thePomDeclaresNoDependencyAndNoPlatform() throws Exception {
        Path pom = file("aot.bytecode.pom");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());

        assertEquals("io.micronaut.aot", document.getElementsByTagName("groupId").item(0).getTextContent());
        assertEquals("micronaut-aot-bytecode", document.getElementsByTagName("artifactId").item(0).getTextContent());
        assertEquals(1, document.getElementsByTagName("groupId").getLength(), "only the module's own coordinates");
        for (String element : new String[] {"dependencies", "dependency", "dependencyManagement", "parent"}) {
            assertEquals(0, document.getElementsByTagName(element).getLength(), "<" + element + "> in " + pom);
        }
    }

    @Test
    void theGradleModuleMetadataDeclaresNoDependencyAndNoPlatform() throws Exception {
        String metadata = Files.readString(file("aot.bytecode.module"), StandardCharsets.UTF_8);

        assertTrue(metadata.contains("\"module\": \"micronaut-aot-bytecode\""), metadata);
        assertTrue(metadata.contains("\"name\": \"runtimeElements\""), metadata);
        assertTrue(metadata.contains("\"name\": \"apiElements\""), metadata);
        for (String key : new String[] {"\"dependencies\"", "\"dependencyConstraints\"", "\"available-at\"",
            "\"capabilities\"", "platform"}) {
            assertFalse(metadata.contains(key), key + " in " + metadata);
        }
    }

    @Test
    void theMainCodeCompilesAgainstMicronautCoreOnly() {
        String classpath = property("aot.bytecode.compileClasspath");

        for (String entry : classpath.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            assertTrue(name.startsWith("micronaut-core-"), entry);
        }
    }

    /**
     * The public API is the entry point and its request and result, every type of it {@code @Internal}. Everything
     * else is package-private.
     */
    @Test
    void theOnlyPublicTypesAreTheInternalEntryPointAndItsRequestAndResult() throws Exception {
        Path classes = Path.of(ClassPathTransform.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assumeTrue(Files.isDirectory(classes), "the main classes are not a directory");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(file -> file.toString().endsWith(".class")).sorted().toList();
        }

        List<String> api = new ArrayList<>();
        for (Path file : files) {
            ClassModel model = ClassFile.of().parse(file);
            if (!model.flags().has(AccessFlag.PUBLIC)) {
                continue;
            }
            String type = model.thisClass().asInternalName().replace('/', '.');
            api.add(type);
            assertTrue(annotations(model).contains(INTERNAL), type + " is @Internal");
            for (MethodModel method : model.methods()) {
                if (method.flags().has(AccessFlag.PUBLIC)) {
                    api.add(type + "#" + method.methodName().stringValue() + method.methodType().stringValue());
                }
            }
            for (FieldModel field : model.fields()) {
                assertFalse(field.flags().has(AccessFlag.PUBLIC), type + " has a public field");
            }
        }
        assertEquals(PUBLIC_API, api.stream().sorted().toList());
    }

    private static List<String> annotations(ClassModel model) {
        return model.findAttribute(Attributes.runtimeVisibleAnnotations())
                .map(attribute -> attribute.annotations().stream().map(Annotation::className)
                        .map(name -> name.stringValue()).toList())
                .orElse(List.of());
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
