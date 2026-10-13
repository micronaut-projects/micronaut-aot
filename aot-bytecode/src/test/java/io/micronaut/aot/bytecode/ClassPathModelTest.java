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
import org.junit.jupiter.api.io.TempDir;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver.ClassHierarchyInfo;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClassPathModel}: which copy of a class wins, the class hierarchy it answers for verification, and the
 * watched classes it reports. The classes are generated with the ClassFile API, each copy with its own superclass
 * so that the winning copy is visible in the hierarchy.
 */
class ClassPathModelTest {

    private static final String SHARED = "com/example/Shared";

    @TempDir
    Path temp;

    @Test
    void anEarlierEntryWinsOverALaterOne() {
        ClassPathModel model = model(
                layer("application", false, Map.of(entry(SHARED), type(SHARED, "com/example/FromApplication"))),
                layer("first", false, Map.of(
                        entry(SHARED), type(SHARED, "com/example/FromFirst"),
                        entry("com/example/Dep"), type("com/example/Dep", "com/example/FromFirst"))),
                layer("second", false, Map.of(
                        entry("com/example/Dep"), type("com/example/Dep", "com/example/FromSecond"),
                        entry("com/example/Only"), type("com/example/Only", "com/example/FromSecond"))));

        assertEquals(superclass("com/example/FromApplication"), info(model, SHARED));
        assertEquals(superclass("com/example/FromFirst"), info(model, "com/example/Dep"));
        assertEquals(superclass("com/example/FromSecond"), info(model, "com/example/Only"));
        assertEquals(3, model.size());
    }

    /**
     * What desugaring needs to tell a certain name from an uncertain one: how many entries hold a name, whether any
     * holds a versioned copy of it, whatever its manifest says, and which packages the class path holds. A class too
     * large to read, or whose entry does not match its name, still takes its name.
     */
    @Test
    void theModelCountsTheEntriesThatHoldANameAndItsVersionedCopies() {
        ClassPathModel model = model(
                layer("first", false, Map.of(
                        entry(SHARED), type(SHARED, "java/lang/Object"),
                        "META-INF/versions/11/com/example/Versioned.class", type("com/example/Versioned",
                                "java/lang/Object"),
                        "META-INF/versions/09/com/example/Leading.class", type("com/example/Leading",
                                "java/lang/Object"))),
                layer("second", false, Map.of(
                        entry(SHARED), type(SHARED, "java/lang/Object"),
                        entry("com/example/Versioned"), type("com/example/Versioned", "java/lang/Object"),
                        entry("org/other/Misnamed"), type("org/other/Elsewhere", "java/lang/Object"))));

        assertEquals(2, model.holders(SHARED));
        assertEquals(1, model.holders("com/example/Versioned"));
        assertTrue(model.versioned("com/example/Versioned"), "a versioned copy in a jar that is not multi-release");
        assertFalse(model.versioned(SHARED));
        assertFalse(model.versioned("com/example/Leading"), "a leading zero is no version");
        assertEquals(1, model.holders("org/other/Misnamed"));
        assertTrue(model.known("org/other/Misnamed"), "its name is taken, though no loader can define it");
        assertTrue(model.holdsPackage("org/other"));
        assertFalse(model.holdsPackage("org"));
        assertEquals(0, model.holders("com/example/Nowhere"));
        assertFalse(model.known("com/example/Nowhere"));
        assertFalse(model.hasMembers(), "a header-only scan records no member table");
        assertFalse(model.hasLambdas(0));
    }

    @Test
    void theWinnerOfAMultiReleaseJarIsTheHighestVariantTheRunningJdkLoads() {
        int feature = Runtime.version().feature();
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entry(SHARED), type(SHARED, "com/example/Base"));
        entries.put("META-INF/versions/11/" + entry(SHARED), type(SHARED, "com/example/Eleven"));
        entries.put("META-INF/versions/" + (feature + 1) + "/" + entry(SHARED), type(SHARED, "com/example/Newer"));
        entries.put("META-INF/versions/" + (feature + 1) + "/" + entry("com/example/Future"),
                type("com/example/Future", "java/lang/Object"));
        entries.put(entry("com/example/Plain"), type("com/example/Plain", "com/example/Base"));
        ClassPathModel model = model(layer("multi-release", true, entries));

        assertEquals(superclass("com/example/Eleven"), info(model, SHARED));
        assertEquals(superclass("com/example/Base"), info(model, "com/example/Plain"));
        assertNull(info(model, "com/example/Future"), "only a newer JDK loads it");
    }

    @Test
    void versionedEntriesOfAJarThatIsNotMultiReleaseAndAVersionWithALeadingZeroAreIgnored() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entry(SHARED), type(SHARED, "com/example/Base"));
        entries.put("META-INF/versions/11/" + entry(SHARED), type(SHARED, "com/example/Eleven"));
        ClassPathModel plain = model(layer("plain", false, entries));
        assertEquals(superclass("com/example/Base"), info(plain, SHARED));

        ClassPathModel leadingZero = model(layer("multi-release", true, Map.of(
                entry(SHARED), type(SHARED, "com/example/Base"),
                "META-INF/versions/011/" + entry(SHARED), type(SHARED, "com/example/Eleven"))));
        assertEquals(superclass("com/example/Base"), info(leadingZero, SHARED));
        assertEquals(1, leadingZero.size(), "META-INF/versions/011/ is an ordinary entry, not a class");
    }

    @Test
    void aJarIsMultiReleaseOnlyWhenItsManifestSaysSoAndADirectoryNever() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entry(SHARED), type(SHARED, "com/example/Base"));
        entries.put("META-INF/versions/11/" + entry(SHARED), type(SHARED, "com/example/Eleven"));
        Manifest multiRelease = new Manifest();
        multiRelease.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        multiRelease.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        Path declared = ClassFixtures.jar(temp.resolve("declared.jar"), multiRelease, entries);
        Path undeclared = ClassFixtures.jar(temp.resolve("undeclared.jar"), entries);
        Path directory = temp.resolve("classes");
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            Path file = directory.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        for (Path entry : List.of(declared, undeclared, directory)) {
            ClassPathModel model = ClassPathModel.merge(List.of(ClassPathModel.scan(entry, entry.toString(),
                    name -> false)));
            String expected = entry == declared ? "com/example/Eleven" : "com/example/Base";
            assertEquals(superclass(expected), info(model, SHARED), entry::toString);
        }
    }

    @Test
    void anEntryThatDoesNotExistOrIsNoArchiveHoldsNoClass() throws Exception {
        Path missing = temp.resolve("missing.jar");
        Path notAJar = Files.writeString(temp.resolve("not-a.jar"), "not a zip archive");

        for (Path entry : List.of(missing, notAJar)) {
            ClassPathModel model = ClassPathModel.merge(List.of(ClassPathModel.scan(entry, entry.toString(),
                    name -> true)));
            assertEquals(0, model.size(), entry::toString);
            assertTrue(model.watched().isEmpty(), entry::toString);
        }
    }

    @Test
    void theHierarchyAnswersForModelClassesAndForJdkClasses() {
        ClassPathModel model = model(layer("application", false, Map.of(
                entry("com/example/Api"), iface("com/example/Api"),
                entry("com/example/Impl"), type("com/example/Impl", "java/util/AbstractList"))));

        assertEquals(ClassHierarchyInfo.ofInterface(), model.getClassInfo(ClassDesc.ofInternalName("com/example/Api")));
        assertEquals(ClassHierarchyInfo.ofClass(ClassDesc.of("java.util.AbstractList")),
                model.getClassInfo(ClassDesc.ofInternalName("com/example/Impl")));
        assertEquals(ClassHierarchyInfo.ofInterface(), model.getClassInfo(ClassDesc.of("java.util.List")));
        assertEquals(ClassHierarchyInfo.ofClass(ClassDesc.of("java.util.AbstractCollection")),
                model.getClassInfo(ClassDesc.of("java.util.AbstractList")));
        assertNull(model.getClassInfo(ClassDesc.of("com.example.Nowhere")), "neither on the class path nor in the JDK");
        assertNull(model.getClassInfo(ClassDesc.of("org.junit.jupiter.api.Test")),
                "the build tool's own class path is not the application's");
    }

    @Test
    void theFirstWatchedClassIsReportedAndAClassWhoseNameDoesNotMatchItsEntryIsLeftOut() {
        Predicate<String> watch = name -> name.startsWith("com/watched/");
        ClassPathModel model = model(watch,
                layer("application", false, Map.of(entry("com/example/Wrong"), type("com/example/Right",
                        "java/lang/Object"))),
                layer("first", false, Map.of("com/watched/First.class", new byte[0])),
                layer("second", false, Map.of("com/watched/Second.class", new byte[0])));

        assertEquals(new ClassPathModel.Watched("first", "com/watched/First.class"), model.watched().orElseThrow());
        assertEquals(0, model.size(), "neither the misnamed class nor the empty watched ones are recorded");
    }

    @Test
    void aClassWhoseSuperclassCannotBeReadIsLeftOutWithoutFailingTheScan() {
        byte[] implementation = implementation("com/example/Impl");
        assertThrows(IllegalArgumentException.class, () -> ClassFile.of().parse(
                ClassFixtures.withCorruptSuperclass(implementation)).superclass(), "the fixture is corrupt");

        ClassPathModel model = model(layer("dependency", false, Map.of(
                entry("com/example/Impl"), implementation,
                entry("com/example/BadSuper"), ClassFixtures.withCorruptSuperclass(
                        implementation("com/example/BadSuper")))));

        assertNull(info(model, "com/example/BadSuper"));
        assertEquals(superclass("java/util/AbstractList"), info(model, "com/example/Impl"),
                "the class next to it is recorded");
        assertEquals(1, model.size());
    }

    @Test
    void noTruncationOrByteFlipOfAClassMakesTheScanThrow() {
        byte[] implementation = implementation("com/example/Impl");
        List<byte[]> damaged = new ArrayList<>();
        for (int length = 0; length < implementation.length; length++) {
            damaged.add(Arrays.copyOf(implementation, length));
        }
        for (int position = 0; position < implementation.length; position++) {
            for (int mask : new int[] {0x01, 0x80, 0xFF}) {
                byte[] flipped = implementation.clone();
                flipped[position] ^= (byte) mask;
                damaged.add(flipped);
            }
        }

        ClassPathModel.LayerScan scan = ClassPathModel.scan("dependency", false, name -> false);
        for (byte[] bytes : damaged) {
            scan.accept(entry("com/example/Impl"), bytes);
        }
        scan.accept(entry("com/example/Impl"), implementation);
        assertEquals(1, ClassPathModel.merge(List.of(scan)).size());
    }

    @Test
    void aClassAboveTheSizeLimitAndModuleInfoAreNotRead() {
        ClassPathModel.LayerScan scan = ClassPathModel.scan("dependency", false, name -> false);

        assertTrue(scan.wants(entry("com/example/Large"), ClassPathModel.MAX_CLASS_SIZE));
        assertFalse(scan.wants(entry("com/example/Large"), ClassPathModel.MAX_CLASS_SIZE + 1L));
        assertFalse(scan.wants("module-info.class", 100));
        assertFalse(scan.wants("com/example/data.txt", 100));
    }

    private static ClassHierarchyInfo info(ClassPathModel model, String internalName) {
        return model.getClassInfo(ClassDesc.ofInternalName(internalName));
    }

    private static ClassHierarchyInfo superclass(String internalName) {
        return ClassHierarchyInfo.ofClass(ClassDesc.ofInternalName(internalName));
    }

    private static ClassPathModel model(Layer... layers) {
        return model(name -> false, layers);
    }

    private static ClassPathModel model(Predicate<String> watch, Layer... layers) {
        List<ClassPathModel.LayerScan> scans = new ArrayList<>();
        for (Layer layer : layers) {
            ClassPathModel.LayerScan scan = ClassPathModel.scan(layer.name, layer.multiRelease, watch);
            Map<String, byte[]> ordered = layer.entries instanceof LinkedHashMap ? layer.entries
                    : new TreeMap<>(layer.entries);
            for (Map.Entry<String, byte[]> entry : ordered.entrySet()) {
                if (scan.wants(entry.getKey(), entry.getValue().length)) {
                    scan.accept(entry.getKey(), entry.getValue());
                }
            }
            scans.add(scan);
        }
        return ClassPathModel.merge(scans);
    }

    private static Layer layer(String name, boolean multiRelease, Map<String, byte[]> entries) {
        return new Layer(name, multiRelease, entries);
    }

    private static String entry(String internalName) {
        return internalName + ".class";
    }

    private static byte[] type(String internalName, String superName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.ofInternalName(superName)));
    }

    /** A class with a superclass, an interface, a field and a method, so that every part of the header exists. */
    private static byte[] implementation(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_SUPER)
                .withSuperclass(ClassDesc.of("java.util.AbstractList"))
                .withInterfaceSymbols(ClassDesc.of("java.lang.Runnable"))
                .withField("count", ConstantDescs.CD_int, ClassFile.ACC_PRIVATE)
                .withMethod("run", MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC
                        | ClassFile.ACC_ABSTRACT, method -> { }));
    }

    private static byte[] iface(String internalName) {
        return ClassFile.of().build(ClassDesc.ofInternalName(internalName), builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT));
    }

    private record Layer(String name, boolean multiRelease, Map<String, byte[]> entries) {
    }
}
