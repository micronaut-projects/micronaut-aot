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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The module's API is for the Micronaut build plugins and Micronaut Runner only: every public type is
 * {@code @Internal}, and nothing is public that they do not call.
 */
class ApiSurfaceTest {

    private static final String INTERNAL = "Lio/micronaut/core/annotation/Internal;";

    @TempDir
    Path temporary;

    @Test
    void thePublicMembersAreTheOnesTheCallersUse() {
        TreeSet<String> members = new TreeSet<>();
        for (Class<?> type : publicTypes()) {
            for (Method method : type.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()) {
                    members.add(type.getSimpleName() + "." + method.getName() + Arrays.stream(method.getParameterTypes())
                            .map(Class::getSimpleName).toList());
                }
            }
            if (Arrays.stream(type.getDeclaredConstructors()).anyMatch(c -> Modifier.isPublic(c.getModifiers()))) {
                members.add(type.getSimpleName() + ".<init>");
            }
            Arrays.stream(type.getDeclaredFields()).filter(field -> Modifier.isPublic(field.getModifiers()))
                    .forEach(field -> members.add(type.getSimpleName() + "#" + field.getName()));
        }

        assertEquals(new TreeSet<>(List.of(
                "ServiceIndexGenerator.generate[Request]",
                "Request.builder[]",
                "Builder.entry[Path]",
                "Builder.entry[Path, String]",
                "Builder.holder[Path]",
                "Builder.build[]",
                "Result.status[]",
                "Result.message[]",
                "Result.entries[]",
                "Result.removals[]",
                "Status.values[]",
                "Status.valueOf[String]",
                "Status#GENERATED",
                "Status#STOOD_DOWN",
                "Status#FAILED")), members);
    }

    @Test
    void everyPublicTypeIsInternal() throws IOException, URISyntaxException {
        List<String> types = new ArrayList<>();
        Path root = Path.of(ServiceIndexGenerator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                boolean isPublic = model.flags().has(java.lang.reflect.AccessFlag.PUBLIC);
                if (isPublic) {
                    types.add(model.thisClass().asInternalName());
                    assertTrue(annotations(model).contains(INTERNAL), model.thisClass().asInternalName());
                }
            }
        }
        assertEquals(List.of(
                "io/micronaut/aot/serviceindex/ServiceIndexGenerator",
                "io/micronaut/aot/serviceindex/ServiceIndexGenerator$Request",
                "io/micronaut/aot/serviceindex/ServiceIndexGenerator$Request$Builder",
                "io/micronaut/aot/serviceindex/ServiceIndexGenerator$Result",
                "io/micronaut/aot/serviceindex/ServiceIndexGenerator$Status"), types.stream().sorted().toList());
    }

    @Test
    void aRequestNeedsAClassPathAndAHolderOnIt() {
        Path jar = temporary.resolve("app.jar");
        Path other = temporary.resolve("lib.jar");

        assertThrows(IllegalStateException.class, () -> ServiceIndexGenerator.Request.builder().holder(jar).build());
        assertThrows(IllegalStateException.class, () -> ServiceIndexGenerator.Request.builder().entry(jar).build());
        assertThrows(IllegalStateException.class, () -> ServiceIndexGenerator.Request.builder().entry(jar)
                .holder(other).build());
        assertThrows(IllegalStateException.class, () -> ServiceIndexGenerator.Request.builder().entry(jar, "app.jar")
                .entry(other).holder(jar).build());
        assertThrows(IllegalStateException.class, () -> ServiceIndexGenerator.Request.builder().entry(jar)
                .entry(temporary.resolve("x/../app.jar")).holder(jar).build());
        assertThrows(IllegalArgumentException.class, () -> ServiceIndexGenerator.Request.builder()
                .entry(jar, "libs/app.jar"));
        // The holder is found by its normalized absolute path.
        ServiceIndexGenerator.Request.builder().entry(jar).holder(temporary.resolve("x/../app.jar")).build();
    }

    @Test
    void theResultCannotBeChanged() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        assertThrows(UnsupportedOperationException.class, () -> result.entries().put("other", new byte[0]));
        assertThrows(UnsupportedOperationException.class, () -> result.removals().add("other"));
        assertEquals(Map.of(), ServiceIndexGenerator.generate(ServiceIndexGenerator.Request.builder()
                .entry(temporary.resolve("missing.jar")).holder(temporary.resolve("missing.jar")).build()).entries());
    }

    private static List<Class<?>> publicTypes() {
        List<Class<?>> types = new ArrayList<>(List.of(ServiceIndexGenerator.class));
        for (int i = 0; i < types.size(); i++) {
            for (Class<?> nested : types.get(i).getDeclaredClasses()) {
                if (Modifier.isPublic(nested.getModifiers())) {
                    types.add(nested);
                }
            }
        }
        return types;
    }

    private static List<String> annotations(ClassModel model) {
        List<String> names = new ArrayList<>();
        model.findAttribute(Attributes.runtimeVisibleAnnotations()).map(RuntimeVisibleAnnotationsAttribute::annotations)
                .orElse(List.of()).stream().map(Annotation::className).forEach(name -> names.add(name.stringValue()));
        model.findAttribute(Attributes.runtimeInvisibleAnnotations()).map(RuntimeInvisibleAnnotationsAttribute::annotations)
                .orElse(List.of()).stream().map(Annotation::className).forEach(name -> names.add(name.stringValue()));
        return names;
    }
}
