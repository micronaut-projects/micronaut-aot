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

import ch.qos.logback.classic.spi.Configurator
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
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.OnErrorConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    OnErrorConsoleStatusListener statuslistener = new OnErrorConsoleStatusListener();
    boolean statuslistenerAdded = loggerContext.getStatusManager().add(statuslistener);
    statuslistener.setContext(loggerContext);
    if (statuslistenerAdded) {
      statuslistener.start();
    }
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setContext(loggerContext);
    stdout.setName("STDOUT");
    stdout.setWithJansi(true);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n");
    encoder.setParent(stdout);
    encoder.start();
    stdout.setEncoder(encoder);
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
    io_micronaut_aot.addAppender(stdout);
    io_micronaut_core_io_service_softserviceloader.addAppender(stdout);
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

    def "does not generate configuration when logback file is not present"() {
        configFileName = "logback-missing.xml"

        when:
        generate()

        then:
        excludesResources()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            doesNotGenerateClasses()
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
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    ConsoleAppender console = new ConsoleAppender();
    console.setContext(loggerContext);
    console.setName("console");
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setParent(console);
    encoder.start();
    console.setEncoder(encoder);
    console.start();
    FileAppender file = new FileAppender();
    file.setContext(loggerContext);
    file.setName("file");
    file.setFile("/tmp/logback.log");
    file.setAppend(true);
    file.setImmediateFlush(true);
    PatternLayoutEncoder encoder_3 = new PatternLayoutEncoder();
    encoder_3.setContext(loggerContext);
    encoder_3.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n");
    encoder_3.setParent(file);
    encoder_3.start();
    file.setEncoder(encoder_3);
    file.start();
    Logger org_acme = loggerContext.getLogger("org.acme");
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    org_acme.addAppender(console);
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
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.FileSize;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    RollingFileAppender file = new RollingFileAppender();
    file.setContext(loggerContext);
    file.setName("file");
    file.setFile("/tmp/logback.log");
    TimeBasedRollingPolicy rollingpolicy = new TimeBasedRollingPolicy();
    rollingpolicy.setContext(loggerContext);
    rollingpolicy.setFileNamePattern("/tmp/logback.%d{yyyy-MM-dd}.%i.log");
    SizeAndTimeBasedFNATP timebasedfilenamingandtriggeringpolicy = new SizeAndTimeBasedFNATP();
    timebasedfilenamingandtriggeringpolicy.setContext(loggerContext);
    timebasedfilenamingandtriggeringpolicy.setMaxFileSize(FileSize.valueOf("10MB"));
    rollingpolicy.setTimeBasedFileNamingAndTriggeringPolicy(timebasedfilenamingandtriggeringpolicy);
    rollingpolicy.setMaxHistory(7);
    rollingpolicy.setParent(file);
    rollingpolicy.start();
    file.setRollingPolicy(rollingpolicy);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setOutputPatternAsHeader(true);
    encoder.setParent(file);
    encoder.start();
    file.setEncoder(encoder);
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
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.spi.FilterReply;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    ConsoleAppender console = new ConsoleAppender();
    console.setContext(loggerContext);
    console.setName("console");
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n");
    encoder.setParent(console);
    encoder.start();
    console.setEncoder(encoder);
    LevelFilter filter = new LevelFilter();
    filter.setContext(loggerContext);
    filter.setLevel(Level.valueOf("INFO"));
    filter.setOnMatch(FilterReply.valueOf("DENY"));
    filter.setOnMismatch(FilterReply.valueOf("ACCEPT"));
    filter.start();
    console.addFilter(filter);
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
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    LevelChangePropagator contextlistener = new LevelChangePropagator();
    contextlistener.setContext(loggerContext);
    contextlistener.setResetJUL(true);
    contextlistener.start();
    loggerContext.addListener(contextlistener);
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setContext(loggerContext);
    stdout.setName("STDOUT");
    stdout.setWithJansi(true);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n");
    encoder.setParent(stdout);
    encoder.start();
    stdout.setEncoder(encoder);
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
    io_micronaut_aot.addAppender(stdout);
    io_micronaut_core_io_service_softserviceloader.addAppender(stdout);
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

    def "logback appender with charset"() {
        configFileName = "logback-test6.xml"

        when:
        generate()

        then:
        excludesResources("logback-test6.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;
import java.nio.charset.Charset;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setContext(loggerContext);
    stdout.setName("STDOUT");
    stdout.setWithJansi(false);
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%cyan(%d{HH:mm:ss.SSS}) %highlight(%-5level) %gray([%thread]) %magenta(%logger{25}) [%file:%line] - %msg%n");
    encoder.setCharset(Charset.forName("UTF-8"));
    encoder.setParent(stdout);
    encoder.start();
    stdout.setEncoder(encoder);
    stdout.start();
    Logger com_zaxxer = loggerContext.getLogger("com.zaxxer");
    com_zaxxer.setLevel(Level.WARN);
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    _rootLogger.addAppender(stdout);
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

    def "attaches nested appender refs before starting the appender and skips unreferenced appenders"() {
        configFileName = "logback-test7.xml"

        when:
        generate()

        then:
        excludesResources("logback-test7.xml")
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            hasClass("StaticLogbackConfiguration") {
                withSources """package io.micronaut.test;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.util.DefaultJoranConfigurator;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.OnConsoleStatusListener;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.ContextUtil;
import ch.qos.logback.core.util.OptionHelper;
import ch.qos.logback.core.util.StatusListenerConfigHelper;
import java.lang.String;
import java.lang.Throwable;

public class StaticLogbackConfiguration implements Configurator {
  private Context context;

  public Configurator.ExecutionStatus configure(LoggerContext loggerContext) {
    if (OptionHelper.getSystemProperty("logback.configurationFile") != null) {
      DefaultJoranConfigurator joranConfigurator = new DefaultJoranConfigurator();
      joranConfigurator.setContext(loggerContext);
      if (joranConfigurator.configure(loggerContext) == Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY) {
        return Configurator.ExecutionStatus.DO_NOT_INVOKE_NEXT_IF_ANY;
      }
    }
    String logbackDebug = OptionHelper.getSystemProperty("logback.debug");
    if (logbackDebug != null && !logbackDebug.trim().isEmpty() && !logbackDebug.equalsIgnoreCase("false") && !logbackDebug.equalsIgnoreCase("null")) {
      StatusListenerConfigHelper.addOnConsoleListenerInstance(loggerContext, new OnConsoleStatusListener());
    }
    loggerContext.setPackagingDataEnabled(false);
    new ContextUtil(loggerContext).addGroovyPackages(loggerContext.getFrameworkPackages());
    ConsoleAppender stdout = new ConsoleAppender();
    stdout.setContext(loggerContext);
    stdout.setName("STDOUT");
    PatternLayoutEncoder encoder = new PatternLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("OUT %level %logger - %msg%n");
    encoder.setParent(stdout);
    encoder.start();
    stdout.setEncoder(encoder);
    stdout.start();
    ConsoleAppender err = new ConsoleAppender();
    err.setContext(loggerContext);
    err.setName("ERR");
    err.setTarget("System.err");
    PatternLayoutEncoder encoder_3 = new PatternLayoutEncoder();
    encoder_3.setContext(loggerContext);
    encoder_3.setPattern("ERR %level %logger - %msg%n");
    encoder_3.setParent(err);
    encoder_3.start();
    err.setEncoder(encoder_3);
    err.start();
    AsyncAppender async = new AsyncAppender();
    async.setContext(loggerContext);
    async.setName("ASYNC");
    async.addAppender(stdout);
    async.start();
    Logger com_example_a = loggerContext.getLogger("com.example.a");
    com_example_a.setLevel(Level.WARN);
    Logger com_example_a_b = loggerContext.getLogger("com.example.a.b");
    com_example_a_b.setLevel(null);
    Logger com_example_c = loggerContext.getLogger("com.example.c");
    com_example_c.setLevel(Level.WARN);
    Logger _rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    _rootLogger.setLevel(Level.INFO);
    _rootLogger.addAppender(async);
    _rootLogger.addAppender(err);
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

    def "leaves #fixture to Joran"() {
        configFileName = "logback-unsupported/${fixture}.xml"

        when:
        generate()

        then:
        excludesResources()
        assertThatGeneratedSources {
            doesNotCreateInitializer()
            doesNotGenerateClasses()
        }
        !new File(resourcesDir.toFile(), "META-INF/services/${Configurator.name}").exists()
        context.diagnostics[LogbackConfigurationSourceGenerator.ID].any {
            it.startsWith("Skipping logback configuration conversion because ${configFileName} ") && it.contains(diagnostic)
        }

        where:
        fixture                | diagnostic
        'property'             | 'contains <property>'
        'variable'             | 'contains <variable>'
        'substitution'         | 'uses variable substitution in [${PATTERN:-%msg%n}]'
        'rewritten-value'      | 'uses [%msg :- %n], which Joran changes during variable substitution'
        'timestamp'            | 'contains <timestamp>'
        'context-name'         | 'contains <contextName>'
        'shutdown-hook'        | 'contains <shutdownHook>'
        'include'              | 'contains <include>'
        'turbo-filter'         | 'contains <turboFilter>'
        'define'               | 'contains <define>'
        'conversion-rule'      | 'contains <conversionRule>'
        // Logback 1.5 reports the deprecated <level> element, older versions produce a LevelModel
        'logger-level-element' | '<level>'
        'root-inherited'       | 'sets the level of the root logger to INHERITED'
        'scan'                 | 'enables scan'
        'forward-appender-ref' | 'references appender [STDOUT] from appender [ASYNC] before declaring it'
        'missing-appender-ref' | 'references appender [MISSING], which is not declared'
        'unknown-property'     | 'sets property [tagret]'
        'unconvertible-value'  | 'sets property [immediateFlush] to [sometimes]'
        'unknown-class'        | 'refers to class [com.example.MissingAppender], which cannot be loaded'
        'file-collision'       | 'declares appenders [FIRST] and [SECOND] with the same file'
        'malformed'            | 'could not be parsed'
    }

    class TestLogbackConfigurationSourceGenerator extends LogbackConfigurationSourceGenerator {
        @Override
        protected String getLogbackFileName() {
            configFileName
        }
    }
}
