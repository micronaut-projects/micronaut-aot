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

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.classfile.constantpool.ConstantDynamicEntry;
import java.lang.classfile.constantpool.InvokeDynamicEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.instruction.InvokeInstruction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The form of the generated classes: what loads in every JVM that has the service index, costs nothing to link, and
 * calls exactly the API of micronaut-core it was checked against.
 */
class LoaderShapeTest {

    private static final String OF_TRUSTED =
            "(Ljava/lang/ClassLoader;Ljava/util/Map;Ljava/util/Map;Ljava/util/List;)Lio/micronaut/core/io/service/ServiceIndex;";

    @TempDir
    Path temporary;

    @Test
    void theGeneratedClassesHaveTheExpectedForm() throws Exception {
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(ServiceIndexGenerator.Status.GENERATED, result.status(), result.message());
        List<String> names = List.copyOf(result.entries().keySet());
        assertEquals(List.of(
                "io/micronaut/aot/serviceindex/generated/ServiceIndexLoader.class",
                "io/micronaut/aot/serviceindex/generated/ServiceIndexNames.class",
                "io/micronaut/aot/serviceindex/generated/ServiceIndexPart0.class",
                ServiceIndexGenerator.LOADER_SERVICE), names);
        for (Map.Entry<String, byte[]> entry : result.entries().entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            ClassModel model = ClassFile.of().parse(entry.getValue());
            assertEquals(ClassFile.JAVA_25_VERSION, model.majorVersion(), entry.getKey());
            assertEquals(0, model.minorVersion(), entry.getKey());
            for (PoolEntry poolEntry : model.constantPool()) {
                assertFalse(poolEntry instanceof InvokeDynamicEntry || poolEntry instanceof ConstantDynamicEntry,
                        entry.getKey() + ": " + poolEntry);
            }
            for (MethodModel method : model.methods()) {
                assertFalse(method.methodName().equalsString("<clinit>"), entry.getKey());
            }
            for (FieldModel field : model.fields()) {
                assertFalse(field.flags().has(java.lang.reflect.AccessFlag.STATIC), entry.getKey() + "." + field.fieldName());
            }
            boolean loader = entry.getKey().endsWith("/ServiceIndexLoader.class");
            assertEquals(loader, model.flags().has(java.lang.reflect.AccessFlag.PUBLIC), entry.getKey());
        }

        ClassModel loader = ClassFile.of().parse(result.entries().get(names.get(0)));
        assertTrue(loader.flags().has(java.lang.reflect.AccessFlag.FINAL));
        assertEquals(List.of("io/micronaut/core/optim/StaticOptimizations$Loader"),
                loader.interfaces().stream().map(type -> type.asInternalName()).toList());
        assertEquals("Ljava/lang/Object;Lio/micronaut/core/optim/StaticOptimizations$Loader<Lio/micronaut/core/io/service/ServiceIndex;>;",
                loader.findAttribute(Attributes.signature()).map(SignatureAttribute::signature)
                        .map(signature -> signature.stringValue()).orElse(null));
        assertTrue(hasPublicMethod(loader, "<init>", "()V"));
        assertTrue(hasPublicMethod(loader, "load", "()Ljava/lang/Object;"));
        List<String> calls = new ArrayList<>();
        for (MethodModel method : loader.methods()) {
            method.code().ifPresent(code -> code.forEach(element -> {
                if (element instanceof InvokeInstruction invoke) {
                    calls.add(invoke.owner().asInternalName() + "." + invoke.name().stringValue()
                            + invoke.type().stringValue());
                }
            }));
        }
        assertTrue(calls.contains("io/micronaut/core/io/service/ServiceIndex.ofTrusted" + OF_TRUSTED), calls::toString);
        assertTrue(new String(result.entries().get(ServiceIndexGenerator.LOADER_SERVICE), StandardCharsets.UTF_8)
                .startsWith(ServiceIndexGenerator.LOADER_CLASS + "\n"));
    }

    private static boolean hasPublicMethod(ClassModel model, String name, String descriptor) {
        return model.methods().stream().anyMatch(method -> method.methodName().equalsString(name)
                && method.methodType().equalsString(descriptor)
                && method.flags().has(java.lang.reflect.AccessFlag.PUBLIC));
    }
}
