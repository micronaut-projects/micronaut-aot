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

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassReader;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CustomAttribute;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles and packages the class fixtures of the class transform tests: real {@code javac} output, with
 * local-variable tables when compiled with {@code -g}.
 */
final class ClassFixtures {

    /** A fixed timestamp for fixture jars, so a rebuilt fixture changes nothing. */
    private static final long FIXTURE_TIME = 1_000_000_000_000L;

    private ClassFixtures() {
    }

    /**
     * Compiles sources into a directory.
     *
     * @param sources   where the sources are written
     * @param classes   where the classes go
     * @param options   the compiler options, such as {@code -g} and {@code --release 17}
     * @param files     the sources, keyed by relative path
     * @return {@code classes}
     * @throws IOException if the fixture does not compile
     */
    static Path compile(Path sources, Path classes, List<String> options, Map<String, String> files)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "this JDK has no java compiler");
        Files.createDirectories(classes);
        List<String> arguments = new ArrayList<>(options);
        // javac warns that release 8 is deprecated; the fixture wants exactly that class file version.
        arguments.addAll(List.of("-nowarn", "-Xlint:-options", "-d", classes.toString()));
        for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
            Path source = sources.resolve(file.getKey());
            Files.createDirectories(source.getParent());
            Files.writeString(source, file.getValue());
            arguments.add(source.toString());
        }
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Could not compile the fixture " + files.keySet());
        }
        return classes;
    }

    /**
     * Every class file below a directory, keyed by entry name, in name order.
     *
     * @param classes the directory
     * @return the classes
     * @throws IOException if one cannot be read
     */
    static Map<String, byte[]> classes(Path classes) throws IOException {
        Map<String, byte[]> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                entries.put(classes.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return entries;
    }

    /**
     * Writes a jar with a plain manifest and deflated entries, in the given order.
     *
     * @param file    the jar
     * @param entries its entries
     * @return {@code file}
     * @throws IOException if it cannot be written
     */
    static Path jar(Path file, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return jar(file, manifest, entries);
    }

    /**
     * Writes a jar with a manifest and deflated entries, in the given order.
     *
     * @param file     the jar
     * @param manifest its manifest
     * @param entries  its entries
     * @return {@code file}
     * @throws IOException if it cannot be written
     */
    static Path jar(Path file, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        return jar(file, manifest, entries, Set.of());
    }

    /**
     * Writes a jar with a manifest, in the given order, with some entries {@code STORED} and the others deflated.
     * Each entry gets its own time, a minute after the one before it, so that a copy that loses the times shows.
     *
     * @param file     the jar
     * @param manifest its manifest
     * @param entries  its entries
     * @param stored   the names of the entries that are stored
     * @return {@code file}
     * @throws IOException if it cannot be written
     */
    static Path jar(Path file, Manifest manifest, Map<String, byte[]> entries, Set<String> stored)
            throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file);
             JarOutputStream jar = new JarOutputStream(out, manifest)) {
            long time = FIXTURE_TIME;
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry record = new ZipEntry(entry.getKey());
                time += 60_000;
                record.setTime(time);
                if (stored.contains(entry.getKey())) {
                    CRC32 crc = new CRC32();
                    crc.update(entry.getValue());
                    record.setMethod(ZipEntry.STORED);
                    record.setSize(entry.getValue().length);
                    record.setCompressedSize(entry.getValue().length);
                    record.setCrc(crc.getValue());
                }
                jar.putNextEntry(record);
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    /**
     * Adds a class-level attribute the JDK does not know, whose two-byte payload is a constant pool index.
     *
     * @param bytes the class
     * @return the class with the attribute
     */
    static byte[] withUnknownAttribute(byte[] bytes) {
        ClassFile context = ClassFile.of();
        return context.transformClass(context.parse(bytes), ClassTransform.endHandler(builder ->
                builder.with(new VendorAttribute())));
    }

    /**
     * Points the superclass index of a class at a constant that is not a class: its first {@code Utf8} entry.
     * The class still parses, and still declares its own name; only reading its superclass fails.
     *
     * @param bytes the class
     * @return a copy with the corrupt index
     */
    static byte[] withCorruptSuperclass(byte[] bytes) {
        return withHeaderIndex(bytes, 4);
    }

    /**
     * Points the first interface index of a class that implements one at a constant that is not a class: its
     * first {@code Utf8} entry. The class still parses; only reading its interfaces fails.
     *
     * @param bytes the class, which must implement an interface
     * @return a copy with the corrupt index
     */
    static byte[] withCorruptInterface(byte[] bytes) {
        return withHeaderIndex(bytes, 8);
    }

    /**
     * Overwrites one two-byte index of the class header, given by its offset from {@code access_flags}, with
     * the index of the first {@code Utf8} constant.
     */
    private static byte[] withHeaderIndex(byte[] bytes, int offset) {
        int count = u2(bytes, 8);
        int position = 10;
        int utf8 = 0;
        for (int index = 1; index < count; index++) {
            int tag = bytes[position] & 0xFF;
            switch (tag) {
                case 1 -> {
                    utf8 = utf8 == 0 ? index : utf8;
                    position += 3 + u2(bytes, position + 1);
                }
                case 3, 4, 9, 10, 11, 12, 17, 18 -> position += 5;
                case 5, 6 -> {
                    position += 9;
                    index++;
                }
                case 7, 8, 16, 19, 20 -> position += 3;
                case 15 -> position += 4;
                default -> throw new IllegalArgumentException("Unknown constant pool tag " + tag);
            }
        }
        if (offset == 8 && u2(bytes, position + 6) == 0) {
            throw new IllegalArgumentException("The class implements no interface");
        }
        byte[] corrupt = bytes.clone();
        corrupt[position + offset] = (byte) (utf8 >>> 8);
        corrupt[position + offset + 1] = (byte) utf8;
        return corrupt;
    }

    private static int u2(byte[] bytes, int position) {
        return (bytes[position] & 0xFF) << 8 | bytes[position + 1] & 0xFF;
    }

    /** The name of the attribute {@link #withUnknownAttribute(byte[])} adds. */
    static final String UNKNOWN_ATTRIBUTE = "ExampleVendorAttribute";

    /** The constant its payload points at. */
    static final String UNKNOWN_ATTRIBUTE_TARGET = "a constant only the vendor attribute names";

    /**
     * An attribute that the ClassFile API writes and, lacking a mapper when it reads it back, reports as
     * unknown.
     */
    private static final class VendorAttribute extends CustomAttribute<VendorAttribute> {

        private static final AttributeMapper<VendorAttribute> MAPPER = new AttributeMapper<>() {
            @Override
            public String name() {
                return UNKNOWN_ATTRIBUTE;
            }

            @Override
            public VendorAttribute readAttribute(AttributedElement enclosing, ClassReader reader, int position) {
                throw new UnsupportedOperationException("the fixture only writes it");
            }

            @Override
            public void writeAttribute(BufWriter buffer, VendorAttribute attribute) {
                buffer.writeIndex(buffer.constantPool().utf8Entry(UNKNOWN_ATTRIBUTE));
                buffer.writeInt(2);
                buffer.writeIndex(buffer.constantPool().utf8Entry(UNKNOWN_ATTRIBUTE_TARGET));
            }

            @Override
            public AttributeStability stability() {
                return AttributeStability.CP_REFS;
            }
        };

        private VendorAttribute() {
            super(MAPPER);
        }
    }

    /**
     * The sources, keyed by relative path, of one small class.
     *
     * @param name   the class's binary name
     * @param source its source
     * @return the map
     */
    static Map<String, String> source(String name, String source) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(name.replace('.', '/') + ".java", source);
        return files;
    }

    /**
     * UTF-8 bytes.
     *
     * @param text the text
     * @return its bytes
     */
    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
