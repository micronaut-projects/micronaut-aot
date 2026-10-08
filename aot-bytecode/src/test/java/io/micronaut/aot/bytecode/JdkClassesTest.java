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

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdkClasses} against the JDK that runs the tests: which packages are the JDK's, and what a generated class may
 * call in them. Ported from Micronaut Runner's test, with the packages of the modules that the application loader
 * defines now the JDK's too.
 */
class JdkClassesTest {

    @Test
    void thePackagesOfEveryBootLayerModuleAreTheJdks() {
        assertTrue(JdkClasses.owns("java/lang"));
        assertTrue(JdkClasses.owns("java/util/function"));
        assertTrue(JdkClasses.owns("java/sql"), "a platform loader module");
        assertTrue(JdkClasses.owns("jdk/internal/misc"), "an internal package is the JDK's too");
        assertTrue(JdkClasses.owns("com/sun/source/tree"), "a module the application loader defines");
        assertTrue(JdkClasses.owns("com/sun/tools/javac/main"), "and its packages that it does not export");
        assertFalse(JdkClasses.owns("com/example"));
        assertFalse(JdkClasses.owns(""));
    }

    @Test
    void aPublicClassOfAnExportedPackageAnswersWithItsDeclaredMethods() {
        JdkClasses.JdkClass string = JdkClasses.find("java/lang/String");

        assertNotNull(string);
        assertTrue(string.exported());
        assertNotEquals(0, string.flags() & ClassFile.ACC_PUBLIC);
        assertEquals("java/lang/Object", string.superName());
        assertTrue(string.interfaces().contains("java/io/Serializable"));
        Integer valueOf = string.method("valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
        assertNotNull(valueOf);
        assertTrue((valueOf & ClassFile.ACC_PUBLIC) != 0 && (valueOf & ClassFile.ACC_STATIC) != 0);
        assertEquals(0, valueOf & JdkClasses.JdkClass.CALLER_SENSITIVE);
        assertNull(string.method("valueOf", "(Ljava/lang/String;)Ljava/lang/String;"), "the exact descriptor");
        assertNull(string.method("hashCode", "()J"));
        assertNull(JdkClasses.find("java/util/ArrayList").method("stream", "()Ljava/util/stream/Stream;"),
                "an inherited method is not declared");
        assertNotNull(JdkClasses.find("java/util/ArrayList").method("<init>", "()V"));
        assertSame(string, JdkClasses.find("java/lang/String"), "a class is read once");
        JdkClasses.JdkClass trees = JdkClasses.find("com/sun/source/util/Trees");
        assertNotNull(trees, "a class of a module the application loader defines");
        assertTrue(trees.exported());
        assertNotNull(trees.method("getSourcePositions", "()Lcom/sun/source/util/SourcePositions;"));
    }

    @Test
    void callerSensitiveMethodsInternalPackagesAndMissingClassesAreTold() {
        Integer forName = JdkClasses.find("java/lang/Class").method("forName",
                "(Ljava/lang/String;)Ljava/lang/Class;");
        assertNotNull(forName);
        assertNotEquals(0, forName & JdkClasses.JdkClass.CALLER_SENSITIVE);
        assertEquals(0, JdkClasses.find("java/lang/Class").method("getName", "()Ljava/lang/String;")
                & JdkClasses.JdkClass.CALLER_SENSITIVE);

        JdkClasses.JdkClass internal = JdkClasses.find("jdk/internal/misc/Unsafe");
        assertNotNull(internal);
        assertFalse(internal.exported(), "java.base exports jdk.internal.misc to named modules only");

        assertNull(JdkClasses.find("java/lang/NoSuchClass"));
        assertNull(JdkClasses.find("com/example/Application"));
        assertNotEquals(0, JdkClasses.find("java/util/function/Function").flags() & ClassFile.ACC_INTERFACE);
    }

    @Test
    void theGateResolvesTheClassesOfEveryBootLayerModule() throws Exception {
        try (InputStream in = JdkClasses.open("com/sun/source/util/DocTrees")) {
            assertNotNull(in, "the class file of a module the application loader defines");
        }
        assertNull(JdkClasses.open("com/example/Application"));
        ClassPathModel model = ClassPathModel.merge(List.of());
        assertNotNull(model.getClassInfo(ClassDesc.of("com.sun.source.util.DocTrees")),
                "the platform class loader cannot see jdk.compiler, the gate can");
        assertNull(model.getClassInfo(ClassDesc.of("com.example.Application")));
    }
}
