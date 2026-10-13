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

import io.micronaut.aot.serviceindex.Fixtures.Run;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.classfile.AccessFlags;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.micronaut.aot.serviceindex.Fixtures.BEAN_DEFINITIONS;
import static io.micronaut.aot.serviceindex.ServiceIndexGenerator.LOADER_SERVICE;
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

    private static final ClassDesc INDEX = ClassDesc.of("io.micronaut.core.io.service.ServiceIndex");

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
        ClassTransform transform = switch (api) {
            case BUILD_TAKES_A_SET -> replacing("build", MethodTypeDesc.of(INDEX, CLASS_LOADER,
                    ConstantDescs.CD_Set));
            case OF_TRUSTED_WITHOUT_A_CLASS_PATH -> replacing("ofTrusted", MethodTypeDesc.of(INDEX,
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

        ServiceIndexGenerator.Result result = generate(holder, transformedCore(api.type, transform));

        assertNothing(STOOD_DOWN, "has another service index API than the one the generated code calls", result);
        assertTrue(result.message().contains(api.member), result::message);
    }

    /** micronaut-core fails while it builds the index: the call returns a failure, and throws nothing at the build. */
    @ParameterizedTest
    @ValueSource(strings = {"java.lang.IllegalStateException", "java.lang.AssertionError"})
    void aMicronautCoreThatFails(String failure) throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());
        ClassDesc thrown = ClassDesc.of(failure);

        ServiceIndexGenerator.Result result = generate(holder, transformedCore("ServiceIndexBuilder",
                ClassTransform.dropping(element -> element instanceof MethodModel method
                                && method.methodName().equalsString("build"))
                        .andThen(ClassTransform.endHandler(builder -> builder.withMethodBody("build",
                                MethodTypeDesc.of(INDEX, CLASS_LOADER, ClassDesc.of("java.util.Collection")),
                                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.new_(thrown).dup()
                                        .invokespecial(thrown, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                        .athrow())))));

        assertNothing(FAILED, "of an unexpected error (" + failure, result);
    }

    /** The generated loader is run in micronaut-core, and an index that differs from the builder's is not returned. */
    @Test
    void aLoaderThatDoesNotRebuildTheIndex() throws Exception {
        Path holder = Fixtures.jar(temporary.resolve("app.jar"), Fixtures.application());

        ServiceIndexGenerator.Result result = generate(holder, transformedCore("ServiceIndex",
                atStartOf("ofTrusted", code -> code.aload(1)
                        .invokeinterface(ConstantDescs.CD_Map, "clear", ConstantDescs.MTD_void))));

        assertNothing(FAILED, "the generated loader does not rebuild micronaut-core's index", result);
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

    /**
     * An earlier index in the holder that a call does not replace is removed, and the other loaders are kept. Here,
     * its loader fails the start of the application, whose micronaut-core has no service index.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEarlierIndexIsRemovedWhenStandingDown(boolean otherLoader) throws Exception {
        Fixtures.Content application = Fixtures.application();
        // micronaut-core 5.2's own scan fails the probe's lookups of META-INF/services, which pass no condition.
        List<String> lookups = new ArrayList<>(List.of("micronaut:" + BEAN_DEFINITIONS));
        if (otherLoader) {
            application.put(LOADER_SERVICE, "fixture.OtherLoader\n")
                    .type("fixture.OtherLoader", Fixtures.loaderClass("fixture.OtherLoader", "fixture.One"))
                    .type("fixture.One", Fixtures.emptyClass("fixture.One"));
            lookups.add("optimization:fixture.One");
        }
        Path jar = Fixtures.fatJar(temporary.resolve("app.jar"), application);
        ServiceIndexGenerator.Result earlier = Fixtures.generateFor(jar);
        assertEquals(GENERATED, earlier.status(), earlier.message());
        // The application, packaged again on a micronaut-core without the builder into a jar that still holds the
        // earlier index.
        List<Path> libraries = new ArrayList<>(Fixtures.libraries());
        libraries.set(libraries.indexOf(Fixtures.coreJar()), Fixtures.legacyCore());
        Fixtures.Content repackaged = Fixtures.contentOf(application.entries);
        repackaged.entries.putAll(earlier.entries());
        Fixtures.fatJar(jar, repackaged, libraries);
        Run stale = Fixtures.javaJar(jar, List.of(), lookups);
        assertTrue(stale.exit() != 0 && stale.err().contains("NoClassDefFoundError"), stale::toString);

        ServiceIndexGenerator.Result result = Fixtures.generateFor(jar);

        assertEquals(STOOD_DOWN, result.status(), result.message());
        assertTrue(result.message().contains("has no ServiceIndexBuilder"), result::message);
        SortedSet<String> classes = new TreeSet<>(earlier.entries().keySet());
        classes.remove(LOADER_SERVICE);
        if (otherLoader) {
            assertEquals(classes, result.removals());
            assertEquals(Set.of(LOADER_SERVICE), result.entries().keySet());
            assertEquals("fixture.OtherLoader\n",
                    new String(result.entries().get(LOADER_SERVICE), StandardCharsets.UTF_8));
        } else {
            classes.add(LOADER_SERVICE);
            assertEquals(classes, result.removals());
            assertEquals(Map.of(), result.entries());
        }
        Fixtures.write(jar, result);
        Run run = Fixtures.javaJar(jar, List.of(), lookups);
        assertEquals("micronaut:" + BEAN_DEFINITIONS + " [fixture.$Hello$Definition$Reference,"
                + " fixture.$Hi$Definition$Reference]\n" + (otherLoader ? "optimization:fixture.One [true]\n" : ""),
                run.lookups());
    }

    private static ClassTransform replacing(String name, MethodTypeDesc type) {
        return ClassTransform.dropping(element -> element instanceof MethodModel method
                        && method.methodName().equalsString(name))
                .andThen(ClassTransform.endHandler(builder -> builder.withMethodBody(name, type,
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, code -> code.aconst_null().areturn())));
    }

    /** micronaut-core's class path, with one class of micronaut-core transformed. */
    private List<Path> transformedCore(String type, ClassTransform transform) throws Exception {
        Fixtures.Content core = Fixtures.read(Fixtures.coreJar());
        String entry = "io/micronaut/core/io/service/" + type + ".class";
        ClassFile classFile = ClassFile.of();
        core.put(entry, classFile.transformClass(classFile.parse(core.entries.get(entry)), transform));
        List<Path> libraries = new ArrayList<>(Fixtures.core());
        libraries.set(libraries.indexOf(Fixtures.coreJar()), Fixtures.jar(temporary.resolve("micronaut-core.jar"), core));
        return libraries;
    }

    private static ClassTransform atStartOf(String method, Consumer<CodeBuilder> start) {
        return ClassTransform.transformingMethodBodies(model -> model.methodName().equalsString(method),
                new CodeTransform() {
                    @Override
                    public void atStart(CodeBuilder code) {
                        start.accept(code);
                    }

                    @Override
                    public void accept(CodeBuilder code, CodeElement element) {
                        code.with(element);
                    }
                });
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
