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

import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.constant.ConstantDescs.CD_Class;
import static java.lang.constant.ConstantDescs.CD_List;
import static java.lang.constant.ConstantDescs.CD_Map;
import static java.lang.constant.ConstantDescs.CD_Object;
import static java.lang.constant.ConstantDescs.CD_String;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

/**
 * Emits the classes of the loader that registers an index, with the ClassFile API:
 *
 * <pre>{@code
 * public final class ServiceIndexLoader implements StaticOptimizations.Loader<ServiceIndex> {
 *     public Object load() {
 *         Map micronaut = LinkedHashMap.newLinkedHashMap(types);
 *         Map standard = LinkedHashMap.newLinkedHashMap(types);
 *         ServiceIndexPart0.load(micronaut, standard); // ... and every other part that puts types
 *         return ServiceIndex.ofTrusted(ServiceIndexLoader.class.getClassLoader(), micronaut, standard,
 *                 ServiceIndexPartN.classPath()); // or null
 *     }
 * }
 * final class ServiceIndexNames extends AbstractSet<String> { ... } // an unhashed view of an array of names
 * final class ServiceIndexPartK {
 *     static void load(Map micronaut, Map standard) { load0(micronaut, standard); ... }
 *     private static void load0(Map micronaut, Map standard) {
 *         micronaut.put("type", new ServiceIndexNames(new String[] {"name", ...}));
 *         standard.put("type", Collections.unmodifiableList(Arrays.asList(new String[] {...})));
 *     }
 *     static void fill0(String[] names) { names[0] = "..."; ... } // the slices of a type too large for a method
 *     static List classPath() { ... }
 * }
 * }</pre>
 *
 * <p>The code is split before it is emitted, by budgets that are counted generously: the code of a method stays
 * under half the JVM's limit of 64 KB, and the constant pool of a class under half its limit of 65,535 entries,
 * whatever the number of types and names. The classes are of version 69, the version of every micronaut-core that has
 * the service index, and have no {@code invokedynamic}, no static initialiser and no static field.</p>
 */
final class LoaderEmitter {

    /** The code bytes a method may have: half the JVM's limit. */
    private static final int METHOD_CODE = 32_000;
    /** The constant pool entries a class may have: half the JVM's limit. */
    private static final int CLASS_POOL = 32_000;
    /** The constant pool entries of a class besides its names: its own types, the JDK's and micronaut-core's. */
    private static final int CLASS_POOL_BASE = 100;

    /** A name stored into an array: dup or aload_0, its index (at most 3), ldc_w and aastore. */
    private static final int NAME_CODE = 8;
    /** A name: its String and Utf8 entries, and an Integer entry for an index above 32,767. */
    private static final int NAME_POOL = 3;
    /** A type put into a map, besides its names: the map, the type, the wrapper, the array and the put. */
    private static final int TYPE_CODE = 32;
    /** A type: its String and Utf8 entries. */
    private static final int TYPE_POOL = 2;
    /** A call that fills a slice of an array: dup and invokestatic. */
    private static final int CALL_CODE = 4;
    /** A call: the Methodref, its NameAndType, the method's name, and the class with its name. */
    private static final int CALL_POOL = 5;
    /** A method: its name and descriptor, and the call that runs it. */
    private static final int METHOD_POOL = 2 + CALL_POOL;
    /** A class path entry stored into an array: index, new, dup, its name, its size, invokespecial and aastore. */
    private static final int ENTRY_CODE = 20;
    /** A class path entry: the String and Utf8 of its name, a Long (two entries) and an Integer index. */
    private static final int ENTRY_POOL = 5;
    /** The names of a type that are put inline; a larger type is filled by methods of this many names. */
    private static final int SLICE_NAMES = 3_000;
    /** The class path entries filled by one method. */
    private static final int SLICE_ENTRIES = 1_000;

    private static final String PACKAGE = ServiceIndexGenerator.GENERATED_PACKAGE + ".";
    private static final ClassDesc LOADER = ClassDesc.of(ServiceIndexGenerator.LOADER_CLASS);
    private static final ClassDesc NAMES = ClassDesc.of(PACKAGE + "ServiceIndexNames");
    /** The array field of {@code ServiceIndexNames}. */
    private static final String NAMES_FIELD = "names";
    private static final String PART = PACKAGE + "ServiceIndexPart";

    private static final ClassDesc STATIC_OPTIMIZATIONS_LOADER =
            ClassDesc.of("io.micronaut.core.optim.StaticOptimizations$Loader");
    private static final ClassDesc SERVICE_INDEX = ClassDesc.of("io.micronaut.core.io.service.ServiceIndex");
    private static final ClassDesc CLASS_PATH_ENTRY =
            ClassDesc.of("io.micronaut.core.io.service.ServiceIndex$ClassPathEntry");
    private static final String LOADER_SIGNATURE = "Ljava/lang/Object;Lio/micronaut/core/optim/StaticOptimizations$Loader"
            + "<Lio/micronaut/core/io/service/ServiceIndex;>;";

    private static final ClassDesc CD_CLASS_LOADER = ClassDesc.of("java.lang.ClassLoader");
    private static final ClassDesc CD_LINKED_HASH_MAP = ClassDesc.of("java.util.LinkedHashMap");
    private static final ClassDesc CD_ABSTRACT_SET = ClassDesc.of("java.util.AbstractSet");
    private static final ClassDesc CD_ARRAYS = ClassDesc.of("java.util.Arrays");
    private static final ClassDesc CD_COLLECTIONS = ClassDesc.of("java.util.Collections");
    private static final ClassDesc CD_ITERATOR = ClassDesc.of("java.util.Iterator");
    private static final ClassDesc STRINGS = CD_String.arrayType();
    private static final ClassDesc ENTRIES = CLASS_PATH_ENTRY.arrayType();

    private static final MethodTypeDesc MAPS = MethodTypeDesc.of(CD_void, CD_Map, CD_Map);
    private static final MethodTypeDesc OF_TRUSTED = MethodTypeDesc.of(SERVICE_INDEX, CD_CLASS_LOADER, CD_Map, CD_Map,
            CD_List);

    private LoaderEmitter() {
    }

    /**
     * Emits the loader of an index, and verifies it.
     *
     * @param index the index
     * @param core  the class loader of the application's class path, which resolves the JDK's and micronaut-core's
     *              types for the verification
     * @return the class files, by entry name, in a fixed order: the loader, the names, then the parts
     * @throws IllegalStateException    if a class does not verify
     * @throws IllegalArgumentException if a class is too large after all, which the ClassFile API checks
     */
    static Map<String, byte[]> emit(IndexData index, ClassLoader core) {
        Plan plan = Plan.of(index);
        Map<ClassDesc, ClassDesc> generated = new LinkedHashMap<>();
        generated.put(LOADER, CD_Object);
        generated.put(NAMES, CD_ABSTRACT_SET);
        for (Part part : plan.parts) {
            generated.put(part.type, CD_Object);
        }
        ClassHierarchyResolver jdkAndCore = ClassHierarchyResolver.ofResourceParsing(desc -> open(core, desc));
        ClassFile building = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.of(List.of(), generated).orElse(jdkAndCore).cached()));

        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put(entry(LOADER), building.build(LOADER, builder -> loader(builder, index, plan)));
        classes.put(entry(NAMES), building.build(NAMES, LoaderEmitter::names));
        for (Part part : plan.parts) {
            classes.put(entry(part.type), building.build(part.type, builder -> part(builder, part)));
        }

        // Verified against the generated classes first: an earlier index that the class path still holds has
        // classes of the same names.
        ClassFile verifying = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(
                ClassHierarchyResolver.ofResourceParsing(desc -> {
                    String name = entry(desc);
                    byte[] bytes = classes.get(name);
                    if (bytes != null) {
                        return new ByteArrayInputStream(bytes);
                    }
                    return name.startsWith(ServiceIndexGenerator.GENERATED_PATH) ? null : open(core, desc);
                }).cached()));
        for (Map.Entry<String, byte[]> generatedClass : classes.entrySet()) {
            var errors = verifying.verify(generatedClass.getValue());
            if (!errors.isEmpty()) {
                throw new IllegalStateException(generatedClass.getKey() + " does not verify: " + errors.get(0));
            }
        }
        return classes;
    }

    // ------------------------------------------------------------------ the loader

    private static void loader(ClassBuilder builder, IndexData index, Plan plan) {
        builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(CD_Object)
                .withInterfaceSymbols(STATIC_OPTIMIZATIONS_LOADER)
                .with(SignatureAttribute.of(ClassSignature.parseFrom(LOADER_SIGNATURE)))
                .withMethodBody(INIT_NAME, MTD_void, ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .invokespecial(CD_Object, INIT_NAME, MTD_void)
                        .return_())
                .withMethodBody("load", MethodTypeDesc.of(CD_Object), ClassFile.ACC_PUBLIC, code -> {
                    MethodTypeDesc newMap = MethodTypeDesc.of(CD_LINKED_HASH_MAP, CD_int);
                    code.loadConstant(index.micronautServices().size())
                            .invokestatic(CD_LINKED_HASH_MAP, "newLinkedHashMap", newMap)
                            .astore(1)
                            .loadConstant(index.standardServices().size())
                            .invokestatic(CD_LINKED_HASH_MAP, "newLinkedHashMap", newMap)
                            .astore(2);
                    for (Part part : plan.parts) {
                        if (!part.loads.isEmpty()) {
                            code.aload(1).aload(2).invokestatic(part.type, "load", MAPS);
                        }
                    }
                    code.loadConstant(LOADER)
                            .invokevirtual(CD_Class, "getClassLoader", MethodTypeDesc.of(CD_CLASS_LOADER))
                            .aload(1)
                            .aload(2);
                    if (plan.classPathPart != null) {
                        code.invokestatic(plan.classPathPart.type, "classPath", MethodTypeDesc.of(CD_List));
                    } else {
                        code.aconst_null();
                    }
                    code.invokestatic(SERVICE_INDEX, "ofTrusted", OF_TRUSTED)
                            .areturn();
                });
    }

    // ------------------------------------------------------------------ the set of names

    private static void names(ClassBuilder builder) {
        MethodTypeDesc asList = MethodTypeDesc.of(CD_List, CD_Object.arrayType());
        builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(CD_ABSTRACT_SET)
                .withField(NAMES_FIELD, STRINGS, ClassFile.ACC_PRIVATE | ClassFile.ACC_FINAL)
                .withMethodBody(INIT_NAME, MethodTypeDesc.of(CD_void, STRINGS), 0, code -> code
                        .aload(0)
                        .invokespecial(CD_ABSTRACT_SET, INIT_NAME, MTD_void)
                        .aload(0)
                        .aload(1)
                        .putfield(NAMES, NAMES_FIELD, STRINGS)
                        .return_())
                .withMethodBody("iterator", MethodTypeDesc.of(CD_ITERATOR), ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .getfield(NAMES, NAMES_FIELD, STRINGS)
                        .invokestatic(CD_ARRAYS, "asList", asList)
                        .invokeinterface(CD_List, "iterator", MethodTypeDesc.of(CD_ITERATOR))
                        .areturn())
                .withMethodBody("size", MethodTypeDesc.of(CD_int), ClassFile.ACC_PUBLIC, code -> code
                        .aload(0)
                        .getfield(NAMES, NAMES_FIELD, STRINGS)
                        .arraylength()
                        .ireturn());
    }

    // ------------------------------------------------------------------ the parts

    private static void part(ClassBuilder builder, Part part) {
        builder.withVersion(ClassFile.JAVA_25_VERSION, 0)
                .withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withSuperclass(CD_Object);
        if (!part.loads.isEmpty()) {
            builder.withMethodBody("load", MAPS, ClassFile.ACC_STATIC, code -> loads(code, part));
        }
        for (int i = 0; i < part.loads.size(); i++) {
            List<Put> puts = part.loads.get(i);
            builder.withMethodBody("load" + i, MAPS, ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC, code -> {
                for (Put put : puts) {
                    put(code, put);
                }
                code.return_();
            });
        }
        for (Fill fill : part.fills) {
            builder.withMethodBody(fill.method, MethodTypeDesc.of(CD_void, fill.arrayType()), ClassFile.ACC_STATIC,
                    code -> fill(code, fill));
        }
        if (part.classPath != null) {
            List<Fill> fills = part.classPath;
            builder.withMethodBody("classPath", MethodTypeDesc.of(CD_List), ClassFile.ACC_STATIC,
                    code -> classPath(code, fills));
        }
    }

    /** {@code load(micronaut, standard)}: calls each {@code loadN} of the part. */
    private static void loads(CodeBuilder code, Part part) {
        for (int i = 0; i < part.loads.size(); i++) {
            code.aload(0).aload(1).invokestatic(part.type, "load" + i, MAPS);
        }
        code.return_();
    }

    /** {@code fillN(array)}: stores a slice of names or class path entries into the array. */
    private static void fill(CodeBuilder code, Fill fill) {
        List<?> elements = fill.elements;
        for (int i = 0; i < elements.size(); i++) {
            code.aload(0).loadConstant(fill.from + i);
            if (elements.get(i) instanceof IndexData.Entry entry) {
                entry(code, entry);
            } else {
                code.loadConstant((String) elements.get(i));
            }
            code.aastore();
        }
        code.return_();
    }

    /** {@code classPath()}: an array of the class path entries, filled by the slices, as a list. */
    private static void classPath(CodeBuilder code, List<Fill> fills) {
        code.loadConstant(fills.stream().mapToInt(fill -> fill.elements.size()).sum())
                .anewarray(CLASS_PATH_ENTRY);
        for (Fill fill : fills) {
            code.dup().invokestatic(fill.owner, fill.method, MethodTypeDesc.of(CD_void, ENTRIES));
        }
        code.invokestatic(CD_List, "of", MethodTypeDesc.of(CD_List, CD_Object.arrayType()), true)
                .areturn();
    }

    /** {@code map.put(type, wrapped names)}: the micronaut map is the first parameter, the standard one the second. */
    private static void put(CodeBuilder code, Put put) {
        code.aload(put.micronaut ? 0 : 1).loadConstant(put.type);
        if (put.micronaut) {
            code.new_(NAMES).dup();
        }
        code.loadConstant(put.names.size()).anewarray(CD_String);
        if (put.fills == null) {
            for (int i = 0; i < put.names.size(); i++) {
                code.dup().loadConstant(i).loadConstant(put.names.get(i)).aastore();
            }
        } else {
            for (Fill fill : put.fills) {
                code.dup().invokestatic(fill.owner, fill.method, MethodTypeDesc.of(CD_void, STRINGS));
            }
        }
        if (put.micronaut) {
            code.invokespecial(NAMES, INIT_NAME, MethodTypeDesc.of(CD_void, STRINGS));
        } else {
            code.invokestatic(CD_ARRAYS, "asList", MethodTypeDesc.of(CD_List, CD_Object.arrayType()))
                    .invokestatic(CD_COLLECTIONS, "unmodifiableList", MethodTypeDesc.of(CD_List, CD_List));
        }
        code.invokeinterface(CD_Map, "put", MethodTypeDesc.of(CD_Object, CD_Object, CD_Object))
                .pop();
    }

    /** {@code new ClassPathEntry(name, size)}. */
    private static void entry(CodeBuilder code, IndexData.Entry entry) {
        code.new_(CLASS_PATH_ENTRY)
                .dup()
                .loadConstant(entry.name())
                .loadConstant(entry.size())
                .invokespecial(CLASS_PATH_ENTRY, INIT_NAME, MethodTypeDesc.of(CD_void, CD_String, CD_long));
    }

    private static String entry(ClassDesc type) {
        String descriptor = type.descriptorString();
        return descriptor.substring(1, descriptor.length() - 1) + ".class";
    }

    /** A class file of the JDK or of the class path, for the ClassFile API; not from the JVM's cache of jar files. */
    private static @Nullable InputStream open(ClassLoader loader, ClassDesc type) {
        URL url = loader.getResource(entry(type));
        if (url == null) {
            return null;
        }
        try {
            URLConnection connection = url.openConnection();
            connection.setUseCaches(false);
            return connection.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ the split

    /**
     * A method that fills a slice of an array.
     *
     * @param owner    the part that has the method
     * @param method   its name
     * @param elements the names, or the {@link IndexData.Entry class path entries}
     * @param from     the index in the array of the first one
     */
    private record Fill(ClassDesc owner, String method, List<?> elements, int from) {

        ClassDesc arrayType() {
            return !elements.isEmpty() && elements.get(0) instanceof IndexData.Entry ? ENTRIES : STRINGS;
        }
    }

    /**
     * A type put into one of the maps.
     *
     * @param micronaut whether it goes into the {@code META-INF/micronaut} map
     * @param type      the service type
     * @param names     its names
     * @param fills     the methods that fill its array, or {@code null} if its names are put inline
     */
    private record Put(boolean micronaut, String type, List<String> names, @Nullable List<Fill> fills) {
    }

    /** A generated class of static methods. */
    private static final class Part {
        final ClassDesc type;
        /** The methods that put types into the maps, in order: each is a list of puts. */
        final List<List<Put>> loads = new ArrayList<>();
        final List<Fill> fills = new ArrayList<>();
        @Nullable List<Fill> classPath;
        int pool = CLASS_POOL_BASE;

        Part(int index) {
            type = ClassDesc.of(PART + index);
        }
    }

    /**
     * How the index is split over methods and classes. The puts are appended to the last method, which is always in
     * the last class that has one, so the loader runs them in the order of the index.
     */
    private static final class Plan {
        final List<Part> parts = new ArrayList<>();
        @Nullable Part classPathPart;
        private Part last;
        private @Nullable List<Put> load;
        private @Nullable Part loadPart;
        private int loadCode;

        private Plan() {
            last = newPart();
        }

        static Plan of(IndexData index) {
            Plan plan = new Plan();
            index.micronautServices().forEach((type, names) -> plan.put(true, type, names));
            index.standardServices().forEach((type, names) -> plan.put(false, type, names));
            List<IndexData.Entry> classPath = index.classPath();
            if (classPath != null) {
                List<Fill> fills = plan.fills(classPath, SLICE_ENTRIES, ENTRY_POOL);
                plan.reserve(fills.size() * CALL_POOL + METHOD_POOL);
                plan.last.classPath = fills;
                plan.classPathPart = plan.last;
            }
            return plan;
        }

        private void put(boolean micronaut, String type, List<String> names) {
            List<Fill> fills = null;
            int code;
            int pool;
            if (names.size() > SLICE_NAMES) {
                fills = fills(names, SLICE_NAMES, NAME_POOL);
                code = TYPE_CODE + fills.size() * CALL_CODE;
                pool = TYPE_POOL + fills.size() * CALL_POOL;
            } else {
                code = TYPE_CODE + names.size() * NAME_CODE;
                pool = TYPE_POOL + names.size() * NAME_POOL;
            }
            if (load == null || loadPart == null || loadCode + code > METHOD_CODE || loadPart.pool + pool > CLASS_POOL) {
                reserve(pool + METHOD_POOL);
                load = new ArrayList<>();
                last.loads.add(load);
                last.pool += METHOD_POOL;
                loadPart = last;
                loadCode = 0;
            }
            load.add(new Put(micronaut, type, names, fills));
            loadCode += code;
            loadPart.pool += pool;
        }

        private List<Fill> fills(List<?> elements, int slice, int elementPool) {
            List<Fill> fills = new ArrayList<>();
            for (int from = 0; from < elements.size(); from += slice) {
                List<?> elementsOfSlice = elements.subList(from, Math.min(elements.size(), from + slice));
                int pool = elementsOfSlice.size() * elementPool + METHOD_POOL;
                reserve(pool);
                Fill fill = new Fill(last.type, "fill" + last.fills.size(), elementsOfSlice, from);
                last.fills.add(fill);
                last.pool += pool;
                fills.add(fill);
            }
            return fills;
        }

        /** Starts a class unless the last one has room for this many more constant pool entries. */
        private void reserve(int pool) {
            if (last.pool + pool > CLASS_POOL) {
                last = newPart();
            }
        }

        private Part newPart() {
            Part part = new Part(parts.size());
            parts.add(part);
            return part;
        }
    }
}
