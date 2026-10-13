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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.Status.FAILED;
import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.Status.GENERATED;
import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.Status.STOOD_DOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every class path the index cannot describe generates nothing, and says why: a stand-down for what is expected of
 * some applications, a failure for what is wrong.
 */
class StandDownTest {

    private static final ClassDesc CLASS_LOADER = ClassDesc.of("java.lang.ClassLoader");

    private static final String LOADER_ENTRY = ServiceIndexGenerator.GENERATED_PATH + "ServiceIndexLoader.class";

    @TempDir
    Path temporary;

    @Test
    void noMicronautCore() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());

        assertNothing(STOOD_DOWN, "there is no micronaut-core on the class path", generate(holder, List.of()));
    }

    @Test
    void aMicronautCoreWithoutTheBuilder() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        Path legacy = Fixtures.legacyCore();
        String version = legacy.getFileName().toString().replace("micronaut-core-", "").replace(".jar", "");

        assertNothing(STOOD_DOWN, "micronaut-core " + version + " has no ServiceIndexBuilder: the service index needs"
                + " micronaut-core 5.3.0 or later", generate(holder, List.of(legacy)));
    }

    /** A micronaut-core whose service index API is not the one the generated code calls. */
    enum OtherApi {
        BUILD_TAKES_A_SET("ServiceIndexBuilder", "build"),
        OF_TRUSTED_WITHOUT_A_CLASS_PATH("ServiceIndex", "ofTrusted"),
        PRIVATE_CLASS_PATH_ENTRY("ServiceIndex$ClassPathEntry", "<init>");

        final String type;
        final String member;

        OtherApi(String type, String member) {
            this.type = type;
            this.member = member;
        }
    }

    @ParameterizedTest
    @EnumSource(OtherApi.class)
    void aMicronautCoreWithAnotherApi(OtherApi api) throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        Fixtures.Content core = Fixtures.read(Fixtures.coreJar());
        String entry = "io/micronaut/core/io/service/" + api.type + ".class";
        ClassDesc index = ClassDesc.of("io.micronaut.core.io.service.ServiceIndex");
        ClassFile classFile = ClassFile.of();
        ClassTransform transform = switch (api) {
            case BUILD_TAKES_A_SET -> replacing("build", MethodTypeDesc.of(index, CLASS_LOADER,
                    ConstantDescs.CD_Set));
            case OF_TRUSTED_WITHOUT_A_CLASS_PATH -> replacing("ofTrusted", MethodTypeDesc.of(index,
                    CLASS_LOADER, ConstantDescs.CD_Map, ConstantDescs.CD_Map));
            case PRIVATE_CLASS_PATH_ENTRY -> ClassTransform.transformingMethods(
                    method -> method.methodName().equalsString(ConstantDescs.INIT_NAME),
                    (method, element) -> {
                        if (element instanceof AccessFlags) {
                            method.withFlags(ClassFile.ACC_PRIVATE);
                        } else {
                            method.with(element);
                        }
                    });
        };
        core.put(entry, classFile.transformClass(classFile.parse(core.entries.get(entry)), transform));
        List<Path> libraries = new ArrayList<>(Fixtures.core());
        libraries.set(libraries.indexOf(Fixtures.coreJar()), Fixtures.jar(temporary.resolve("micronaut-core.jar"), core));

        ServiceIndexGenerator.Result result = generate(holder, libraries);

        assertNothing(STOOD_DOWN, "has another service index API than the one the generated code calls", result);
        assertTrue(result.message().contains(api.member), result::message);
    }

    @Test
    void anIndexLoaderThatAnotherEntryHolds() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        Path other = Fixtures.jar(temporary.resolve("other.jar"), Fixtures.content().put(LOADER_ENTRY, new byte[0]));

        assertNothing(STOOD_DOWN, "other.jar already holds a service index loader", generate(holder, with(other)));
    }

    @Test
    void anIndexLoaderThatAnotherEntryRegisters() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        Path other = Fixtures.jar(temporary.resolve("other.jar"), Fixtures.content()
                .put(ServiceIndexGenerator.LOADER_SERVICE, ServiceIndexGenerator.LOADER_CLASS + "\n"));

        assertNothing(STOOD_DOWN, "other.jar already registers a service index loader", generate(holder, with(other)));
    }

    @Test
    void aGeneratedNameThatAnotherEntryHolds() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        Path other = Fixtures.jar(temporary.resolve("other.jar"), Fixtures.content()
                .put(ServiceIndexGenerator.GENERATED_PATH + "ServiceIndexPart0.class", new byte[0]));

        assertNothing(FAILED, "other.jar holds io/micronaut/aot/serviceindex/generated/ServiceIndexPart0.class",
                generate(holder, with(other)));
    }

    @Test
    void aManifestThatNamesAFileOutsideTheClassPath() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application()
                .manifest(Map.of("Class-Path", "lib/extra.jar")));
        Fixtures.jar(temporary.resolve("lib/extra.jar"), Fixtures.library());

        assertNothing(STOOD_DOWN, "the Class-Path of the manifest of app.jar names lib/extra.jar, which is not on the"
                + " class path given", generate(holder, Fixtures.core()));
    }

    @Test
    void aManifestThatNamesAnEntryOfTheClassPath() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application()
                .manifest(Map.of("Class-Path", "lib/extra.jar missing.jar")));
        Path extra = Fixtures.jar(temporary.resolve("lib/extra.jar"), Fixtures.library());

        ServiceIndexGenerator.Result result = generate(holder, with(extra));

        assertEquals(GENERATED, result.status(), result.message());
    }

    @Test
    void aSignedHolder() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application()
                .put("META-INF/APP.SF", "Signature-Version: 1.0\n").put("META-INF/APP.RSA", new byte[0]));

        assertNothing(STOOD_DOWN, "app.jar is signed", generate(holder, Fixtures.core()));
    }

    @Test
    void aMissingEntry() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());

        assertNothing(FAILED, "the class path entry " + temporary.resolve("missing.jar") + " does not exist",
                generate(holder, with(temporary.resolve("missing.jar"))));
    }

    @Test
    void nothingToIndex() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.content()
                .type("fixture.Main", Fixtures.emptyClass("fixture.Main")));

        assertNothing(STOOD_DOWN, "the class path has no META-INF/services file and no META-INF/micronaut entry",
                generate(holder, Fixtures.core()));
    }

    @Test
    void anEmptyHolderDirectoryIsNotAnError() throws Exception {
        Path holder = Files.createDirectories(temporary.resolve("classes"));
        Path application = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = generate(holder, with(application));

        assertEquals(GENERATED, result.status(), result.message());
    }

    private static ClassTransform replacing(String name, MethodTypeDesc type) {
        return ClassTransform.dropping(element -> element instanceof MethodModel method
                        && method.methodName().equalsString(name))
                .andThen(ClassTransform.endHandler(builder -> builder.withMethodBody(name, type,
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn())));
    }

    private static List<Path> with(Path other) {
        List<Path> libraries = new ArrayList<>(List.of(other));
        libraries.addAll(Fixtures.core());
        return libraries;
    }

    private static ServiceIndexGenerator.Result generate(Path holder, List<Path> libraries) {
        ServiceIndexGenerator.Request.Builder request = ServiceIndexGenerator.Request.builder()
                .entry(holder, holder.getFileName().toString()).holder(holder);
        for (Path library : libraries) {
            request.entry(library, library.getFileName().toString());
        }
        return ServiceIndexGenerator.generate(request.build());
    }

    private static void assertNothing(ServiceIndexGenerator.Status status, String reason,
                                      ServiceIndexGenerator.Result result) {
        assertEquals(status, result.status(), result.message());
        assertTrue(result.message().contains(reason), result::message);
        assertEquals(Map.of(), result.entries());
        assertEquals(List.of(), List.copyOf(result.removals()));
    }
}
