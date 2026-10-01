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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.status.Status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeSet;

/**
 * The differential harness of the Logback precompiler: it configures one {@link LoggerContext} with Joran and one
 * with a generated {@link Configurator}, and describes both in a form that has to be identical.
 *
 * <p>It uses no type of the precompiler. The generated side is a directory holding the class files and the
 * {@code META-INF/services/ch.qos.logback.classic.spi.Configurator} file of any engine, found through
 * {@link ServiceLoader} in a class loader that sees Logback, exactly as Logback finds a configurator.</p>
 */
final class LogbackDifferential {

    /** Logger names every comparison describes, besides every logger either configuration created. */
    static final List<String> LOGGER_NAMES = List.of(Logger.ROOT_LOGGER_NAME, "com", "com.example",
            "com.example.Service", "com.example.quiet.Thing", "com.example.silent", "io.micronaut",
            "io.micronaut.runtime.Micronaut", "io.netty.channel", "org.hibernate.SQL", "x.y.z");

    private static final Instant EVENT_TIME = Instant.parse("2026-02-03T04:05:06.789Z");

    private static final List<Level> LEVELS = List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN,
            Level.ERROR);

    /** One exception for every emission, so that both contexts print the same stack trace. */
    private static final IllegalStateException FAILURE = failure();

    private LogbackDifferential() {
    }

    private static IllegalStateException failure() {
        IllegalStateException failure = new IllegalStateException("outer failure", new IOException("the cause"));
        failure.addSuppressed(new UnsupportedOperationException("suppressed"));
        return failure;
    }

    /**
     * Configures a new context with Joran from a file, as Logback's default configurator does.
     *
     * @param xml the configuration file
     * @return the configured context
     */
    static LoggerContext joran(Path xml) {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        try {
            configurator.doConfigure(xml.toUri().toURL());
        } catch (JoranException | IOException e) {
            throw new IllegalStateException("Joran could not read " + xml, e);
        }
        return context;
    }

    /**
     * A class loader that sees the generated classes and service file, and Logback through its parent.
     *
     * @param generated the directory holding them
     * @return the loader, which the caller closes
     */
    static URLClassLoader loader(Path generated) {
        try {
            return new URLClassLoader(new URL[] {generated.toUri().toURL()}, LogbackDifferential.class.getClassLoader());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Calls the configurator a loader registers on a context, as Logback's {@code ContextInitializer} and
     * Micronaut's {@code LogbackUtils} do: a new instance, its context set, then {@code configure}.
     *
     * @param loader  the loader of the generated classes
     * @param context the context to configure
     * @return what the configurator returned
     */
    static Configurator.ExecutionStatus configure(ClassLoader loader, LoggerContext context) {
        Iterator<Configurator> configurators = ServiceLoader.load(Configurator.class, loader).iterator();
        if (!configurators.hasNext()) {
            throw new AssertionError("no Configurator service is registered");
        }
        Configurator configurator = configurators.next();
        if (configurator.getClass().getClassLoader() != loader) {
            throw new AssertionError(configurator.getClass() + " does not come from the generated classes");
        }
        configurator.setContext(context);
        return configurator.configure(context);
    }

    /**
     * Describes the logger tree of a context: level, effective level, additivity and appenders of every logger in
     * {@link #LOGGER_NAMES} and in {@code others}, each appender with its type, started flag and encoder, plus
     * the context's framework packages, packaging-data flag and every status of level {@code WARN} or above.
     *
     * @param context the context
     * @param others  further contexts whose loggers are described too, so two descriptions cover the same names
     * @return the description, one line per item
     */
    static String describe(LoggerContext context, LoggerContext... others) {
        TreeSet<String> names = new TreeSet<>(LOGGER_NAMES);
        for (LoggerContext each : others) {
            for (Logger logger : each.getLoggerList()) {
                names.add(logger.getName());
            }
        }
        for (Logger logger : context.getLoggerList()) {
            names.add(logger.getName());
        }
        // Every ancestor too: describing a logger creates its ancestors, which the next description would see.
        for (String name : List.copyOf(names)) {
            for (int i = 0; i < name.length(); i++) {
                if (name.charAt(i) == '.' || name.charAt(i) == '$') {
                    names.add(name.substring(0, i));
                }
            }
        }
        StringBuilder description = new StringBuilder();
        for (String name : names) {
            Logger logger = context.getLogger(name);
            description.append(name).append(" level=").append(logger.getLevel())
                    .append(" effective=").append(logger.getEffectiveLevel())
                    .append(" additive=").append(logger.isAdditive()).append(" appenders=[");
            for (Iterator<Appender<ILoggingEvent>> it = logger.iteratorForAppenders(); it.hasNext(); ) {
                description.append(appender(it.next())).append(it.hasNext() ? ", " : "");
            }
            description.append("]\n");
        }
        description.append("frameworkPackages=").append(context.getFrameworkPackages()).append('\n');
        description.append("packagingData=").append(context.isPackagingDataEnabled()).append('\n');
        for (Status status : context.getStatusManager().getCopyOfStatusList()) {
            if (status.getLevel() >= Status.WARN) {
                description.append("status ").append(status.getLevel()).append(' ').append(status.getMessage())
                        .append('\n');
            }
        }
        return description.toString();
    }

    private static String appender(Appender<ILoggingEvent> appender) {
        StringBuilder text = new StringBuilder(appender.getName()).append(':')
                .append(appender.getClass().getName()).append(":started=").append(appender.isStarted());
        if (appender instanceof OutputStreamAppender<ILoggingEvent> stream) {
            text.append(":encoder=").append(stream.getEncoder() == null ? null : stream.getEncoder().getClass().getName());
            if (stream.getEncoder() instanceof PatternLayoutEncoder encoder) {
                text.append(":pattern=").append(encoder.getPattern()).append(":encoderStarted=")
                        .append(encoder.isStarted());
            }
        }
        return text.toString();
    }

    /**
     * Sends the same events through every logger of a context, at every level the logger is enabled for, and
     * returns what reached standard output and standard error. The events are fixed: time, thread, caller data and
     * MDC, with arguments, multi-line and non-ASCII text, and an exception with a cause and a suppressed exception.
     * The context is stopped afterwards, so file appenders have flushed and closed.
     *
     * @param context the context
     * @return standard output, then {@code "--- stderr ---"}, then standard error
     */
    static String emit(LoggerContext context) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        Map<String, String> mdc = new LinkedHashMap<>();
        mdc.put("requestId", "42");
        mdc.put("user", "zoë");
        List<Object[]> events = new ArrayList<>();
        events.add(new Object[] {"plain message", null, null});
        events.add(new Object[] {"with {} and {}", new Object[] {"one", 2}, null});
        events.add(new Object[] {"first line\nsecond line", null, null});
        events.add(new Object[] {"a secret, héllo wörld ☃ 雪", null, null});
        events.add(new Object[] {"failed", null, FAILURE});
        // The capturing streams are closed only after the originals are restored.
        try (PrintStream capturingOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
             PrintStream capturingErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8)) {
            try {
                System.setOut(capturingOut);
                System.setErr(capturingErr);
                for (String name : LOGGER_NAMES) {
                    Logger logger = context.getLogger(name);
                    for (Level level : LEVELS) {
                        if (!logger.isEnabledFor(level)) {
                            continue;
                        }
                        for (Object[] template : events) {
                            LoggingEvent event = new LoggingEvent(Logger.FQCN, logger, level, (String) template[0],
                                    (Throwable) template[2], (Object[]) template[1]);
                            event.setInstant(EVENT_TIME);
                            event.setThreadName("differential-thread");
                            event.setMDCPropertyMap(mdc);
                            event.setCallerData(new StackTraceElement[] {
                                new StackTraceElement("com.example.Caller", "call", "Caller.java", 42)
                            });
                            logger.callAppenders(event);
                        }
                    }
                }
            } finally {
                System.setOut(out);
                System.setErr(err);
                context.stop();
            }
        }
        return capturedOut.toString(StandardCharsets.UTF_8) + "--- stderr ---\n"
                + capturedErr.toString(StandardCharsets.UTF_8);
    }

    /**
     * Reads a file an appender wrote, or says that it does not exist.
     *
     * @param file the file
     * @return its content
     */
    static String read(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "(no file)";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
