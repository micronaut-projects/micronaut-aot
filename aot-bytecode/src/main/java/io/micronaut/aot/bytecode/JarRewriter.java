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

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Writes the copy of a jar whose classes a {@link ClassTransformPipeline} rewrites.
 *
 * <p>The copy holds every entry of the jar, in the order of its central directory, with its name, its comment, its
 * MS-DOS time, its extended timestamps and its compression method. A class of at most
 * {@link ClassTransformPipeline#MAX_CLASS_SIZE} is read whole, checked against its recorded size and CRC-32, and
 * handed to the pipeline; a class the pipeline rewrites gets the size and CRC-32 of its new bytes. Every other entry,
 * the manifest and a larger class included, is copied: a {@code STORED} entry as it is, checked against its CRC-32,
 * and a {@code DEFLATED} entry inflated and deflated again. So every entry keeps its content except the classes the
 * pipeline rewrote, and the compressed bytes of a {@code DEFLATED} entry depend on the JDK that writes them.</p>
 *
 * <p>A jar is left as it is, and no copy is written, when no class changes, when it is signed, because its
 * manifest holds a digest of each entry, and when it holds an entry name more than once, which
 * {@link ZipOutputStream} cannot write. The class depends only on {@code java.util.zip} and the pipeline.</p>
 */
final class JarRewriter {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final String META_INF = "META-INF/";

    private JarRewriter() {
    }

    /**
     * Rewrites the classes of one jar into a copy.
     *
     * @param source     the jar; it is neither closed nor modified
     * @param target     the copy to write; it is replaced if it exists, and deleted when nothing changes
     * @param pipeline   the pipeline
     * @param name       what notes and reports call the jar
     * @param thirdParty whether the caller declared the jar a third-party jar
     * @return whether the copy was written, what the pipeline did, and why the jar was left as it is, if it was
     * @throws IOException if the jar cannot be read, an entry does not match its recorded size or CRC-32, or the copy
     *                     cannot be written
     */
    static Outcome rewrite(Path source, Path target, ClassTransformPipeline pipeline, String name,
                           boolean thirdParty) throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile())) {
            List<ZipEntry> entries = new ArrayList<>(zip.size());
            Set<String> names = new HashSet<>();
            boolean signed = false;
            String repeated = null;
            for (Enumeration<? extends ZipEntry> all = zip.entries(); all.hasMoreElements(); ) {
                ZipEntry entry = all.nextElement();
                entries.add(entry);
                signed |= isSignatureFile(entry.getName());
                if (!names.add(entry.getName()) && repeated == null) {
                    repeated = entry.getName();
                }
            }
            ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer(name, signed,
                    thirdParty));
            if (!run.applies() || repeated != null) {
                passClasses(entries, run);
                return new Outcome(false, run.report(), keptReason(signed, repeated));
            }
            boolean changed = write(zip, entries, target, run);
            return new Outcome(changed, run.report(), null);
        } catch (IOException e) {
            throw new IOException("Cannot rewrite the classes of " + name + ": " + ClassTransformPipeline.describe(e),
                    e);
        }
    }

    /**
     * Counts every class of a jar that is left as it is, as passed.
     */
    private static void passClasses(List<ZipEntry> entries, ClassTransformPipeline.JarRun run) {
        for (ZipEntry entry : entries) {
            if (ClassTransformPipeline.isClass(entry.getName())) {
                run.pass();
            }
        }
    }

    /**
     * Why a jar is left as it is, or {@code null} when it is only that no step applies to it.
     */
    private static String keptReason(boolean signed, String repeated) {
        if (signed) {
            return "the jar is signed";
        }
        if (repeated != null) {
            return "the jar holds the entry " + repeated + " more than once";
        }
        return null;
    }

    /**
     * Whether an entry is a signature file of a signed jar: {@code META-INF/*.SF}, {@code *.DSA}, {@code *.RSA},
     * {@code *.EC} or {@code META-INF/SIG-*}, in any case.
     *
     * @param entryName the entry name
     * @return whether it is a signature file
     */
    static boolean isSignatureFile(String entryName) {
        String upper = entryName.toUpperCase(Locale.ROOT);
        if (!upper.startsWith(META_INF) || upper.length() == META_INF.length()) {
            return false;
        }
        String simple = upper.substring(META_INF.length());
        if (simple.indexOf('/') >= 0) {
            return false;
        }
        return simple.endsWith(".SF")
                || simple.endsWith(".DSA")
                || simple.endsWith(".RSA")
                || simple.endsWith(".EC")
                || simple.startsWith("SIG-");
    }

    /**
     * Writes the copy and keeps it only when a class changed. When the copy fails, it is deleted and the failure is
     * thrown, with a failure to delete it added as suppressed.
     *
     * @return whether a class changed, so the copy was kept
     */
    private static boolean write(ZipFile zip, List<ZipEntry> entries, Path target,
                                 ClassTransformPipeline.JarRun run) throws IOException {
        Files.createDirectories(target.getParent());
        boolean changed = false;
        try {
            try (OutputStream file = Files.newOutputStream(target);
                 ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(file, COPY_BUFFER_SIZE))) {
                String comment = zip.getComment();
                if (comment != null) {
                    out.setComment(comment);
                }
                CRC32 crc = new CRC32();
                for (ZipEntry entry : entries) {
                    changed |= writeEntry(zip, entry, out, run, crc);
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            try {
                delete(target);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        if (!changed) {
            delete(target);
        }
        return changed;
    }

    /**
     * Writes one entry of the copy: a class the pipeline reads goes through it, and every other entry is copied.
     *
     * @return whether the pipeline rewrote the entry
     */
    private static boolean writeEntry(ZipFile zip, ZipEntry entry, ZipOutputStream out,
                                      ClassTransformPipeline.JarRun run, CRC32 crc) throws IOException {
        ZipEntry copy = new ZipEntry(entry);
        if (ClassTransformPipeline.isClass(entry.getName())) {
            if (run.reads(entry.getSize())) {
                return writeClass(zip, entry, copy, out, run, crc);
            }
            run.pass();
        }
        put(out, copy);
        try (InputStream in = zip.getInputStream(entry)) {
            in.transferTo(out);
        }
        out.closeEntry();
        return false;
    }

    /**
     * Writes one class of the copy as the pipeline returns it, with the size and CRC-32 of its new bytes when it
     * changed.
     *
     * @return whether the pipeline rewrote the class
     */
    private static boolean writeClass(ZipFile zip, ZipEntry entry, ZipEntry copy, ZipOutputStream out,
                                      ClassTransformPipeline.JarRun run, CRC32 crc) throws IOException {
        byte[] original = read(zip, entry, crc);
        byte[] output = run.process(entry.getName(), original);
        boolean changed = output != original;
        if (changed) {
            crc.reset();
            crc.update(output, 0, output.length);
            copy.setSize(output.length);
            copy.setCrc(crc.getValue());
            if (copy.getMethod() == ZipEntry.STORED) {
                copy.setCompressedSize(output.length);
            }
        }
        put(out, copy);
        out.write(output);
        out.closeEntry();
        return changed;
    }

    /**
     * Deletes a copy, and its directory when nothing else is in it.
     */
    private static void delete(Path target) throws IOException {
        Files.deleteIfExists(target);
        try {
            Files.deleteIfExists(target.getParent());
        } catch (DirectoryNotEmptyException e) {
            // The caller's directory holds other files: only the copy is this method's.
        }
    }

    /**
     * Starts an entry of the copy. The compressed size of a {@code DEFLATED} entry is whatever the deflater writes,
     * so it is left open.
     */
    private static void put(ZipOutputStream out, ZipEntry copy) throws IOException {
        if (copy.getMethod() == ZipEntry.DEFLATED) {
            copy.setCompressedSize(-1);
        }
        out.putNextEntry(copy);
    }

    /**
     * Reads a class whole, checked against its recorded size and CRC-32.
     */
    private static byte[] read(ZipFile zip, ZipEntry entry, CRC32 crc) throws IOException {
        long size = entry.getSize();
        byte[] bytes;
        try (InputStream in = zip.getInputStream(entry)) {
            bytes = in.readNBytes((int) size + 1);
        }
        if (bytes.length != size) {
            throw new ZipException("The entry " + entry.getName() + " holds " + bytes.length
                    + " bytes, not the " + size + " its header records");
        }
        crc.reset();
        crc.update(bytes, 0, bytes.length);
        if (entry.getCrc() != -1 && crc.getValue() != entry.getCrc()) {
            throw new ZipException("The entry " + entry.getName() + " does not match its recorded CRC-32");
        }
        return bytes;
    }

    /**
     * What a rewrite did.
     *
     * @param written whether the copy was written, because a class changed
     * @param report  what the pipeline did to the jar's classes
     * @param kept    why no class of the jar was even read, or {@code null}
     */
    record Outcome(boolean written, ClassTransformPipeline.JarReport report, String kept) {
    }
}
