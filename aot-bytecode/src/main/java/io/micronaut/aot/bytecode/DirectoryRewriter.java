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

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Writes the copy of a directory of classes, such as an application's compiled output, whose classes a
 * {@link ClassTransformPipeline} rewrites.
 *
 * <p>The directory is planned first, as a jar is. The copy then holds every file and every directory of the
 * original, in the same place, with its modification time: a class of a planned nest is written as the pipeline
 * accepted it, and the classes generated for it are written next to it, with its modification time; any other class
 * goes through the pipeline, and every other file is copied as it is. A file of the directory is read and written by
 * its path, so a symbolic link would be followed on one side and not on the other: a directory that holds one is left
 * as it is, as a jar that holds an entry name twice is.</p>
 *
 * <p>No copy is written when no class changes. The class depends only on {@code java.nio.file} and the
 * pipeline.</p>
 */
final class DirectoryRewriter {

    private DirectoryRewriter() {
    }

    /**
     * Rewrites the classes of one directory into a copy.
     *
     * @param source   the directory; it is not modified
     * @param target   the copy to write; it is replaced if it exists, and not written when nothing changes
     * @param pipeline the pipeline
     * @param name     what notes and reports call the directory
     * @param index    the directory's position in the class path
     * @return whether the copy was written, what the pipeline did, and why the directory was left as it is, if it was
     * @throws IOException if the directory cannot be read or the copy cannot be written
     */
    static JarRewriter.Outcome rewrite(Path source, Path target, ClassTransformPipeline pipeline, String name,
                                       int index) throws IOException {
        try {
            List<Path> paths;
            try (Stream<Path> walk = Files.walk(source)) {
                paths = walk.sorted().toList();
            }
            String link = null;
            Map<String, Path> files = new HashMap<>();
            List<ClassTransformPipeline.ClassEntry> classes = new ArrayList<>();
            for (Path path : paths) {
                String entryName = entryName(source, path);
                if (Files.isSymbolicLink(path)) {
                    link = link == null ? entryName : link;
                } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && ClassTransformPipeline.isClass(entryName)) {
                    files.put(entryName, path);
                    classes.add(new ClassTransformPipeline.ClassEntry(entryName, Files.size(path)));
                }
            }
            ClassTransformPipeline.JarRun run = pipeline.start(new ClassTransformPipeline.Layer(name, index, false,
                    false));
            if (run.plans()) {
                run.plan(new DirectoryClasses(files, classes, link != null));
            }
            if (link != null || !run.rewrites()) {
                for (ClassTransformPipeline.ClassEntry entry : classes) {
                    run.pass(entry.name());
                }
                return new JarRewriter.Outcome(false, run.report(),
                        link == null ? null : "the directory holds the symbolic link " + link);
            }
            boolean changed = write(source, paths, target, run);
            return new JarRewriter.Outcome(changed, run.report(), null);
        } catch (IOException e) {
            throw new IOException("Cannot rewrite the classes of " + name + ": " + ClassTransformPipeline.describe(e),
                    e);
        }
    }

    /**
     * Writes the copy and keeps it only when a class changed. When the copy fails, it is deleted and the failure is
     * thrown, with a failure to delete it added as suppressed.
     *
     * @return whether a class changed, so the copy was kept
     */
    private static boolean write(Path source, List<Path> paths, Path target, ClassTransformPipeline.JarRun run)
            throws IOException {
        delete(target);
        boolean changed = false;
        try {
            List<Path> directories = new ArrayList<>();
            for (Path path : paths) {
                Path copy = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(copy);
                    directories.add(path);
                } else {
                    changed |= writeFile(source, path, copy, run);
                }
            }
            // Writing a file changes its directory's time, so the times of the directories are set last, the deepest
            // first.
            for (int i = directories.size() - 1; i >= 0; i--) {
                Path directory = directories.get(i);
                Files.setLastModifiedTime(target.resolve(source.relativize(directory).toString()),
                        Files.getLastModifiedTime(directory, LinkOption.NOFOLLOW_LINKS));
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
            // A second guard: a directory is copied only when a nest was accepted, which always changes a class.
            delete(target);
        }
        return changed;
    }

    /**
     * Writes one file of the copy: a class of a planned nest as the pipeline accepted it, followed by its generated
     * classes, a class the pipeline reads through it, and every other file as it is.
     *
     * @return whether the pipeline rewrote the file or generated classes for it
     */
    private static boolean writeFile(Path source, Path file, Path copy, ClassTransformPipeline.JarRun run)
            throws IOException {
        String entryName = entryName(source, file);
        FileTime time = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS);
        if (ClassTransformPipeline.isClass(entryName)) {
            ClassTransformPipeline.Planned planned = run.planned(entryName);
            if (planned != null) {
                write(copy, planned.bytes(), time);
                for (ClassTransformPipeline.Generated generated : planned.generated()) {
                    write(copy.resolveSibling(generated.name().substring(generated.name().lastIndexOf('/') + 1)),
                            generated.bytes(), time);
                }
                return planned.rewritten() || !planned.generated().isEmpty();
            }
            long size = Files.size(file);
            if (run.reads(size)) {
                byte[] original = Files.readAllBytes(file);
                byte[] output = run.process(entryName, original);
                write(copy, output, time);
                return output != original;
            }
            run.pass(entryName);
        }
        Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                LinkOption.NOFOLLOW_LINKS);
        Files.setLastModifiedTime(copy, time);
        return false;
    }

    private static void write(Path file, byte[] bytes, FileTime time) throws IOException {
        Files.write(file, bytes);
        Files.setLastModifiedTime(file, time);
    }

    /**
     * Deletes a copy, and everything in it, without following links, and then its directory when nothing else is in
     * it, as {@link JarRewriter} does.
     */
    private static void delete(Path target) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(target);
        }
        try {
            Files.deleteIfExists(target.getParent());
        } catch (DirectoryNotEmptyException e) {
            // The caller's directory holds other files: only the copy is this method's.
        }
    }

    private static void deleteTree(Path target) throws IOException {
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * The name of a file below the directory, with {@code /} as the separator, as a jar would name its entry.
     */
    private static String entryName(Path source, Path path) {
        return source.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    /**
     * The classes of a directory, as the planning step reads them.
     */
    private static final class DirectoryClasses implements ClassTransformPipeline.JarClasses {

        private final Map<String, Path> files;
        private final List<ClassTransformPipeline.ClassEntry> classes;
        private final boolean kept;

        private DirectoryClasses(Map<String, Path> files, List<ClassTransformPipeline.ClassEntry> classes,
                                 boolean kept) {
            this.files = files;
            this.classes = classes;
            this.kept = kept;
        }

        @Override
        public List<ClassTransformPipeline.ClassEntry> classes() {
            return classes;
        }

        @Override
        public long size(String entryName) {
            Path file = files.get(entryName);
            if (file == null) {
                return -1;
            }
            try {
                return Files.size(file);
            } catch (IOException e) {
                return -1;
            }
        }

        @Override
        public boolean multiRelease() {
            // No manifest decides for a directory.
            return false;
        }

        @Override
        public boolean kept() {
            return kept;
        }

        @Override
        public byte[] read(String entryName) throws IOException {
            Path file = files.get(entryName);
            if (file == null) {
                throw new IOException("No class file " + entryName);
            }
            return Files.readAllBytes(file);
        }
    }
}
