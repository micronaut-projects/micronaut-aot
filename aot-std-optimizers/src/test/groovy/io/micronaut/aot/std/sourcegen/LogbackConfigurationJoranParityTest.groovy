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
package io.micronaut.aot.std.sourcegen

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.Configurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.Appender
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.spi.AppenderAttachable
import ch.qos.logback.core.status.OnConsoleStatusListener
import groovy.transform.CompileStatic
import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec
import spock.util.environment.RestoreSystemProperties

import java.nio.charset.StandardCharsets

/**
 * Configures a logger context with Joran and another one with the generated configurator,
 * from the same file, then compares their state and their output for a fixed set of events.
 */
class LogbackConfigurationJoranParityTest extends AbstractSourceGeneratorSpec {
    private static final long TIMESTAMP = 1_700_000_000_000L
    private static final List<Level> LEVELS = [Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR]
    private static final String UNUSED_LOG = 'build/logback-unused/unused.log'

    String configFileName

    @Override
    AOTCodeGenerator newGenerator() {
        new TestLogbackConfigurationSourceGenerator()
    }

    def "configures the same logger context as Joran from #fixture"() {
        given:
        def configurator = compileConfigurator(fixture)

        when:
        def joran = run(files) { LoggerContext context -> configureWithJoran(context, fixture) }
        def generated = run(files) { LoggerContext context -> configureWith(configurator, context) }

        then:
        generated.status == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
        generated.state == joran.state
        generated.output == joran.output

        cleanup:
        files.each { new File(it).delete() }

        where:
        fixture              | files
        'logback-test1.xml'  | []
        'logback-test2.xml'  | ['/tmp/logback.log']
        'logback-test3.xml'  | ['/tmp/logback.log']
        'logback-test4.xml'  | []
        'logback-test5.xml'  | []
        'logback-test6.xml'  | []
        'logback-test7.xml'  | [UNUSED_LOG]
        'logback-test8.xml'  | []
        'logback-launch.xml' | []
    }

    def "attaches nested appender refs, resolves inherited levels and skips unreferenced appenders"() {
        given:
        def configurator = compileConfigurator('logback-test7.xml')

        when:
        def result = run([UNUSED_LOG]) { LoggerContext context -> configureWith(configurator, context) }
        Map<String, Map<String, Object>> loggers = result.state.loggers
        List<Map<String, Object>> rootAppenders = loggers[Logger.ROOT_LOGGER_NAME].appenders

        then:
        rootAppenders*.name == ['ASYNC', 'ERR']
        rootAppenders[0].started
        rootAppenders[0].appenders*.name == ['STDOUT']
        loggers['com.example.a.b'].level == null
        loggers['com.example.a.b'].effectiveLevel == 'WARN'
        loggers['com.example.c'].level == 'WARN'
        result.output.files[UNUSED_LOG] == null
        result.output.out.contains('OUT INFO com.example.x - event')
        result.output.err.contains('ERR INFO com.example.x - event')
    }

    def "applies encoder properties to the parent appender"() {
        given:
        def configurator = compileConfigurator('logback-test8.xml')

        when:
        def result = run([]) { LoggerContext context -> configureWith(configurator, context) }

        then:
        result.state.loggers[Logger.ROOT_LOGGER_NAME].appenders[0].immediateFlush == false
        result.state.packagingData
    }

    def "resets packaging data like Joran when the context is reconfigured"() {
        given:
        def configurator = compileConfigurator('logback-test1.xml')

        when:
        def joran = run([]) { LoggerContext context ->
            context.packagingDataEnabled = true
            configureWithJoran(context, 'logback-test1.xml')
        }
        def generated = run([]) { LoggerContext context ->
            context.packagingDataEnabled = true
            configureWith(configurator, context)
        }

        then:
        !joran.state.packagingData
        generated.state == joran.state
    }

    @RestoreSystemProperties
    def "delegates to Joran when logback.configurationFile is set"() {
        given:
        def configurator = compileConfigurator('logback-test1.xml')
        System.setProperty('logback.configurationFile', getClass().classLoader.getResource('logback-test4.xml').toExternalForm())

        when:
        def joran = run([]) { LoggerContext context -> configureWithJoran(context, 'logback-test4.xml') }
        def generated = run([]) { LoggerContext context -> configureWith(configurator, context) }

        then:
        generated.status == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
        generated.state == joran.state
        generated.output == joran.output
    }

    @RestoreSystemProperties
    def "applies the generated configuration when logback.configurationFile cannot be found"() {
        given: 'Joran would fall back to logback-test.xml, then to logback.xml, which the optimizer removes'
        assert getClass().classLoader.getResource('logback-test.xml') == null
        assert getClass().classLoader.getResource('logback.xml') == null
        def configurator = compileConfigurator('logback-test7.xml')
        System.setProperty('logback.configurationFile', 'build/logback-missing/logback.xml')

        when:
        def joran = run([UNUSED_LOG]) { LoggerContext context -> configureWithJoran(context, 'logback-test7.xml') }
        def generated = run([UNUSED_LOG]) { LoggerContext context -> configureWith(configurator, context) }

        then:
        generated.status == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY
        generated.state == joran.state
        generated.output == joran.output
    }

    @RestoreSystemProperties
    def "registers a console status listener like Joran (#fixture, logback.debug=#debug)"() {
        given:
        def configurator = compileConfigurator(fixture)
        if (debug != null) {
            System.setProperty('logback.debug', debug)
        }

        when:
        def joran = run([]) { LoggerContext context -> configureWithJoran(context, fixture) }
        def generated = run([]) { LoggerContext context -> configureWith(configurator, context) }

        then:
        generated.state == joran.state
        generated.output == joran.output
        (OnConsoleStatusListener.name in generated.state.statusListeners) == console

        where:
        fixture             | debug   | console
        'logback-test4.xml' | 'true'  | true
        'logback-debug.xml' | null    | true
        'logback-debug.xml' | 'false' | false
    }

    private Class<?> compileConfigurator(String fixture) {
        configFileName = fixture
        generate()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                containingSources("implements Configurator")
            }
            compiles()
        }
        def loader = new URLClassLoader([testDirectory.resolve("compiled").toUri().toURL()] as URL[], getClass().classLoader)
        loader.loadClass("${packageName}.StaticLogbackConfiguration")
    }

    private static Configurator.ExecutionStatus configureWithJoran(LoggerContext context, String fixture) {
        def configurator = new JoranConfigurator()
        configurator.context = context
        configurator.doConfigure(LogbackConfigurationJoranParityTest.classLoader.getResource(fixture))
        null
    }

    private static Configurator.ExecutionStatus configureWith(Class<?> configuratorClass, LoggerContext context) {
        def configurator = (Configurator) configuratorClass.getDeclaredConstructor().newInstance()
        configurator.context = context
        configurator.configure(context)
    }

    /**
     * Configures a new logger context, takes a snapshot of it, logs a fixed set of events and
     * captures what the appenders write to the console and to the given files.
     */
    private static Map<String, Object> run(List<String> files, Closure<Configurator.ExecutionStatus> configure) {
        files.each { new File(it).delete() }
        def context = new LoggerContext()
        context.MDCAdapter = new LogbackMDCAdapter()
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()
        def originalOut = System.out
        def originalErr = System.err
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8))
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8))
        Configurator.ExecutionStatus status
        Map<String, Object> state
        try {
            status = configure(context)
            state = snapshot(context)
            logEvents(context)
        } finally {
            context.stop()
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        [
            status: status,
            state : state,
            output: [
                out  : withoutStatuses(out.toString(StandardCharsets.UTF_8)),
                err  : withoutStatuses(err.toString(StandardCharsets.UTF_8)),
                files: files.collectEntries { String path ->
                    def file = new File(path)
                    [(path): file.exists() ? file.getText('UTF-8') : null]
                }
            ]
        ]
    }

    /**
     * Status listeners print Logback's own status messages, which include Joran's progress
     * messages and timestamps. They are not part of the logging output.
     */
    private static String withoutStatuses(String output) {
        output.readLines()
            .findAll { String line -> !(line ==~ /\d{2}:\d{2}:\d{2},\d{3} \|-.*/) && !line.startsWith('\t') }
            .join('\n')
    }

    private static Map<String, Object> snapshot(LoggerContext context) {
        [
            loggers          : context.loggerList.collectEntries { Logger logger ->
                [(logger.name): [
                    level         : logger.level?.toString(),
                    effectiveLevel: logger.effectiveLevel.toString(),
                    additive      : logger.additive,
                    appenders     : describeAppenders(logger)
                ]]
            },
            statusListeners  : context.statusManager.copyOfStatusListenerList.collect { it.class.name },
            contextListeners : context.copyOfListenerList.collect { it.class.name },
            packagingData    : context.packagingDataEnabled,
            frameworkPackages: new ArrayList<>(context.frameworkPackages)
        ]
    }

    private static List<Map<String, Object>> describeAppenders(AppenderAttachable<ILoggingEvent> attachable) {
        attachable.iteratorForAppenders().collect { Appender<ILoggingEvent> appender ->
            Map<String, Object> description = [
                name   : appender.name,
                type   : appender.class.name,
                started: appender.started,
                filters: appender.copyOfAttachedFiltersList.collect { [type: it.class.name, started: it.started] }
            ]
            if (appender instanceof AppenderAttachable) {
                description.appenders = describeAppenders((AppenderAttachable<ILoggingEvent>) appender)
            }
            if (appender instanceof OutputStreamAppender) {
                def encoder = ((OutputStreamAppender) appender).encoder
                description.immediateFlush = ((OutputStreamAppender) appender).immediateFlush
                description.encoder = encoder == null ? null : [type: encoder.class.name, started: encoder.started]
            }
            if (appender instanceof FileAppender) {
                description.file = ((FileAppender) appender).file
            }
            description
        }
    }

    @CompileStatic
    private static void logEvents(LoggerContext context) {
        List<String> names = [Logger.ROOT_LOGGER_NAME, 'com.example.x']
        for (Logger logger : context.loggerList) {
            names << logger.name
            names << logger.name + '.child'
        }
        int sequence = 0
        for (String name : names.unique()) {
            Logger logger = context.getLogger(name)
            for (Level level : LEVELS) {
                if (logger.isEnabledFor(level)) {
                    def event = new LoggingEvent(Logger.name, logger, level, 'event {} on {}', null, [sequence, name] as Object[])
                    event.timeStamp = TIMESTAMP + sequence
                    logger.callAppenders(event)
                }
                sequence++
            }
        }
    }

    class TestLogbackConfigurationSourceGenerator extends LogbackConfigurationSourceGenerator {
        @Override
        protected String getLogbackFileName() {
            configFileName
        }
    }
}
