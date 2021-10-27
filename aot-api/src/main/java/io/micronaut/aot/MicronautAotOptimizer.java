/*
 * Copyright 2017-2021 original authors
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
package io.micronaut.aot;

import com.squareup.javapoet.JavaFile;
import io.micronaut.aot.core.AOTSourceGenerator;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.Runtime;
import io.micronaut.aot.core.SourceGenerationContext;
import io.micronaut.aot.core.config.DefaultConfiguration;
import io.micronaut.aot.core.config.SourceGeneratorLoader;
import io.micronaut.aot.core.context.ApplicationContextAnalyzer;
import io.micronaut.aot.core.sourcegen.ApplicationContextCustomizerGenerator;
import io.micronaut.aot.core.sourcegen.DefaultSourceGenerationContext;
import io.micronaut.aot.internal.StreamHelper;
import io.micronaut.core.annotation.Experimental;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The Micronaut AOT optimizer is the main entry point for code
 * generation at build time. Its role is to generate a bunch of
 * source code for various optimizations which can be computed
 * at build time.
 *
 * Typically, generated code will involve the generation of an
 * "optimized" entry point for the application, which delegates
 * to the main entry point, but also performs some static
 * initialization by making calls to the
 * {@link io.micronaut.core.optim.StaticOptimizations} class.
 *
 * The Micronaut AOT optimizer is experimental and won't do
 * anything by its own: it must be integrated in some form, for
 * example via a build plugin, which in turn will make the generated
 * classes visible to the user. For example, the build tool may
 * call this class to generate the optimization code, and in addition
 * create an optimized jar, an optimized native binary or even a
 * full distribution.
 *
 * The optimizer works by passing in the whole application runtime
 * classpath and a set of configuration options. It then analyzes
 * the classpath, for example to identify the services to be loaded,
 * or to provide some alternative implementations to existing
 * classes.
 */
@Experimental
public final class MicronautAotOptimizer implements ConfigKeys {
    public static final String OUTPUT_RESOURCES_FILE_NAME = "resource-filter.txt";

    private static final Logger LOGGER = LoggerFactory.getLogger(MicronautAotOptimizer.class);

    private final List<File> classpath;
    private final File outputSourcesDirectory;
    private final File outputClassesDirectory;
    private final File logsDirectory;

    private MicronautAotOptimizer(List<File> classpath,
                                  File outputSourcesDirectory,
                                  File outputClassesDirectory,
                                  File logsDirectory) {
        this.classpath = classpath;
        this.outputSourcesDirectory = outputSourcesDirectory;
        this.outputClassesDirectory = outputClassesDirectory;
        this.logsDirectory = logsDirectory;
    }

    private void compileGeneratedSources(List<File> extraClasspath, List<JavaFile> javaFiles) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> ds = new DiagnosticCollector<>();
        try (StandardJavaFileManager mgr = compiler.getStandardFileManager(ds, null, null)) {
            List<File> fullClasspath = new ArrayList<>(classpath);
            fullClasspath.addAll(extraClasspath);
            List<String> options = compilerOptions(outputClassesDirectory, fullClasspath);
            List<File> filesToCompile = outputSourceFilesToSourceDir(outputSourcesDirectory, javaFiles);
            if (outputClassesDirectory.exists() || outputClassesDirectory.mkdirs()) {
                Iterable<? extends JavaFileObject> sources = mgr.getJavaFileObjectsFromFiles(filesToCompile);
                JavaCompiler.CompilationTask task = compiler.getTask(null, mgr, ds, options, null, sources);
                task.call();
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to compile generated classes", e);
        }
        List<Diagnostic<? extends JavaFileObject>> diagnostics = ds.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .collect(Collectors.toList());
        if (!diagnostics.isEmpty()) {
            throwCompilationError(diagnostics);
        }
    }

    /**
     * Scans the list of available optimization services and generates
     * a configuration file which includes all entries.
     * @param runtime the runtime for which to generate a properties file
     * @param propertiesFile the generated properties file
     */
    public static void exportConfiguration(String runtime, File propertiesFile) {
        List<AOTSourceGenerator> list = SourceGeneratorLoader.list(Runtime.valueOf(runtime.toUpperCase(Locale.ENGLISH)));
        try (PrintWriter wrt = new PrintWriter(new FileOutputStream(propertiesFile))) {
            for (AOTSourceGenerator generator : list) {
                generator.getDescription().ifPresent(desc ->
                        Arrays.stream(desc.split("\r?\n")).forEach(line ->
                                wrt.println("# " + line)));
                wrt.println(generator.getId() + ".enabled = true");
                for (Option option : generator.getConfigurationOptions()) {
                    if (option.getDescription().isPresent()) {
                        wrt.println("# " + option.getDescription().get());
                    }
                    String sample = option.getSampleValue().orElse("");
                    wrt.println(option.getKey() + " = " + sample);
                }
                wrt.println();
            }
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This convenience method uses properties to load the configuration.
     * This is useful because the optimizer must be found on the same
     * classloader as the application under optimization, otherwise it
     * would mean that we could have a clash between Micronaut runtime
     * versions.
     *
     * @param props the configuration properties
     */
    public static void execute(Properties props) {
        Configuration config = new DefaultConfiguration(props);
        String pkg = config.mandatoryValue(GENERATED_PACKAGE);
        File outputDir = new File(config.mandatoryValue(OUTPUT_DIRECTORY));
        File sourcesDir = new File(outputDir, "sources");
        File classesDir = new File(outputDir, "classes");
        File logsDir = new File(outputDir, "logs");

        runner(pkg, sourcesDir, classesDir, logsDir, config)
                .addClasspath(config.stringList(CLASSPATH).stream().map(File::new).collect(Collectors.toList()))
                .execute();
    }

    public static Runner runner(String generatedPackage,
                                File outputSourcesDirectory,
                                File outputClassesDirectory,
                                File logsDirectory,
                                Configuration config) {
        return new Runner(generatedPackage, outputSourcesDirectory, outputClassesDirectory, logsDirectory, config);
    }

    private static List<File> outputSourceFilesToSourceDir(File srcDir, List<JavaFile> javaFiles) {
        List<File> srcFiles = new ArrayList<>(javaFiles.size());
        if (srcDir.isDirectory() || srcDir.mkdirs()) {
            StreamHelper.trying(() -> {
                for (JavaFile javaFile : javaFiles) {
                    javaFile.writeTo(srcDir);
                }
                Files.walkFileTree(srcDir.toPath(), new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        srcFiles.add(file.toFile());
                        return super.visitFile(file, attrs);
                    }
                });
            });
        }
        return srcFiles;
    }

    private static void throwCompilationError(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        StringBuilder sb = new StringBuilder("Compilation errors:\n");
        for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
            JavaFileObject source = d.getSource();
            String srcFile = source == null ? "unknown" : new File(source.toUri()).getName();
            String diagLine = String.format("File %s, line: %d, %s", srcFile, d.getLineNumber(), d.getMessage(null));
            sb.append(diagLine).append("\n");
        }
        throw new RuntimeException(sb.toString());
    }

    private static List<String> compilerOptions(File dstDir,
                                                List<File> classPath) {
        List<String> options = new ArrayList<>();
        options.add("-source");
        options.add("1.8");
        options.add("-target");
        options.add("1.8");
        options.add("-classpath");
        String cp = classPath.stream().map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator));
        options.add(cp);
        options.add("-d");
        options.add(dstDir.getAbsolutePath());
        return options;
    }

    private void writeLogs(SourceGenerationContext context) {
        if (logsDirectory.isDirectory() || logsDirectory.mkdirs()) {
            writeLines(new File(logsDirectory, OUTPUT_RESOURCES_FILE_NAME), context.getExcludedResources());
            context.getDiagnostics().forEach((key, messages) -> {
                File logFile = new File(logsDirectory, key.toLowerCase(Locale.US) + ".log");
                writeLines(logFile, messages);
            });
        }
    }

    private static void writeLines(File outputFile, Collection<String> lines) {
        try (PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8)
        )) {
            lines.forEach(writer::println);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The main AOT optimizer runner.
     */
    public static final class Runner {
        private final List<File> classpath = new ArrayList<>();
        private final String generatedPackage;
        private final File outputSourcesDirectory;
        private final File outputClassesDirectory;
        private final File logsDirectory;

        private final Configuration config;

        public Runner(String generatedPackage,
                      File outputSourcesDirectory,
                      File outputClassesDirectory,
                      File logsDirectory,
                      Configuration config
        ) {
            this.generatedPackage = generatedPackage;
            this.outputSourcesDirectory = outputSourcesDirectory;
            this.outputClassesDirectory = outputClassesDirectory;
            this.logsDirectory = logsDirectory;
            this.config = config;
        }

        /**
         * Adds elements to the application classpath.
         *
         * @param elements the files to add to classpath
         * @return this builder
         */
        public Runner addClasspath(Collection<File> elements) {
            classpath.addAll(elements);
            return this;
        }

        @SuppressWarnings("unchecked")
        public Runner execute() {
            MicronautAotOptimizer optimizer = new MicronautAotOptimizer(
                    classpath,
                    outputSourcesDirectory,
                    outputClassesDirectory,
                    logsDirectory);
            ApplicationContextAnalyzer analyzer = ApplicationContextAnalyzer.create();
            Set<String> environmentNames = analyzer.getEnvironmentNames();
            LOGGER.info("Detected environments: {}", environmentNames);
            SourceGenerationContext context = new DefaultSourceGenerationContext(generatedPackage, analyzer, config);
            List<AOTSourceGenerator> sourceGenerators = SourceGeneratorLoader.load(config.getRuntime(), context);
            ApplicationContextCustomizerGenerator generator = new ApplicationContextCustomizerGenerator(
                    sourceGenerators
            );
            generator.init(context);
            optimizer.compileGeneratedSources(context.getExtraClasspath(), generator.generateSourceFiles());
            generator.generateResourceFiles(outputClassesDirectory);
            optimizer.writeLogs(context);
            return this;
        }
    }

}
