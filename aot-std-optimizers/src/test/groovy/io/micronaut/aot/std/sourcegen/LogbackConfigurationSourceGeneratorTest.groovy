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
package io.micronaut.aot.std.sourcegen

import io.micronaut.aot.core.AOTCodeGenerator
import io.micronaut.aot.core.codegen.AbstractSourceGeneratorSpec

class LogbackConfigurationSourceGeneratorTest extends AbstractSourceGeneratorSpec {
    String configFileName = "logback.xml"

    @Override
    AOTCodeGenerator newGenerator() {
        new TestLogbackConfigurationSourceGenerator()
    }

    def "adds logback.xml to the excluded resources"() {
        configFileName = "logback-test1.xml"

        when:
        generate()

        then:
        excludesResources("logback-test1.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setWithJansi(true);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n");
    encoder.setContext(context);
    encoder.start();
    stdout.setEncoder(encoder);
    stdout.setContext(context);
    stdout.start();
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    Logger io_micronaut_core_optim_staticoptimizations = loggerContext.getLogger("io.micronaut.core.optim.StaticOptimizations");
    io_micronaut_core_optim_staticoptimizations.setLevel(Level.DEBUG);
    io_micronaut_core_optim_staticoptimizations.setAdditive(false);
    Logger io_micronaut_aot = loggerContext.getLogger("io.micronaut.aot");
    io_micronaut_aot.setLevel(Level.DEBUG);
    io_micronaut_aot.setAdditive(false);
    Logger io_micronaut_core_io_service_softserviceloader = loggerContext.getLogger("io.micronaut.core.io.service.SoftServiceLoader");
    io_micronaut_core_io_service_softserviceloader.setLevel(Level.DEBUG);
    io_micronaut_core_io_service_softserviceloader.setAdditive(false);
    _rootLogger.addAppender(stdout);
    io_micronaut_core_optim_staticoptimizations.addAppender(stdout);
    io_micronaut_core_io_service_softserviceloader.addAppender(stdout);
    io_micronaut_aot.addAppender(stdout);
    return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Context getContext() {
    return context;
  }

  public void addStatus(Status status) {
  }

  public void addInfo(String info) {
  }

  public void addInfo(String info, Throwable ex) {
  }

  public void addWarn(String warn) {
  }

  public void addWarn(String warn, Throwable ex) {
  }

  public void addError(String error) {
  }

  public void addError(String error, Throwable ex) {
  }
}
"""
            }
            compiles()
        }
    }

    def "converts configuration using file logger"() {
        configFileName = "logback-test2.xml"

        when:
        generate()

        then:
        excludesResources("logback-test2.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.status.Status;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    ConsoleAppender console = new ConsoleAppender();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setContext(context);
    encoder.start();
    console.setEncoder(encoder);
    console.setContext(context);
    console.start();
    FileAppender file = new FileAppender();
    file.setFile("/tmp/logback.log");
    file.setAppend(true);
    file.setImmediateFlush(true);
    PatternLayoutEncoder encoder_3 = new PatternLayoutEncoder();
    encoder_3.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
    encoder_3.setContext(context);
    encoder_3.start();
    file.setEncoder(encoder_3);
    file.setContext(context);
    file.start();
    Logger org_acme = loggerContext.getLogger("org.acme");
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    _rootLogger.addAppender(file);
    org_acme.addAppender(console);
    return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Context getContext() {
    return context;
  }

  public void addStatus(Status status) {
  }

  public void addInfo(String info) {
  }

  public void addInfo(String info, Throwable ex) {
  }

  public void addWarn(String warn) {
  }

  public void addWarn(String warn, Throwable ex) {
  }

  public void addError(String error) {
  }

  public void addError(String error, Throwable ex) {
  }
}
"""
            }
            compiles()
        }
    }

    def "converts configuration using nested components"() {
        configFileName = "logback-test3.xml"

        when:
        generate()

        then:
        excludesResources("logback-test3.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.FileSize;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    RollingFileAppender file = new RollingFileAppender();
    file.setFile("/tmp/logback.log");
    TimeBasedRollingPolicy rollingpolicy = new TimeBasedRollingPolicy();
    rollingpolicy.setFileNamePattern("/tmp/logback.%d{yyyy-MM-dd}.%i.log");
    SizeAndTimeBasedFNATP timebasedfilenamingandtriggeringpolicy = new SizeAndTimeBasedFNATP();
    timebasedfilenamingandtriggeringpolicy.setMaxFileSize(FileSize.valueOf("10MB"));
    timebasedfilenamingandtriggeringpolicy.setContext(context);
    timebasedfilenamingandtriggeringpolicy.start();
    rollingpolicy.setTimeBasedFileNamingAndTriggeringPolicy(timebasedfilenamingandtriggeringpolicy);
    rollingpolicy.setMaxHistory(7);
    rollingpolicy.setContext(context);
    rollingpolicy.start();
    file.setRollingPolicy(rollingpolicy);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setOutputPatternAsHeader(true);
    encoder.setContext(context);
    encoder.start();
    file.setEncoder(encoder);
    file.setContext(context);
    file.start();
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    _rootLogger.addAppender(file);
    return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Context getContext() {
    return context;
  }

  public void addStatus(Status status) {
  }

  public void addInfo(String info) {
  }

  public void addInfo(String info, Throwable ex) {
  }

  public void addWarn(String warn) {
  }

  public void addWarn(String warn, Throwable ex) {
  }

  public void addError(String error) {
  }

  public void addError(String error, Throwable ex) {
  }
}
"""
            }
            compiles()
        }
    }

    def "converts configuration using filters"() {
        configFileName = "logback-test4.xml"

        when:
        generate()

        then:
        excludesResources("logback-test4.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.filter.LevelFilter;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.Status;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    ConsoleAppender console = new ConsoleAppender();
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setContext(context);
    encoder.start();
    console.setEncoder(encoder);
    LevelFilter filter = new LevelFilter();
    filter.setLevel(Level.valueOf("INFO"));
    filter.setOnMatch(FilterReply.valueOf("DENY"));
    filter.setOnMismatch(FilterReply.valueOf("ACCEPT"));
    filter.setContext(context);
    filter.start();
    console.addFilter(filter);
    console.setContext(context);
    console.start();
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    _rootLogger.addAppender(console);
    return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Context getContext() {
    return context;
  }

  public void addStatus(Status status) {
  }

  public void addInfo(String info) {
  }

  public void addInfo(String info, Throwable ex) {
  }

  public void addWarn(String warn) {
  }

  public void addWarn(String warn, Throwable ex) {
  }

  public void addError(String error) {
  }

  public void addError(String error, Throwable ex) {
  }
}
"""
            }
            compiles()
        }
    }

    def "converts configuration with logger context listener - jul LevelChangePropagator"() {
        configFileName = "logback-test5.xml"

        when:
        generate()

        then:
        excludesResources("logback-test5.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.jul.LevelChangePropagator;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    LevelChangePropagator contextlistener = new LevelChangePropagator();
    contextlistener.setResetJUL(true);
    contextlistener.setContext(context);
    contextlistener.start();
    loggerContext.addListener(contextlistener);
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setWithJansi(true);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setPattern("%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n");
    encoder.setContext(context);
    encoder.start();
    stdout.setEncoder(encoder);
    stdout.setContext(context);
    stdout.start();
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    Logger io_micronaut_core_optim_staticoptimizations = loggerContext.getLogger("io.micronaut.core.optim.StaticOptimizations");
    io_micronaut_core_optim_staticoptimizations.setLevel(Level.DEBUG);
    io_micronaut_core_optim_staticoptimizations.setAdditive(false);
    Logger io_micronaut_aot = loggerContext.getLogger("io.micronaut.aot");
    io_micronaut_aot.setLevel(Level.DEBUG);
    io_micronaut_aot.setAdditive(false);
    Logger io_micronaut_core_io_service_softserviceloader = loggerContext.getLogger("io.micronaut.core.io.service.SoftServiceLoader");
    io_micronaut_core_io_service_softserviceloader.setLevel(Level.DEBUG);
    io_micronaut_core_io_service_softserviceloader.setAdditive(false);
    _rootLogger.addAppender(stdout);
    io_micronaut_core_optim_staticoptimizations.addAppender(stdout);
    io_micronaut_core_io_service_softserviceloader.addAppender(stdout);
    io_micronaut_aot.addAppender(stdout);
    return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
  }

  public void setContext(Context context) {
    this.context = context;
  }

  public Context getContext() {
    return context;
  }

  public void addStatus(Status status) {
  }

  public void addInfo(String info) {
  }

  public void addInfo(String info, Throwable ex) {
  }

  public void addWarn(String warn) {
  }

  public void addWarn(String warn, Throwable ex) {
  }

  public void addError(String error) {
  }

  public void addError(String error, Throwable ex) {
  }
}
"""
            }
            compiles()
        }
    }

    class TestLogbackConfigurationSourceGenerator extends LogbackConfigurationSourceGenerator {
        @Override
        protected String getLogbackFileName() {
            configFileName
        }
    }
}
