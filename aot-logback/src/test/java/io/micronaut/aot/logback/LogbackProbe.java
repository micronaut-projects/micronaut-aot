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

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the tests run on a class path of their own, where Logback, the generated classes and the application's
 * resources are what the class loader that defines Logback sees: in an {@link IsolatedClassPath}, or as the main
 * class of a forked JVM. It takes and returns {@code java.base} types only, so that a test in another class loader
 * can call it reflectively, and uses nothing but Logback and {@link LogbackDifferential}.
 */
public final class LogbackProbe {

    /** Starts the description of one configurator call in what {@link #main(String[])} prints. */
    public static final String CALL_MARKER = "@@call ";

    /** Ends what {@link #main(String[])} prints. */
    public static final String END_MARKER = "@@end";

    private LogbackProbe() {
    }

    /**
     * Calls the registered configurator on one context, once or several times with a reset in between as
     * Micronaut's logging refresh does.
     *
     * @param calls how many calls
     * @return for every call, the returned status, a line break and the description of the context after it
     */
    public static String[] configure(int calls) {
        return configure(LogbackProbe.class.getClassLoader(), calls);
    }

    /**
     * Calls the registered configurator on one context, replaces a file, and calls it again after a reset: what
     * Micronaut's logging refresh does in an application whose {@code logback.xml} was edited while it runs.
     *
     * @param file    the file to replace after the first call
     * @param content its new content
     * @return for both calls, the returned status, a line break and the description of the context after it
     */
    public static String[] configureChangeAndConfigureAgain(String file, String content) {
        ClassLoader generated = LogbackProbe.class.getClassLoader();
        LoggerContext context = new LoggerContext();
        String[] descriptions = new String[2];
        descriptions[0] = LogbackDifferential.configure(generated, context) + "\n"
                + LogbackDifferential.describe(context);
        try {
            Files.writeString(Path.of(file), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        context.reset();
        descriptions[1] = LogbackDifferential.configure(generated, context) + "\n"
                + LogbackDifferential.describe(context);
        context.stop();
        return descriptions;
    }

    /**
     * Runs Logback's own default lookup on a new context, which is what happens without a configurator.
     *
     * @return the returned status, a line break and the description of the context
     */
    public static String joranDefault() {
        LoggerContext context = new LoggerContext();
        DefaultJoranConfigurator configurator = new DefaultJoranConfigurator();
        configurator.setContext(context);
        Configurator.ExecutionStatus status = configurator.configure(context);
        String description = status + "\n" + LogbackDifferential.describe(context);
        context.stop();
        return description;
    }

    /**
     * Describes the context Joran builds from a file and the one the registered configurator builds.
     *
     * @param xml the configuration file
     * @return the status the configurator returned, Joran's description and the configurator's
     */
    public static String[] trees(String xml) {
        return trees(LogbackProbe.class.getClassLoader(), Path.of(xml));
    }

    /**
     * Sends the fixed events through the context Joran builds from a file and through the one the registered
     * configurator builds, one context at a time: a file appender of each writes the same file.
     *
     * @param xml     the configuration file
     * @param logFile the file an appender of the configuration may write
     * @return what Joran's context wrote and what the configurator's wrote
     */
    public static String[] outputs(String xml, String logFile) {
        return outputs(LogbackProbe.class.getClassLoader(), Path.of(xml), Path.of(logFile));
    }

    /**
     * Prints what {@link #configure(int)} returns, each call after a {@link #CALL_MARKER} line that ends with its
     * status, and an {@link #END_MARKER} line.
     *
     * @param args the number of calls
     */
    public static void main(String[] args) {
        StringBuilder output = new StringBuilder();
        for (String call : configure(Integer.parseInt(args[0]))) {
            output.append(CALL_MARKER).append(call);
        }
        System.out.print(output.append(END_MARKER).append('\n'));
    }

    static String[] configure(ClassLoader generated, int calls) {
        LoggerContext context = new LoggerContext();
        String[] descriptions = new String[calls];
        for (int i = 0; i < calls; i++) {
            if (i > 0) {
                context.reset();
            }
            Configurator.ExecutionStatus status = LogbackDifferential.configure(generated, context);
            descriptions[i] = status + "\n" + LogbackDifferential.describe(context);
        }
        context.stop();
        return descriptions;
    }

    static String[] trees(ClassLoader generated, Path xml) {
        // The tree, the appenders, their encoders and every warning, with both contexts alive.
        LoggerContext joran = LogbackDifferential.joran(xml);
        LoggerContext configured = new LoggerContext();
        Configurator.ExecutionStatus status = LogbackDifferential.configure(generated, configured);
        String expected = LogbackDifferential.describe(joran, configured);
        String actual = LogbackDifferential.describe(configured, joran);
        joran.stop();
        configured.stop();
        return new String[] {status.toString(), expected, actual};
    }

    static String[] outputs(ClassLoader generated, Path xml, Path logFile) {
        try {
            Files.deleteIfExists(logFile);
            String joran = LogbackDifferential.emit(LogbackDifferential.joran(xml)) + LogbackDifferential.read(logFile);
            Files.deleteIfExists(logFile);
            LoggerContext configured = new LoggerContext();
            LogbackDifferential.configure(generated, configured);
            return new String[] {joran, LogbackDifferential.emit(configured) + LogbackDifferential.read(logFile)};
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
