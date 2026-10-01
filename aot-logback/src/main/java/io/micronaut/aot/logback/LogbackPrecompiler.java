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
package io.micronaut.aot.logback;

import io.micronaut.core.annotation.Internal;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Compiles an application's {@code logback.xml} into a Logback {@code Configurator} at build time, so that the
 * application does not parse XML and run Joran on the main thread at every start.
 *
 * <p>It is the engine the Micronaut Gradle and Maven plugins and Micronaut Runner share: they call
 * {@link #precompile(Request)} in-process with plain paths and get class files back. It is internal to them, not
 * an API for applications or other build tools, and may change in any release. It fails closed: whenever it
 * cannot prove that the generated code reproduces Joran's result exactly, it generates nothing, says why in
 * {@link Result#message()}, and Logback reads {@code logback.xml} with Joran at startup exactly as it would
 * without this class.</p>
 *
 * <h2>What it generates</h2>
 * <p>Entries under {@value #PACKAGE_PATH}: a {@code LogbackConfigurator} emitted with the ClassFile API, the
 * {@code JoranFallback} class and, unless the class path is {@linkplain Request.Builder#closedClassPath(boolean)
 * closed}, the {@code ClassPathGuard} class, both copied byte for byte from the front end jar this module carries,
 * and a {@code META-INF/services/ch.qos.logback.classic.spi.Configurator} file naming the configurator.
 * {@code logback.xml} has to stay in the application for the fallbacks. At runtime the configurator, on every
 * call:</p>
 * <ol>
 *     <li>hands over to Logback's own default lookup when {@code -Dlogback.configurationFile} is set;</li>
 *     <li>on a second or later call (Micronaut's {@code LoggingSystem.refresh()}), configures from the first of
 *     these that is set, as Micronaut does without a configurator: an environment variable named
 *     {@code logback.configurationFile}, the {@code logger.config} system property, the {@code LOGGER_CONFIG}
 *     environment variable, an environment variable named {@code logger.config};</li>
 *     <li>hands over to Logback's default lookup when {@code logback.debug} or {@code logback.statusListenerClass}
 *     asks for Logback's status output, or {@value #OPT_OUT_PROPERTY} is {@code false};</li>
 *     <li>unless the class path is closed, hands over to Logback's default lookup when a {@code logback-test.xml}
 *     is visible, or the first visible {@code logback.xml} is missing or is not the file that was compiled, which
 *     it checks again on every call;</li>
 *     <li>otherwise applies the configuration literally, in Joran's order, and returns
 *     {@code DO_NOT_INVOKE_NEXT_IF_ANY}.</li>
 * </ol>
 * <p>A location that only Micronaut's own configuration holds, such as a {@code --logger.config} program argument,
 * is not applied: Micronaut's refresh calls a registered configurator with the logger context alone.</p>
 *
 * <h2>Build policy</h2>
 * <p>It never loads, initialises or runs application classes. Its front end runs Logback and slf4j-api classes,
 * and only those, taken from the runtime class path it is given and never from the caller's own, in an isolated
 * class loader whose parent is the platform class loader, and instantiates only the {@code ch.qos.logback.*}
 * classes the configuration names. That loader is also the thread's context class loader while the front end
 * runs, so a service lookup in it, such as JAXP's for an XML parser, finds the JDK's provider and nothing from the
 * build tool's class path. It never starts an appender and never opens a file or a stream of the application.</p>
 *
 * <h2>When it generates nothing</h2>
 * <p>When the target release is below {@value #MINIMUM_RELEASE}; when logback-classic, logback-core or slf4j-api
 * is missing, the application output carries Logback or SLF4J classes of its own, or either Logback version is
 * missing, differs from the other or is outside {@value #MINIMUM_VERSION} to {@value #VERSION_LIMIT} (exclusive);
 * when there is no {@code logback.xml} or more than one class path entry or application root has one, or any
 * entry has a {@code logback-test.xml}, a {@code logback.groovy} or a versioned Logback file; when any entry of
 * the class path it is given already registers a {@code Configurator} service, which covers the application's own
 * configurator and an application that the {@code logback.xml.to.java} optimizer has already optimized; when a
 * packaged {@code application*} or {@code bootstrap*} file, of the application output or of any entry of the
 * runtime class path, might set {@code logger.config} or {@code logback.configurationFile}, which the generated
 * code cannot see, or might import configuration with {@code micronaut.config.import}, which is not followed;
 * and when the front end finds the file outside its literal subset. Each of these is
 * {@link Status#STOOD_DOWN}. A generated name that is already taken, which includes the output of an earlier call
 * that is still in the application output, a class path that cannot be read, or an unexpected failure, is
 * {@link Status#FAILED}.</p>
 *
 * <h2>When it warns</h2>
 * <p>One case still generates, as {@link Status#GENERATED}, but adds a line to {@link Result#warnings()}: a
 * packaged {@code application*} or {@code bootstrap*} file, of the application output or of any entry of the
 * runtime class path, that might enable Micronaut's distributed configuration client
 * ({@code micronaut.config-client.enabled} set to {@code true}). A {@code logger.config} or
 * {@code logback.configurationFile} that distributed configuration supplies is not applied while the generated
 * configurator is registered, and the build cannot know whether the configuration server supplies one. A client
 * that only the run time enables, by an environment variable, a system property or a file that is not on the class
 * path given here, is not seen. A stand-down or a failure has no warnings.</p>
 *
 * @since 3.2.0
 */
@Internal
public final class LogbackPrecompiler {

    /** The binary name of the generated configurator, in the same package in every packaging. */
    static final String CONFIGURATOR_CLASS = "io.micronaut.aot.logback.generated.LogbackConfigurator";

    /** The binary name of the class every Joran path goes through. */
    static final String FALLBACK_CLASS = "io.micronaut.aot.logback.generated.JoranFallback";

    /** The binary name of the class that checks an open class path. */
    static final String GUARD_CLASS = "io.micronaut.aot.logback.generated.ClassPathGuard";

    /** The entry names of the generated classes. */
    static final String PACKAGE_PATH = "io/micronaut/aot/logback/generated/";

    static final String CONFIGURATOR_ENTRY = PACKAGE_PATH + "LogbackConfigurator.class";

    static final String FALLBACK_ENTRY = PACKAGE_PATH + "JoranFallback.class";

    static final String GUARD_ENTRY = PACKAGE_PATH + "ClassPathGuard.class";

    /** The service file that makes Logback, and Micronaut's refresh, find the configurator. */
    static final String SERVICE_ENTRY = "META-INF/services/ch.qos.logback.classic.spi.Configurator";

    /** The system property that, set to {@code false}, hands every call over to Logback's default lookup. */
    static final String OPT_OUT_PROPERTY = "micronaut.logback.precompiled";

    /** The front end, which this module carries as a resource and never puts on its own class path. */
    static final String FRONTEND_RESOURCE = "/META-INF/micronaut-aot/logback-frontend.jar";

    /**
     * Applied to every description the front end returns before code is emitted from it. Tests replace the
     * function to force a front-end or emitter failure; it is never set in production.
     */
    static final AtomicReference<UnaryOperator<Map<String, Object>>> DESCRIPTION_HOOK =
            new AtomicReference<>(UnaryOperator.identity());

    /** The lowest Logback version the generated code is tested against. */
    private static final String MINIMUM_VERSION = "1.5.37";

    /** The first Logback version it is not. */
    private static final String VERSION_LIMIT = "1.6";

    /** The lowest Java release that loads the generated classes, which are class files of that release. */
    private static final int MINIMUM_RELEASE = 25;

    private static final String FRONTEND_CLASS = "io.micronaut.aot.logback.internal.frontend.LogbackFrontend";

    private static final String FRONTEND_FILE = "logback-frontend.jar";

    private static final int IR_VERSION = 1;

    private static final String LOGBACK_XML = "logback.xml";

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    private static final String CLASSIC_MARKER = "ch/qos/logback/classic/LoggerContext.class";

    private static final String CORE_MARKER = "ch/qos/logback/core/Context.class";

    private static final String SLF4J_MARKER = "org/slf4j/ILoggerFactory.class";

    private static final String NOT_PRECOMPILED = "No Logback configuration was precompiled because ";

    private static final String JORAN_AT_STARTUP = "; Logback will configure itself with Joran at startup";

    private static final Pattern VERSIONED_LOGBACK_FILE =
            Pattern.compile("META-INF/versions/[^/]+/logback(-test)?\\.xml");

    private static final List<String> CONFIGURATION_EXTENSIONS =
            List.of("properties", "yml", "yaml", "json", "toml", "groovy");

    /**
     * The configuration files the {@code logger.config} scan reads. This is wider than what Micronaut reads by
     * default, which is {@code application} and {@code bootstrap}, with an optional {@code -<environment>}
     * suffix, at the root of the class path. It takes any name that starts with either word, and the
     * {@code config/} directory, which Micronaut reads only when {@code overrideConfigLocations} adds a location
     * such as {@code classpath:config/}. A false positive only costs the optimisation.
     */
    private static final Pattern PACKAGED_CONFIGURATION = Pattern.compile(
            "(config/)?(application|bootstrap)[^/]*\\.(" + String.join("|", CONFIGURATION_EXTENSIONS) + ")");

    /**
     * The word {@code logger} in lower-cased text, wherever it stands: a block or flow YAML key, a JSON member, a
     * TOML {@code [logger]} table or inline table, a Groovy closure.
     */
    private static final Pattern LOGGER_WORD = Pattern.compile("(?<![a-z0-9_-])logger(?![a-z0-9_-])");

    /**
     * A {@code config} key in lower-cased text, wherever it stands, quoted or not: the word, then {@code :} or
     * {@code =}, with at most a closing quote, a closing bracket and white space in between.
     */
    private static final Pattern CONFIG_KEY = Pattern.compile("(?<![a-z0-9_-])config[\"']?\\s*+]?\\s*+[:=]");

    /** The word {@code config} in lower-cased text, wherever it stands: a key, a TOML table name, a value. */
    private static final Pattern CONFIG_WORD = Pattern.compile("(?<![a-z0-9_-])config(?![a-z0-9_-])");

    /**
     * An {@code import} key in lower-cased text, wherever it stands, quoted or not: the word, then {@code :},
     * {@code =}, {@code .} or {@code [}, with at most a closing quote, a closing bracket and white space in
     * between. That is a block or flow YAML key, a JSON member, a TOML key and the start of a dotted, indexed or
     * structured declaration; an {@code import} statement or the word in a sentence is not.
     */
    private static final Pattern IMPORT_KEY = Pattern.compile("(?<![a-z0-9_-])import[\"']?\\s*+]?\\s*+[:=.\\[]");

    /** The dotted {@code config.import} of a flat key or a TOML table name, in lower-cased text. */
    private static final Pattern CONFIG_IMPORT_PATH = Pattern.compile("(?<![a-z0-9_-])config\\.import(?![a-z0-9_-])");

    /** The key of Micronaut's configuration imports, lower-cased. */
    private static final String CONFIG_IMPORT = "micronaut.config.import";

    /** The key that enables Micronaut's distributed configuration client. */
    private static final String CONFIG_CLIENT_ENABLED = "micronaut.config-client.enabled";

    /** {@link #CONFIG_CLIENT_ENABLED} as a {@code .properties} key is compared: lower-cased, without hyphens. */
    private static final String CONFIG_CLIENT_ENABLED_KEY = CONFIG_CLIENT_ENABLED.replace("-", "");

    /**
     * The word {@code config-client}, or {@code configClient}, in lower-cased text, wherever it stands: a block or
     * flow YAML key, a dotted key, a JSON member, a TOML table name, a Groovy closure.
     */
    private static final Pattern CONFIG_CLIENT_WORD = Pattern.compile("(?<![a-z0-9_-])config-?client(?![a-z0-9_-])");

    /**
     * An {@code enabled} key set to {@code true} in lower-cased text, quoted or not: the word, then {@code :} or
     * {@code =}, with at most a closing quote, a closing bracket and white space in between, then the value, quoted
     * or not. {@code yes} and {@code on} count as well: SnakeYAML, with which Micronaut reads YAML, reads them as
     * {@code true}.
     */
    private static final Pattern ENABLED_TRUE =
            Pattern.compile("(?<![a-z0-9_-])enabled[\"']?\\s*+]?\\s*+[:=]\\s*+[\"']?(?:true|yes|on)(?![a-z0-9_-])");

    /** The name under which Micronaut reads a logging configuration location: a property or an environment variable. */
    private static final String LOGGER_CONFIG_PROPERTY = "logger.config";

    private static final String MAY_SET_A_LOCATION =
            "may set " + LOGGER_CONFIG_PROPERTY + " or logback.configurationFile";

    private static final List<String> APPLICATION_INPUTS = inputPatterns();

    /**
     * The permissions of a temporary work directory, which holds the front end jar that is then run: only its
     * owner may read, write or list it. They are explicit on a POSIX file system, where the directory is created
     * in a shared one such as {@code /tmp}. Elsewhere, as on Windows, the directory is created in the user's own
     * temporary directory and takes its access control list.
     */
    private static final FileAttribute<?>[] OWNER_ONLY =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                    ? new FileAttribute<?>[] {
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
                    }
                    : new FileAttribute<?>[0];

    private LogbackPrecompiler() {
    }

    /**
     * Precompiles the first {@code logback.xml} on a class path, or reports why it does not.
     *
     * <p>It never throws for anything about the inputs: a missing class path entry, an unreadable file and any
     * failure of the front end or of the emitter, {@link Error}s included, are a {@link Result} that generated
     * nothing. So a build that calls it never fails because of Logback. Only a {@link VirtualMachineError}
     * propagates.</p>
     *
     * <p>Before it returns, the isolated class loader of the front end is closed and the caller's context class
     * loader is restored. Each call unpacks the front end into a temporary directory of its own, which it deletes,
     * so calls are independent of each other.</p>
     *
     * @param request what to compile and for which class path
     * @return what was generated, or why nothing was
     */
    public static Result precompile(Request request) {
        Objects.requireNonNull(request, "request");
        long start = System.nanoTime();
        if (request.targetRelease < MINIMUM_RELEASE) {
            return Result.standDown("the target release " + request.targetRelease + " is below "
                    + MINIMUM_RELEASE + ", the Java release of the generated classes");
        }
        List<Layer> layers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Survey survey;
        try {
            layers.add(Layer.application(request.applicationOutput));
            for (Path entry : request.runtimeClasspath) {
                layers.add(Layer.of(entry));
            }
            if (registersGeneratedConfigurator(layers.get(0))) {
                // Not a stand-down: that output is still on the class path, and it was compiled from the
                // application as it was then.
                return Result.failed("The application output already holds the output of an earlier"
                        + " precompilation (" + SERVICE_ENTRY + " names " + CONFIGURATOR_CLASS + "), which stays"
                        + " in use; no Logback configuration was precompiled. Remove that output before"
                        + " precompiling again");
            }
            survey = Survey.of(layers);
            String reason = survey.standDownReason();
            if (reason == null) {
                reason = packagedConfigurationReason(layers, warnings);
            }
            if (reason != null) {
                return Result.standDown(reason);
            }
        } catch (IOException | RuntimeException e) {
            return Result.failure("the class path could not be read", e);
        }
        for (String name : generatedNames(request.closedClassPath)) {
            if (layers.get(0).names().contains(name)) {
                return Result.failed("The application output already carries '" + name + "'; no Logback"
                        + " configuration was precompiled and Logback will configure itself with Joran at startup");
            }
        }
        Path work = request.workDirectory;
        boolean temporary = work == null;
        try {
            if (temporary) {
                work = Files.createTempDirectory("micronaut-aot-logback", OWNER_ONLY);
            } else {
                Files.createDirectories(work);
            }
            return compile(request, layers, survey, work.resolve(FRONTEND_FILE), start, warnings);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (IOException | ReflectiveOperationException | RuntimeException | Error e) {
            // Every other Error too: a LinkageError from a Logback that changed, or what a service lookup throws
            // (FactoryConfigurationError, ServiceConfigurationError), must not fail a build that works without
            // this optimisation.
            return Result.failure("it could not be compiled", e);
        } finally {
            if (temporary && work != null) {
                deleteQuietly(work.resolve(FRONTEND_FILE));
                deleteQuietly(work);
            }
        }
    }

    /**
     * The entries of the application output that can change the result, as Ant-style patterns relative to a root
     * ({@code *} matches within a path segment, {@code **}{@code /} any number of directories). Everything else
     * in a root is ignored: its name is read and nothing more, its {@code META-INF/MANIFEST.MF} included.
     *
     * <p>This is a contract a build plugin's input declaration may rely on: a task that declares the application
     * output filtered by these patterns, plus the runtime class path, stays up to date when ordinary classes and
     * resources change. They are {@code logback.xml}, {@code logback-test.xml}, {@code logback.groovy} and the
     * versioned Logback files; the {@code Configurator} service file; classes under {@code ch/qos/logback/} and
     * {@code org/slf4j/}; the {@code application*} and {@code bootstrap*} configuration files at the root and
     * under {@code config/}; and the generated names.</p>
     *
     * @return the patterns, unmodifiable
     */
    public static List<String> applicationInputs() {
        return APPLICATION_INPUTS;
    }

    /**
     * Whether a Logback version is in {@code [MINIMUM_VERSION, VERSION_LIMIT)}. Only plain numeric versions are:
     * a qualified one, such as a snapshot, was never tested.
     *
     * @param version an {@code Implementation-Version}
     * @return whether the generated code is tested against it
     */
    static boolean supported(String version) {
        for (String number : version.split("\\.", -1)) {
            if (number.isEmpty() || !number.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return false;
            }
        }
        return compare(version, MINIMUM_VERSION) >= 0 && compare(version, VERSION_LIMIT) < 0;
    }

    /**
     * The names a result has, in order.
     *
     * @param closed whether the class path is closed
     * @return the entry names
     */
    static List<String> generatedNames(boolean closed) {
        return closed ? List.of(CONFIGURATOR_ENTRY, FALLBACK_ENTRY, SERVICE_ENTRY)
                : List.of(CONFIGURATOR_ENTRY, FALLBACK_ENTRY, GUARD_ENTRY, SERVICE_ENTRY);
    }

    private static List<String> inputPatterns() {
        List<String> patterns = new ArrayList<>(List.of(LOGBACK_XML, "logback-test.xml", "logback.groovy",
                "META-INF/versions/*/logback.xml", "META-INF/versions/*/logback-test.xml", SERVICE_ENTRY,
                "ch/qos/logback/**/*.class", "org/slf4j/**/*.class"));
        for (String directory : List.of("", "config/")) {
            for (String file : List.of("application", "bootstrap")) {
                for (String extension : CONFIGURATION_EXTENSIONS) {
                    patterns.add(directory + file + "*." + extension);
                }
            }
        }
        patterns.addAll(List.of(CONFIGURATOR_ENTRY, FALLBACK_ENTRY, GUARD_ENTRY));
        return List.copyOf(patterns);
    }

    private static int compare(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0;
            long y = i < b.length ? Long.parseLong(b[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static Result compile(Request request, List<Layer> layers, Survey survey, Path frontEnd, long start,
                                  List<String> warnings) throws IOException, ReflectiveOperationException {
        Layer source = layers.get(survey.logbackXml);
        try (InputStream in = LogbackPrecompiler.class.getResourceAsStream(FRONTEND_RESOURCE)) {
            if (in == null) {
                throw new IOException("the module carries no Logback front end at " + FRONTEND_RESOURCE
                        + "; it was built incorrectly");
            }
            Files.copy(in, frontEnd, StandardCopyOption.REPLACE_EXISTING);
        }
        byte[] logbackXml = source.read(LOGBACK_XML);
        byte[] fallback;
        Guard guard = null;
        try (ZipFile zip = new ZipFile(frontEnd.toFile())) {
            fallback = read(zip, FALLBACK_ENTRY);
            if (!request.closedClassPath) {
                CRC32 crc = new CRC32();
                crc.update(logbackXml);
                guard = new Guard(read(zip, GUARD_ENTRY), logbackXml.length, crc.getValue());
            }
        }
        URL[] path = {
            frontEnd.toUri().toURL(),
            source(layers, survey.classic).toUri().toURL(),
            source(layers, survey.core).toUri().toURL(),
            source(layers, survey.slf4j).toUri().toURL()
        };
        Map<String, byte[]> entries;
        Map<String, Object> description;
        try (URLClassLoader loader = new URLClassLoader("micronaut-aot-logback-frontend", path,
                ClassLoader.getPlatformClassLoader())) {
            description = DESCRIPTION_HOOK.get().apply(describe(loader, logbackXml));
            if (!Integer.valueOf(IR_VERSION).equals(description.get("irVersion"))) {
                throw new IllegalStateException("the Logback front end answered with description version "
                        + description.get("irVersion") + ", not " + IR_VERSION);
            }
            Object rejection = description.get("rejection");
            if (rejection != null) {
                return Result.standDown("logback.xml (" + source.description() + ") is outside what the"
                        + " precompiler can reproduce exactly: " + rejection);
            }
            entries = Emitter.emit(description, fallback, guard, loader);
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        int appenders = (Integer) description.get("appenders");
        int patterns = (Integer) description.get("patterns");
        return new Result(Status.GENERATED, "Precompiled logback.xml (" + source.description() + ") into "
                + CONFIGURATOR_CLASS + ": " + appenders + (appenders == 1 ? " appender, " : " appenders, ")
                + patterns + (patterns == 1 ? " pattern, " : " patterns, ") + millis + " ms", entries, warnings);
    }

    private static Path source(List<Layer> layers, int index) throws IOException {
        Path source = layers.get(index).source();
        if (source == null) {
            // Unreachable: the survey stands down when the application output carries a Logback or SLF4J class.
            throw new IOException("the application output is not a class path entry of its own");
        }
        return source;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IOException(zip.getName() + " has no " + name);
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException _) {
            // A temporary file that stays behind is no reason to fail, or to report anything else than the result.
        }
    }

    /**
     * Whether the application output holds what an earlier call generated: its {@code Configurator} service file
     * names the generated configurator. That happens when a caller writes a result into an application root and
     * precompiles again without removing it.
     */
    private static boolean registersGeneratedConfigurator(Layer application) throws IOException {
        if (!application.names().contains(SERVICE_ENTRY)) {
            return false;
        }
        String services = new String(application.read(SERVICE_ENTRY), StandardCharsets.UTF_8);
        for (String line : services.split("\\R")) {
            int comment = line.indexOf('#');
            if ((comment < 0 ? line : line.substring(0, comment)).strip().equals(CONFIGURATOR_CLASS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the front end with the isolated loader as the thread's context class loader, and restores the caller's
     * afterwards. Logback's {@code SaxEventRecorder} asks JAXP for a parser factory, and JAXP, like every service
     * lookup, searches the context class loader: left alone, that is the build tool's or the plugin's class path,
     * whose XML parser would then run here. With the isolated loader it is the JDK's own.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> describe(ClassLoader loader, byte[] logbackXml)
            throws ReflectiveOperationException {
        Thread thread = Thread.currentThread();
        ClassLoader caller = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            Function<byte[], Map<String, Object>> frontEnd = (Function<byte[], Map<String, Object>>)
                    Class.forName(FRONTEND_CLASS, true, loader).getConstructor().newInstance();
            return frontEnd.apply(logbackXml);
        } finally {
            thread.setContextClassLoader(caller);
        }
    }

    /**
     * Stands down when a packaged configuration file might set {@code logger.config} or
     * {@code logback.configurationFile}: Micronaut's refresh applies such a location only when no
     * {@code Configurator} service is registered, and the generated code cannot see it.
     *
     * <p>Every {@code application*} and {@code bootstrap*} file of every class path entry is checked: those of
     * each application root and those of each entry of the runtime class path, in class path order, including a
     * file that an earlier entry shadows. Micronaut reads a configuration file of a dependency whenever the
     * application has none of that name, and its configuration loading strategy can merge the files of every
     * entry; which strategy the application uses is not known here. The names checked, those of
     * {@link #PACKAGED_CONFIGURATION}, include more than Micronaut reads by default.</p>
     *
     * <p>A file that may import configuration with {@code micronaut.config.import} stands it down too. Such an
     * import can load a class path resource of any name, a file, an environment variable or what a custom
     * provider supplies, and an imported file can import further. This check does not follow imports, so it
     * stands down on any import, whatever it names.</p>
     *
     * <p>A file that may enable Micronaut's distributed configuration client does not stand it down: a location
     * that a configuration server supplies is not applied either, but the build cannot know whether the server
     * supplies one. Such a file adds a line to {@code warnings} instead, which only a generated result
     * carries. {@link PackagedFile} says what each rule reads.</p>
     *
     * @param layers   the class path, the application output first
     * @param warnings where a line for each file that may enable the client is added, in class path order
     * @return the reason to stand down, or {@code null}
     */
    private static @Nullable String packagedConfigurationReason(List<Layer> layers, List<String> warnings)
            throws IOException {
        for (int i = 0; i < layers.size(); i++) {
            String reason = packagedConfigurationReason(layers.get(i), i == 0, warnings);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    /**
     * The reason of {@link #packagedConfigurationReason(List, List)} for one layer, checking each of its roots in
     * order.
     */
    private static @Nullable String packagedConfigurationReason(Layer layer, boolean application,
                                                                List<String> warnings) throws IOException {
        Set<String> seen = new HashSet<>();
        for (Root root : layer.roots()) {
            for (String name : root.names()) {
                if (!isPackagedConfiguration(name)) {
                    continue;
                }
                PackagedFile file = PackagedFile.read(name, root.read(name));
                String what = file.standDownReason();
                if (what != null) {
                    return "the packaged " + name + where(layer, root, application, seen.contains(name)) + " " + what;
                }
                if (file.enablesConfigClient()) {
                    warnings.add(configClientWarning(name + where(layer, root, application, true)));
                }
                seen.add(name);
            }
        }
        return null;
    }

    private static boolean isPackagedConfiguration(String name) {
        return (name.startsWith("application") || name.startsWith("bootstrap") || name.startsWith("config/"))
                && PACKAGED_CONFIGURATION.matcher(name).matches();
    }

    /**
     * How a message names where a packaged configuration file is: by its class path entry, or, for the
     * application output, by its root when {@code nameRoot} asks for it. A stand-down names the root only when an
     * earlier root shadows the file; a warning always names it.
     */
    private static String where(Layer layer, Root root, boolean application, boolean nameRoot) {
        if (!application) {
            return " of " + layer.description();
        }
        return nameRoot ? " of " + root.path() : "";
    }

    /**
     * The warning for a packaged configuration file that may enable the distributed configuration client: which
     * file, what is not applied and what to do instead. The run-time opt-out is no remedy: with it the generated
     * configurator runs Logback's default lookup, and Micronaut still calls it instead of reading the location.
     *
     * @param file the file's name in its root, then where it is, as {@link #where} names it
     * @return one line
     */
    private static String configClientWarning(String file) {
        return "The packaged " + file + " enables Micronaut's distributed configuration client ("
                + CONFIG_CLIENT_ENABLED + "): a " + LOGGER_CONFIG_PROPERTY + " or logback.configurationFile that"
                + " distributed configuration supplies is not applied while the generated configurator is registered."
                + " Pass the location as the JVM system property -D" + LOGGER_CONFIG_PROPERTY + " or the LOGGER_CONFIG"
                + " environment variable instead, or turn precompilation off in the build plugin; -D"
                + OPT_OUT_PROPERTY + "=false does not help, as Micronaut then still calls the configurator";
    }

    /**
     * What a packaged configuration file may do that the generated code cannot follow.
     *
     * <p>A {@code .properties} file is parsed. It sets a location when it has {@code logger.config} or
     * {@code logback.configurationFile}; it imports when it has {@code micronaut.config.import}, an indexed
     * {@code micronaut.config.import[n]} or a structured {@code micronaut.config.import.*} key; and it enables the
     * client when {@code micronaut.config-client.enabled} is {@code true}. Keys are compared ignoring case and
     * hyphens, the value ignoring case and surrounding white space.</p>
     *
     * <p>Any other format is not parsed, so each rule is on its text, ignoring case, and errs on the side of the
     * stand-down or the warning. It sets a location when the text names {@code configurationFile} or
     * {@code logger.config}, or has the word {@code logger} and a {@code config} key anywhere, in any order and on
     * any line. It imports when the text names {@code config.import}, or has an {@code import} key and the word
     * {@code config} anywhere. It enables the client when the text has the word {@code config-client} (or
     * {@code configClient}) and an {@code enabled} key set to {@code true} anywhere. That covers nested YAML,
     * flow-style YAML, JSON on one line, TOML tables and inline tables and a Groovy closure. A false positive only
     * costs the optimisation, or a warning.</p>
     *
     * @param setsLocation        whether it may set {@code logger.config} or {@code logback.configurationFile}
     * @param imports             whether it may import configuration with {@code micronaut.config.import}
     * @param enablesConfigClient whether it may enable Micronaut's distributed configuration client
     */
    private record PackagedFile(boolean setsLocation, boolean imports, boolean enablesConfigClient) {

        static PackagedFile read(String name, byte[] content) throws IOException {
            if (name.endsWith(".properties")) {
                return parse(content);
            }
            String lower = new String(content, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            return new PackagedFile(
                    lower.contains("configurationfile") || lower.contains("configuration-file")
                            || lower.contains(LOGGER_CONFIG_PROPERTY)
                            || LOGGER_WORD.matcher(lower).find() && CONFIG_KEY.matcher(lower).find(),
                    CONFIG_IMPORT_PATH.matcher(lower).find()
                            || IMPORT_KEY.matcher(lower).find() && CONFIG_WORD.matcher(lower).find(),
                    CONFIG_CLIENT_WORD.matcher(lower).find() && ENABLED_TRUE.matcher(lower).find());
        }

        private static PackagedFile parse(byte[] content) throws IOException {
            Properties properties = new Properties();
            properties.load(new ByteArrayInputStream(content));
            boolean setsLocation = false;
            boolean imports = false;
            boolean enablesConfigClient = false;
            for (String key : properties.stringPropertyNames()) {
                String normalized = key.toLowerCase(Locale.ROOT).replace("-", "");
                setsLocation |= normalized.equals(LOGGER_CONFIG_PROPERTY)
                        || normalized.equals("logback.configurationfile");
                imports |= normalized.equals(CONFIG_IMPORT) || normalized.startsWith(CONFIG_IMPORT + ".")
                        || normalized.startsWith(CONFIG_IMPORT + "[");
                enablesConfigClient |= normalized.equals(CONFIG_CLIENT_ENABLED_KEY)
                        && properties.getProperty(key).strip().equalsIgnoreCase("true");
            }
            return new PackagedFile(setsLocation, imports, enablesConfigClient);
        }

        /**
         * Why the file stands the precompiler down, or {@code null} when it does not: it may set a location, or it
         * may import configuration that does. Enabling the client is no reason.
         *
         * @return the reason, or {@code null}
         */
        @Nullable String standDownReason() {
            if (setsLocation) {
                return MAY_SET_A_LOCATION + ", which Micronaut applies only without a Configurator service";
            }
            if (imports) {
                return "may import configuration with " + CONFIG_IMPORT + ", which is not read here and "
                        + MAY_SET_A_LOCATION + "; Micronaut applies such a location only without a Configurator"
                        + " service";
            }
            return null;
        }
    }

    /**
     * What to compile and for which class path: plain paths, no build tool type. Instances are immutable.
     *
     * @since 3.2.0
     */
    @Internal
    public static final class Request {

        private final List<Path> applicationOutput;
        private final List<Path> runtimeClasspath;
        private final int targetRelease;
        private final boolean closedClassPath;
        private final @Nullable Path workDirectory;

        private Request(Builder builder) {
            this.applicationOutput = require(builder.applicationOutput, "applicationOutput");
            this.runtimeClasspath = require(builder.runtimeClasspath, "runtimeClasspath");
            this.targetRelease = require(builder.targetRelease, "targetRelease");
            this.closedClassPath = builder.closedClassPath;
            this.workDirectory = builder.workDirectory;
        }

        /**
         * Starts a request.
         *
         * @return a builder with nothing set
         */
        public static Builder builder() {
            return new Builder();
        }

        private static <T> T require(@Nullable T value, String input) {
            if (value == null) {
                throw new IllegalStateException(input + " is required");
            }
            return value;
        }

        /**
         * Builds a {@link Request}. {@link #applicationOutput(List)}, {@link #runtimeClasspath(List)} and
         * {@link #targetRelease(int)} are required.
         *
         * @since 3.2.0
         */
        @Internal
        public static final class Builder {

            private @Nullable List<Path> applicationOutput;
            private @Nullable List<Path> runtimeClasspath;
            private @Nullable Integer targetRelease;
            private boolean closedClassPath;
            private @Nullable Path workDirectory;

            private Builder() {
            }

            /**
             * The application's own output, in class path order: its resource roots and class directories, or a
             * jar. Required.
             *
             * <p>Pass the class directories as well as the resources. Two rules need them: the engine stands down
             * when the application carries a Logback or SLF4J class of its own, and when a {@code Configurator}
             * is already registered, which an annotation processor does by writing the service file to the class
             * output. The roots are one layer of the class path: when two of them hold the same name, the first
             * wins. There are two exceptions, because wherever the roots are class path entries of their own both
             * files are found: when two roots hold a {@code logback.xml} the engine stands down, as Logback reports
             * the file that occurs twice, and the packaged {@code application*} and {@code bootstrap*} files of
             * every root are read, since Micronaut can merge them. Only the entries
             * {@link LogbackPrecompiler#applicationInputs()} describes are read; of everything else, the name.
             * Every root has to exist.</p>
             *
             * @param roots directories or jars
             * @return this builder
             */
            public Builder applicationOutput(List<Path> roots) {
                this.applicationOutput = List.copyOf(roots);
                return this;
            }

            /**
             * The runtime class path after the application, in order: jars or directories. Required.
             *
             * <p>Each entry is one layer: a jar gives the names of its central directory, a directory the names
             * of its files. logback-classic, logback-core and slf4j-api are taken from here, never from the
             * caller's own class path, and the {@code META-INF/MANIFEST.MF} of the two Logback entries is read
             * for their version. The packaged {@code application*} and {@code bootstrap*} configuration files of
             * every entry are read as well, at its root, where Micronaut reads those of a dependency too, and
             * under {@code config/}, which Micronaut reads only when {@code overrideConfigLocations} adds it. The
             * engine stands down when one might set {@code logger.config} or {@code logback.configurationFile},
             * or might import configuration with {@code micronaut.config.import}, and warns when one might enable
             * the distributed configuration client. Every entry has to exist.</p>
             *
             * @param entries jars or directories
             * @return this builder
             */
            public Builder runtimeClasspath(List<Path> entries) {
                this.runtimeClasspath = List.copyOf(entries);
                return this;
            }

            /**
             * The lowest Java release the application runs on. Required.
             *
             * <p>The generated classes are class files of Java 25, so the engine stands down below that: a
             * service file that names a class the JVM cannot load breaks Logback's initialisation.</p>
             *
             * @param release a Java feature release, such as {@code 25}
             * @return this builder
             */
            public Builder targetRelease(int release) {
                this.targetRelease = release;
                return this;
            }

            /**
             * Whether the class path given here is exactly the one the application runs on, as it is for a
             * packager that fixes the class path at packaging time. {@code false} by default.
             *
             * <p>When it is not, the class path can change after the build: a later test run sees the same
             * output directory, an IDE leaves stale output, a file is mounted into a container image. The
             * generated configurator then checks on every call, at startup and on each logging refresh, where
             * Logback itself would look, that no {@code logback-test.xml} is visible and that the first visible
             * {@code logback.xml} is the file that was compiled, by size and CRC-32, and hands over to Joran
             * otherwise. A closed class path needs neither check: what was surveyed at build time is what
             * runs.</p>
             *
             * @param closed {@code true} only when nothing can be added to or changed on the class path
             * @return this builder
             */
            public Builder closedClassPath(boolean closed) {
                this.closedClassPath = closed;
                return this;
            }

            /**
             * Where the front end jar is unpacked, for the tests. When unset, a temporary directory that is
             * deleted before {@link LogbackPrecompiler#precompile(Request)} returns.
             *
             * @param directory a directory of the caller's, created when it does not exist
             * @return this builder
             */
            Builder workDirectory(Path directory) {
                this.workDirectory = Objects.requireNonNull(directory, "directory");
                return this;
            }

            /**
             * Builds the request.
             *
             * @return the request
             * @throws IllegalStateException if a required input is not set
             */
            public Request build() {
                return new Request(this);
            }
        }
    }

    /**
     * The outcome of one precompilation: its {@link #status()}, one {@link #message()} for the caller to log, the
     * generated {@link #entries()}, and the {@link #warnings()} the caller logs at warning level, which most
     * results do not have.
     *
     * @since 3.2.0
     */
    @Internal
    public static final class Result {

        private final Status status;
        private final String message;
        private final Map<String, byte[]> entries;
        private final List<String> warnings;

        private Result(Status status, String message, Map<String, byte[]> entries, List<String> warnings) {
            this.status = status;
            this.message = message;
            this.entries = Collections.unmodifiableMap(entries);
            this.warnings = List.copyOf(warnings);
        }

        /**
         * What happened.
         *
         * @return the status
         */
        public Status status() {
            return status;
        }

        /**
         * One line for the caller to log: what was compiled, or why nothing was. It is informational for
         * {@link Status#GENERATED} and {@link Status#STOOD_DOWN} and worth a warning for {@link Status#FAILED}.
         * Whatever else deserves a warning is in {@link #warnings()}, never in this line.
         *
         * @return the line
         */
        public String message() {
            return message;
        }

        /**
         * Lines for the caller to log at warning level, each on its own, after {@link #message()}. Each line is
         * complete: it names the file it is about, what it means at run time and what to do instead.
         *
         * <p>Only a {@link Status#GENERATED} result can have any, and most have none. It has one line for each
         * packaged {@code application*} or {@code bootstrap*} file, of the application output or of an entry of
         * the runtime class path, that may enable Micronaut's distributed configuration client
         * ({@code micronaut.config-client.enabled} set to {@code true}): a {@code logger.config} or
         * {@code logback.configurationFile} that distributed configuration supplies is not applied while the
         * generated configurator is registered. A client that is enabled only at run time, by an environment
         * variable, a system property or a file that is not on the class path of the request, cannot be seen and
         * gives no line.</p>
         *
         * @return the lines, unmodifiable, in class path order; empty unless {@link #status()} is
         *         {@link Status#GENERATED}
         */
        public List<String> warnings() {
            return warnings;
        }

        /**
         * The generated entries: the class files and the
         * {@code META-INF/services/ch.qos.logback.classic.spi.Configurator} file, by entry name, in order.
         *
         * <p>The caller writes every entry, by its name, below one directory that is on the application's class
         * path next to its {@code logback.xml}, or adds every entry to one archive. They have to stay together,
         * on the same class loader: the generated configurator calls the classes copied next to it, which are
         * package-private.</p>
         *
         * <p>Removing the output of an earlier build is the caller's job, because only the build tool knows
         * whether a directory is its own. A caller that writes into one of the roots it passes as
         * {@linkplain Request.Builder#applicationOutput(List) application output}, such as a class directory, has
         * to remove that earlier output <em>before</em> it calls {@link LogbackPrecompiler#precompile(Request)}
         * again, not after: the engine neither replaces nor removes it, and the call is {@link Status#FAILED}
         * while it is there. The output is the service file and the classes of the package
         * {@code io.micronaut.aot.logback.generated}.</p>
         *
         * @return the entries, unmodifiable; empty unless {@link #status()} is {@link Status#GENERATED}
         */
        public Map<String, byte[]> entries() {
            return entries;
        }

        private static Result standDown(String reason) {
            return new Result(Status.STOOD_DOWN, NOT_PRECOMPILED + reason + JORAN_AT_STARTUP, Map.of(), List.of());
        }

        private static Result failed(String message) {
            return new Result(Status.FAILED, message, Map.of(), List.of());
        }

        private static Result failure(String what, Throwable cause) {
            return failed(NOT_PRECOMPILED + what + ": " + cause + JORAN_AT_STARTUP);
        }
    }

    /**
     * What a precompilation did.
     *
     * @since 3.2.0
     */
    @Internal
    public enum Status {

        /** A configurator was generated. */
        GENERATED,

        /**
         * Nothing was generated because the result could not be proven to match Joran's, which is expected for
         * many applications; Logback configures itself with Joran at startup.
         */
        STOOD_DOWN,

        /**
         * Nothing was generated because something went wrong: a generated name is already taken, the output of
         * an earlier call is still in the application output, the class path could not be read, or the front end
         * or the emitter failed. Logback configures itself with Joran at startup, unless an earlier output is
         * what is in the way, which then stays in use.
         */
        FAILED
    }

    /**
     * One layer of the class path, as a class loader searches it.
     *
     * @param description     how the message names it: {@code application output}, or the entry's file name or
     *                        path
     * @param source          the class path entry, or {@code null} for the application output
     * @param names           every entry name the layer holds
     * @param reader          reads one entry
     * @param logbackXmlRoots the application roots that hold a {@code logback.xml}, in order; empty for a class
     *                        path entry
     * @param roots           what the layer is made of, in order: every application root, or the one entry
     */
    private record Layer(String description, @Nullable Path source, Collection<String> names, EntryReader reader,
                         List<Path> logbackXmlRoots, List<Root> roots) {

        /**
         * The application output: one layer made of every root, where the first root that holds a name wins.
         * Which roots hold a {@code logback.xml} is kept: where the roots are class path entries of their own,
         * Joran sees every one of them.
         *
         * @param roots directories or jars
         * @return the layer
         * @throws IOException if a root cannot be read
         */
        static Layer application(List<Path> roots) throws IOException {
            Map<String, Root> owners = new LinkedHashMap<>();
            List<Path> logbackXmlRoots = new ArrayList<>();
            List<Root> read = new ArrayList<>();
            for (Path path : roots) {
                Root root = Root.of(path);
                read.add(root);
                for (String name : root.names()) {
                    owners.putIfAbsent(name, root);
                    if (name.equals(LOGBACK_XML)) {
                        logbackXmlRoots.add(path);
                    }
                }
            }
            return new Layer("application output", null, Collections.unmodifiableSet(owners.keySet()),
                    name -> {
                        Root root = owners.get(name);
                        if (root == null) {
                            throw new IOException("the application output has no " + name);
                        }
                        return root.read(name);
                    }, List.copyOf(logbackXmlRoots), List.copyOf(read));
        }

        /**
         * One entry of the runtime class path.
         *
         * @param entry a directory or a jar
         * @return the layer
         * @throws IOException if the entry cannot be read
         */
        static Layer of(Path entry) throws IOException {
            Root root = Root.of(entry);
            String description = root.directory() ? entry.toString() : String.valueOf(entry.getFileName());
            return new Layer(description, entry, root.names(), root::read, List.of(), List.of(root));
        }

        byte[] read(String name) throws IOException {
            return reader.read(name);
        }

        /**
         * The {@code Implementation-Version} of the layer's manifest. Only the two Logback entries are asked, so
         * no other manifest is ever read: not an application root's, and not another dependency's.
         *
         * @return the version, or {@code null} when the layer has no manifest or the manifest no version
         * @throws IOException if the manifest cannot be read
         */
        @Nullable String implementationVersion() throws IOException {
            if (!names.contains(MANIFEST_ENTRY)) {
                return null;
            }
            try {
                return new Manifest(new ByteArrayInputStream(read(MANIFEST_ENTRY))).getMainAttributes()
                        .getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            } catch (IOException e) {
                throw new IOException("the " + MANIFEST_ENTRY + " of " + description + ": " + e.getMessage(), e);
            }
        }
    }

    /**
     * A directory or a jar, read with the JDK only.
     *
     * @param path      where it is
     * @param directory whether it is a directory; anything else is read as a ZIP
     * @param names     the names of its files, without directories
     */
    private record Root(Path path, boolean directory, List<String> names) {

        static Root of(Path path) throws IOException {
            if (Files.isDirectory(path)) {
                List<String> names = new ArrayList<>();
                // Links are followed, as a class loader follows them.
                try (Stream<Path> files = Files.walk(path, FileVisitOption.FOLLOW_LINKS)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(file)) {
                            names.add(path.relativize(file).toString().replace(File.separatorChar, '/'));
                        }
                    }
                }
                Collections.sort(names);
                return new Root(path, true, names);
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                List<String> names = new ArrayList<>(zip.size());
                for (var entries = zip.entries(); entries.hasMoreElements(); ) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.isDirectory()) {
                        names.add(entry.getName());
                    }
                }
                return new Root(path, false, names);
            } catch (ZipException e) {
                throw new ZipException(path + " is neither a directory nor an archive: " + e.getMessage());
            }
        }

        byte[] read(String name) throws IOException {
            if (directory) {
                return Files.readAllBytes(path.resolve(name));
            }
            try (ZipFile zip = new ZipFile(path.toFile())) {
                return LogbackPrecompiler.read(zip, name);
            }
        }
    }

    /** Reads the content of one entry of a layer. */
    @FunctionalInterface
    private interface EntryReader {

        /**
         * Reads one entry.
         *
         * @param name the entry name
         * @return its content
         * @throws IOException if it cannot be read
         */
        byte[] read(String name) throws IOException;
    }

    /**
     * What the generated configurator checks on an open class path. Not a record: it holds an array, and nothing
     * compares it.
     */
    private static final class Guard {

        /** The {@code ClassPathGuard} class, as the front end jar carries it. */
        private final byte[] classBytes;

        /** The size of the compiled {@code logback.xml}. */
        private final long size;

        /** Its CRC-32. */
        private final long crc;

        private Guard(byte[] classBytes, long size, long crc) {
            this.classBytes = classBytes;
            this.size = size;
            this.crc = crc;
        }
    }

    /** One pass over every name of every layer, resolved as a class loader resolves them. */
    private static final class Survey {

        private final List<Layer> layers;
        private int classic = -1;
        private int core = -1;
        private int slf4j = -1;
        private int logbackXml = -1;
        private @Nullable String secondLogbackXml;
        private @Nullable String applicationLogging;
        private @Nullable String unsupportedFile;
        private @Nullable String configurator;

        private Survey(List<Layer> layers) {
            this.layers = layers;
        }

        static Survey of(List<Layer> layers) {
            Survey survey = new Survey(layers);
            for (int i = 0; i < layers.size(); i++) {
                for (String name : layers.get(i).names()) {
                    survey.visit(i, name);
                }
            }
            return survey;
        }

        private void visit(int layer, String name) {
            if (layer == 0 && applicationLogging == null && isLoggingClass(name)) {
                applicationLogging = name;
            }
            if (classic < 0 && name.equals(CLASSIC_MARKER)) {
                classic = layer;
            } else if (core < 0 && name.equals(CORE_MARKER)) {
                core = layer;
            } else if (slf4j < 0 && name.equals(SLF4J_MARKER)) {
                slf4j = layer;
            } else if (name.equals(LOGBACK_XML)) {
                visitLogbackXml(layer);
            } else if (unsupportedFile == null && isUnsupportedFile(name)) {
                unsupportedFile = layers.get(layer).description() + " has " + name;
            } else if (configurator == null && name.equals(SERVICE_ENTRY)) {
                configurator = layers.get(layer).description();
            }
        }

        private void visitLogbackXml(int layer) {
            if (logbackXml < 0) {
                logbackXml = layer;
            } else if (secondLogbackXml == null) {
                secondLogbackXml = layers.get(layer).description();
            }
        }

        private static boolean isLoggingClass(String name) {
            return name.endsWith(".class") && (name.startsWith("ch/qos/logback/") || name.startsWith("org/slf4j/"));
        }

        private static boolean isUnsupportedFile(String name) {
            return name.equals("logback-test.xml") || name.equals("logback.groovy")
                    || VERSIONED_LOGBACK_FILE.matcher(name).matches();
        }

        @Nullable String standDownReason() throws IOException {
            if (applicationLogging != null) {
                return "the application output carries its own Logback or SLF4J class " + applicationLogging;
            }
            if (classic < 0) {
                return "logback-classic is not on the class path";
            }
            if (core < 0) {
                return "logback-core is not on the class path";
            }
            if (slf4j < 0) {
                return "slf4j-api is not on the class path";
            }
            String classicVersion = layers.get(classic).implementationVersion();
            String coreVersion = layers.get(core).implementationVersion();
            if (classicVersion == null || coreVersion == null) {
                return "logback-classic or logback-core declares no Implementation-Version";
            }
            if (!classicVersion.equals(coreVersion)) {
                return "logback-classic " + classicVersion + " and logback-core " + coreVersion + " differ";
            }
            if (!supported(classicVersion)) {
                return "Logback " + classicVersion + " is outside the tested range [" + MINIMUM_VERSION + ", "
                        + VERSION_LIMIT + ")";
            }
            if (logbackXml < 0) {
                return "there is no logback.xml";
            }
            // Joran's default lookup adds a warning for a resource that occurs more than once, and Logback prints
            // its status list at startup when it holds a warning. The generated code does neither. Two roots of
            // the application count as well: on an open class path each is an entry of its own.
            List<Path> roots = layers.get(logbackXml).logbackXmlRoots();
            if (roots.size() > 1) {
                return "the application roots " + roots.get(0) + " and " + roots.get(1)
                        + " both have a logback.xml, which Logback reports at startup";
            }
            if (secondLogbackXml != null) {
                return layers.get(logbackXml).description() + " and " + secondLogbackXml
                        + " both have a logback.xml, which Logback reports at startup";
            }
            if (unsupportedFile != null) {
                return unsupportedFile;
            }
            if (configurator != null) {
                return configurator + " already registers a Logback Configurator (" + SERVICE_ENTRY + ")";
            }
            return null;
        }
    }

    /**
     * Emits the configurator from the front end's description, with the ClassFile API, and verifies every class
     * before anything is returned.
     */
    private static final class Emitter {

        private static final ClassDesc CONFIGURATOR = ClassDesc.of(CONFIGURATOR_CLASS);
        private static final ClassDesc FALLBACK = ClassDesc.of(FALLBACK_CLASS);
        private static final ClassDesc GUARD = ClassDesc.of(GUARD_CLASS);
        private static final ClassDesc CONTEXT_AWARE_BASE = ClassDesc.of("ch.qos.logback.core.spi.ContextAwareBase");
        private static final ClassDesc CONTEXT_AWARE = ClassDesc.of("ch.qos.logback.core.spi.ContextAware");
        private static final ClassDesc CONTEXT = ClassDesc.of("ch.qos.logback.core.Context");
        private static final ClassDesc LIFE_CYCLE = ClassDesc.of("ch.qos.logback.core.spi.LifeCycle");
        private static final ClassDesc APPENDER = ClassDesc.of("ch.qos.logback.core.Appender");
        private static final ClassDesc CONTEXT_UTIL = ClassDesc.of("ch.qos.logback.core.util.ContextUtil");
        private static final ClassDesc STATUS = ClassDesc.of("ch.qos.logback.core.status.Status");
        private static final ClassDesc STATUS_MANAGER = ClassDesc.of("ch.qos.logback.core.status.StatusManager");
        private static final ClassDesc WARN_STATUS = ClassDesc.of("ch.qos.logback.core.status.WarnStatus");
        private static final ClassDesc LOGGER_CONTEXT = ClassDesc.of("ch.qos.logback.classic.LoggerContext");
        private static final ClassDesc LOGGER = ClassDesc.of("ch.qos.logback.classic.Logger");
        private static final ClassDesc LEVEL = ClassDesc.of("ch.qos.logback.classic.Level");
        private static final ClassDesc CONFIGURATOR_INTERFACE = ClassDesc.of("ch.qos.logback.classic.spi.Configurator");
        private static final ClassDesc EXECUTION_STATUS =
                ClassDesc.of("ch.qos.logback.classic.spi.Configurator$ExecutionStatus");

        private static final MethodTypeDesc CONFIGURE = MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT);
        private static final MethodTypeDesc LOCATION =
                MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT, ConstantDescs.CD_String);
        private static final MethodTypeDesc UNCHANGED =
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_long, ConstantDescs.CD_long);
        private static final MethodTypeDesc STRING_TO_STRING =
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String);
        private static final MethodTypeDesc TAKES_CONTEXT = MethodTypeDesc.of(ConstantDescs.CD_void, CONTEXT);
        private static final MethodTypeDesc TAKES_STRING =
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String);
        private static final MethodTypeDesc TAKES_APPENDER = MethodTypeDesc.of(ConstantDescs.CD_void, APPENDER);

        private static final String CONFIGURED_ONCE = "configuredOnce";

        /** Slots of the configure method: this, the context, again, the visible location, the current logger. */
        private static final int CONTEXT_SLOT = 1;
        private static final int AGAIN_SLOT = 2;
        private static final int LOCATION_SLOT = 3;
        private static final int LOGGER_SLOT = 4;
        private static final int ENCODER_SLOT = 5;
        private static final int FIRST_APPENDER_SLOT = 6;

        private Emitter() {
        }

        static Map<String, byte[]> emit(Map<String, Object> description, byte[] fallback, @Nullable Guard guard,
                                        ClassLoader loader) {
            // The generated class is not packaged anywhere yet, so its supertype is declared here; everything else
            // is parsed from the isolated loader, whose platform parent resolves the JDK. Deliberately not
            // defaultResolver(): it would also see the build tool's own class path, and could resolve a Logback
            // supertype from a copy there instead of the application's.
            ClassHierarchyResolver resolver = ClassHierarchyResolver
                    .of(List.of(), Map.of(CONFIGURATOR, CONTEXT_AWARE_BASE))
                    .orElse(ClassHierarchyResolver.ofResourceParsing(loader))
                    .cached();
            ClassFile classFile = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
            byte[] configurator = classFile.build(CONFIGURATOR,
                    new ConfiguratorClass(operations(description), guard));
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put(CONFIGURATOR_ENTRY, configurator);
            entries.put(FALLBACK_ENTRY, fallback);
            if (guard != null) {
                entries.put(GUARD_ENTRY, guard.classBytes);
            }
            for (byte[] bytes : entries.values()) {
                var errors = classFile.verify(bytes);
                if (!errors.isEmpty()) {
                    throw new IllegalStateException("a generated class does not verify: " + errors.get(0));
                }
            }
            entries.put(SERVICE_ENTRY, (CONFIGURATOR_CLASS + "\n").getBytes(StandardCharsets.UTF_8));
            return entries;
        }

        @SuppressWarnings("unchecked")
        private static List<Map<String, Object>> operations(Map<String, Object> description) {
            return (List<Map<String, Object>>) description.get("operations");
        }

        /**
         * The class: a final ContextAwareBase implementing Configurator, with one static flag.
         *
         * @param operations what the configuration does, in Joran's order
         * @param guard      what to check on an open class path, or {@code null} on a closed one
         */
        private record ConfiguratorClass(List<Map<String, Object>> operations, @Nullable Guard guard)
                implements Consumer<ClassBuilder> {

            @Override
            public void accept(ClassBuilder builder) {
                builder.withVersion(ClassFile.JAVA_25_VERSION, 0);
                builder.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER);
                builder.withSuperclass(CONTEXT_AWARE_BASE);
                builder.withInterfaceSymbols(CONFIGURATOR_INTERFACE);
                builder.withField(CONFIGURED_ONCE, ConstantDescs.CD_boolean,
                        ClassFile.ACC_PRIVATE | ClassFile.ACC_STATIC | ClassFile.ACC_VOLATILE);
                builder.withMethodBody(ConstantDescs.INIT_NAME, ConstantDescs.MTD_void, ClassFile.ACC_PUBLIC,
                        code -> code.aload(0)
                                .invokespecial(CONTEXT_AWARE_BASE, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                                .return_());
                builder.withMethodBody("configure", CONFIGURE, ClassFile.ACC_PUBLIC,
                        new Configure(operations, guard));
            }
        }

        /**
         * The body of {@code configure}: the runtime rules, then the literal configuration.
         *
         * @param operations what the configuration does, in Joran's order
         * @param guard      what to check on an open class path, or {@code null} on a closed one
         */
        private record Configure(List<Map<String, Object>> operations, @Nullable Guard guard)
                implements Consumer<CodeBuilder> {

            @Override
            public void accept(CodeBuilder code) {
                Label afterProperty = code.newLabel();
                Label notAgain = code.newLabel();
                Label useLocation = code.newLabel();
                Label joran = code.newLabel();
                Label literal = code.newLabel();

                // Keeps whether an earlier call configured, in the again slot, then records this call.
                code.getstatic(CONFIGURATOR, CONFIGURED_ONCE, ConstantDescs.CD_boolean).istore(AGAIN_SLOT)
                        .iconst_1().putstatic(CONFIGURATOR, CONFIGURED_ONCE, ConstantDescs.CD_boolean);

                // Rule 1: -Dlogback.configurationFile is Logback's own lookup.
                systemProperty(code, "logback.configurationFile").ifnull(afterProperty);
                defaultLookup(code);
                code.labelBinding(afterProperty);

                // Rule 2: again, and a location is visible, in the order Micronaut resolves one:
                // logback.configurationFile before logger.config, a system property before the environment.
                // Rule 1 took the system property logback.configurationFile. Micronaut looks that name up as it
                // is written, so the only environment variable that sets it is one of exactly that name, which
                // a container can declare; LOGBACK_CONFIGURATIONFILE does not, and is deliberately not read.
                code.iload(AGAIN_SLOT).ifeq(notAgain);
                environment(code, "logback.configurationFile").astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnonnull(useLocation);
                systemProperty(code, LOGGER_CONFIG_PROPERTY).astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnonnull(useLocation);
                environment(code, "LOGGER_CONFIG").astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnonnull(useLocation);
                environment(code, LOGGER_CONFIG_PROPERTY).astore(LOCATION_SLOT)
                        .aload(LOCATION_SLOT).ifnull(notAgain);
                code.labelBinding(useLocation);
                code.aload(CONTEXT_SLOT).aload(LOCATION_SLOT)
                        .invokestatic(FALLBACK, "location", LOCATION).areturn();
                code.labelBinding(notAgain);

                // Rule 3: Logback's status output was asked for, or the runtime opt-out is set.
                systemProperty(code, "logback.debug").ifnonnull(joran);
                systemProperty(code, "logback.statusListenerClass").ifnonnull(joran);
                code.ldc("false");
                systemProperty(code, OPT_OUT_PROPERTY)
                        .invokevirtual(ConstantDescs.CD_String, "equals",
                                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
                if (guard == null) {
                    code.ifeq(literal);
                } else {
                    code.ifne(joran);
                    // Rule 4, on an open class path only: what is visible is still what was compiled.
                    code.loadConstant(guard.size).loadConstant(guard.crc)
                            .invokestatic(GUARD, "unchanged", UNCHANGED).ifne(literal);
                }
                code.labelBinding(joran);
                defaultLookup(code);

                // Rule 5: the literal configuration, starting with what ConfigurationModelHandler does.
                code.labelBinding(literal);
                code.aload(CONTEXT_SLOT).iconst_0()
                        .invokevirtual(LOGGER_CONTEXT, "setPackagingDataEnabled",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
                code.new_(CONTEXT_UTIL).dup().aload(CONTEXT_SLOT)
                        .invokespecial(CONTEXT_UTIL, ConstantDescs.INIT_NAME, TAKES_CONTEXT)
                        .aload(CONTEXT_SLOT)
                        .invokevirtual(LOGGER_CONTEXT, "getFrameworkPackages",
                                MethodTypeDesc.of(ConstantDescs.CD_List))
                        .invokevirtual(CONTEXT_UTIL, "addGroovyPackages",
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_List));
                Map<String, Integer> appenderSlots = new HashMap<>();
                for (Map<String, Object> operation : operations) {
                    switch ((String) operation.get("op")) {
                        case "appender" -> appender(code, operation, appenderSlots);
                        case "skip" -> skipped(code, (String) operation.get("name"));
                        case "logger" -> logger(code, operation, appenderSlots);
                        default -> throw new IllegalStateException("unknown operation " + operation.get("op"));
                    }
                }
                code.getstatic(EXECUTION_STATUS, "DO_NOT_INVOKE_NEXT_IF_ANY", EXECUTION_STATUS).areturn();
            }

            private static CodeBuilder systemProperty(CodeBuilder code, String name) {
                return code.ldc(name).invokestatic(ClassDesc.of("java.lang.System"), "getProperty", STRING_TO_STRING);
            }

            private static CodeBuilder environment(CodeBuilder code, String name) {
                return code.ldc(name).invokestatic(ClassDesc.of("java.lang.System"), "getenv", STRING_TO_STRING);
            }

            private static void defaultLookup(CodeBuilder code) {
                code.aload(CONTEXT_SLOT)
                        .invokestatic(FALLBACK, "defaultLookup", MethodTypeDesc.of(EXECUTION_STATUS, LOGGER_CONTEXT))
                        .areturn();
            }

            /**
             * What {@code AppenderModelHandler} and {@code ImplicitModelHandler} do: create the appender, set its
             * context and name, apply its nested elements in document order, and start it.
             */
            @SuppressWarnings("unchecked")
            private static void appender(CodeBuilder code, Map<String, Object> operation, Map<String, Integer> slots) {
                ClassDesc type = ClassDesc.of((String) operation.get("className"));
                int slot = FIRST_APPENDER_SLOT + slots.size();
                slots.put((String) operation.get("name"), slot);
                code.new_(type).dup().invokespecial(type, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .astore(slot);
                code.aload(slot).aload(CONTEXT_SLOT).invokeinterface(APPENDER, "setContext", TAKES_CONTEXT);
                code.aload(slot).ldc((String) operation.get("name"))
                        .invokeinterface(APPENDER, "setName", TAKES_STRING);
                for (Map<String, Object> step : (List<Map<String, Object>>) operation.get("steps")) {
                    if ("encoder".equals(step.get("step"))) {
                        encoder(code, type, slot, step);
                        continue;
                    }
                    code.aload(slot);
                    Object value = step.get("value");
                    switch (value) {
                        case String text -> code.ldc(text);
                        case Boolean flag -> code.loadConstant(Boolean.TRUE.equals(flag) ? 1 : 0);
                        case null, default -> code.loadConstant((Integer) value);
                    }
                    setter(code, type, (String) step.get("method"), (String) step.get("descriptor"));
                }
                start(code, operation, slot);
            }

            /**
             * A plain {@code PatternLayoutEncoder}, as Joran builds one: context, pattern, parent, start, and only
             * then the appender's encoder property.
             */
            private static void encoder(CodeBuilder code, ClassDesc appender, int appenderSlot,
                                        Map<String, Object> step) {
                ClassDesc type = ClassDesc.of((String) step.get("className"));
                code.new_(type).dup().invokespecial(type, ConstantDescs.INIT_NAME, ConstantDescs.MTD_void)
                        .astore(ENCODER_SLOT);
                code.aload(ENCODER_SLOT).aload(CONTEXT_SLOT)
                        .invokeinterface(CONTEXT_AWARE, "setContext", TAKES_CONTEXT);
                code.aload(ENCODER_SLOT).ldc((String) step.get("pattern"));
                setter(code, type, (String) step.get("patternMethod"), (String) step.get("patternDescriptor"));
                if (step.get("parentMethod") != null) {
                    code.aload(ENCODER_SLOT).aload(appenderSlot);
                    setter(code, type, (String) step.get("parentMethod"), (String) step.get("parentDescriptor"));
                }
                start(code, step, ENCODER_SLOT);
                code.aload(appenderSlot).aload(ENCODER_SLOT);
                setter(code, appender, (String) step.get("method"), (String) step.get("descriptor"));
            }

            /** Starts what a slot holds when its description asks for it. */
            private static void start(CodeBuilder code, Map<String, Object> description, int slot) {
                if (Boolean.TRUE.equals(description.get("start"))) {
                    code.aload(slot).invokeinterface(LIFE_CYCLE, "start", ConstantDescs.MTD_void);
                }
            }

            private static void setter(CodeBuilder code, ClassDesc owner, String name, String descriptor) {
                MethodTypeDesc type = MethodTypeDesc.ofDescriptor(descriptor);
                code.invokevirtual(owner, name, type);
                TypeKind result = TypeKind.from(type.returnType());
                if (result.slotSize() == 2) {
                    code.pop2();
                } else if (result.slotSize() == 1) {
                    code.pop();
                }
            }

            /** The warning {@code AppenderModelHandler} adds for an appender nothing references. */
            private static void skipped(CodeBuilder code, String name) {
                code.aload(CONTEXT_SLOT)
                        .invokevirtual(LOGGER_CONTEXT, "getStatusManager", MethodTypeDesc.of(STATUS_MANAGER))
                        .new_(WARN_STATUS).dup()
                        .ldc("Appender named [" + name + "] not referenced. Skipping further processing.")
                        .aload(0)
                        .invokespecial(WARN_STATUS, ConstantDescs.INIT_NAME,
                                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String,
                                        ConstantDescs.CD_Object))
                        .invokeinterface(STATUS_MANAGER, "add", MethodTypeDesc.of(ConstantDescs.CD_void, STATUS));
            }

            /**
             * What {@code LoggerModelHandler} or {@code RootLoggerModelHandler} and then
             * {@code AppenderRefModelHandler} do: level, additivity, then each referenced appender in order.
             */
            @SuppressWarnings("unchecked")
            private static void logger(CodeBuilder code, Map<String, Object> operation, Map<String, Integer> slots) {
                code.aload(CONTEXT_SLOT).ldc((String) operation.get("name"))
                        .invokevirtual(LOGGER_CONTEXT, "getLogger", MethodTypeDesc.of(LOGGER, ConstantDescs.CD_String))
                        .astore(LOGGER_SLOT);
                Object level = operation.get("level");
                if (level != null) {
                    code.aload(LOGGER_SLOT);
                    if ("NULL".equals(level)) {
                        code.aconst_null();
                    } else {
                        code.getstatic(LEVEL, (String) level, LEVEL);
                    }
                    code.invokevirtual(LOGGER, "setLevel", MethodTypeDesc.of(ConstantDescs.CD_void, LEVEL));
                }
                Object additivity = operation.get("additivity");
                if (additivity != null) {
                    code.aload(LOGGER_SLOT).loadConstant(Boolean.TRUE.equals(additivity) ? 1 : 0)
                            .invokevirtual(LOGGER, "setAdditive",
                                    MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_boolean));
                }
                for (String reference : (List<String>) operation.get("appenders")) {
                    Integer slot = slots.get(reference);
                    if (slot == null) {
                        throw new IllegalStateException("the logger " + operation.get("name")
                                + " references " + reference + " before it is created");
                    }
                    code.aload(LOGGER_SLOT).aload(slot).invokevirtual(LOGGER, "addAppender", TAKES_APPENDER);
                }
            }
        }
    }
}
