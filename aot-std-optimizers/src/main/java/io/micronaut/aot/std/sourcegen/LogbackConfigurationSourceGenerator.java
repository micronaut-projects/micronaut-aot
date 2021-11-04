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
package io.micronaut.aot.std.sourcegen;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.sourcegen.AbstractSingleClassFileGenerator;
import io.micronaut.core.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.util.Locale;
import java.util.Optional;

/**
 * A source generator responsible for converting a logback.xml configuration into
 * Java configuration.
 *
 * Note: for now the implementation does NOT do that, it's hardcoded!
 */
public class LogbackConfigurationSourceGenerator extends AbstractSingleClassFileGenerator {
    public static final String ID = "logback.xml.to.java";
    public static final String DESCRIPTION = "Replaces logback.xml with a pure Java configuration (NOT YET IMPLEMENTED!)";
    private static final Logger LOGGER = LoggerFactory.getLogger(LogbackConfigurationSourceGenerator.class);

    @Override
    @NonNull
    public String getId() {
        return ID;
    }

    @Override
    @NonNull
    public Optional<String> getDescription() {
        return Optional.of(DESCRIPTION);
    }

    @Override
    @NonNull
    protected JavaFile generate() {
        LOGGER.warn("The logback.xml conversion feature is not implemented yet. Using hardcoded Java configuration.");
        TypeSpec typeSpec = TypeSpec.classBuilder("StaticLogbackConfiguration")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Configurator.class)
                .addField(contextField())
                .addMethod(configureMethod())
                .addMethod(setcontextMethod())
                .addMethod(getcontextMethod())
                .addMethod(addStatusMethod())
                .addMethod(addLogMethod("Info"))
                .addMethod(addLog2Method("Info"))
                .addMethod(addLogMethod("Warn"))
                .addMethod(addLog2Method("Warn"))
                .addMethod(addLogMethod("Error"))
                .addMethod(addLog2Method("Error"))
                .build();
        return getContext().javaFile(typeSpec);
    }

    @Override
    public void doInit() {
        getContext().registerExcludedResource("logback.xml");
    }

    @Override
    public void generateResourceFiles(@NonNull File targetDirectory) {
        writeServiceFile(targetDirectory, Configurator.class, "StaticLogbackConfiguration");
    }

    private static MethodSpec addStatusMethod() {
        return MethodSpec.methodBuilder("addStatus")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Status.class, "status")
                .build();
    }

    private static MethodSpec addLogMethod(String name) {
        return MethodSpec.methodBuilder("add" + name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, name.toLowerCase(Locale.US))
                .build();
    }

    private static MethodSpec addLog2Method(String name) {
        return MethodSpec.methodBuilder("add" + name)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(String.class, name.toLowerCase(Locale.US))
                .addParameter(Throwable.class, "ex")
                .build();
    }

    private static MethodSpec getcontextMethod() {
        return MethodSpec.methodBuilder("getContext")
                .addModifiers(Modifier.PUBLIC)
                .returns(Context.class)
                .addStatement("return context")
                .build();
    }

    private static MethodSpec setcontextMethod() {
        return MethodSpec.methodBuilder("setContext")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(Context.class, "context")
                .addStatement("this.context = context")
                .build();
    }

    private static MethodSpec configureMethod() {
        return MethodSpec.methodBuilder("configure")
                .addModifiers(Modifier.PUBLIC)
                .addParameter(LoggerContext.class, "loggerContext")
                .addStatement("$T console = new $T<>()", ParameterizedTypeName.get(ConsoleAppender.class, ILoggingEvent.class), ConsoleAppender.class)
                .addStatement("console.setWithJansi(true)")
                .addStatement("console.setContext(context)")
                .addStatement("$T encoder = new $T();", PatternLayoutEncoder.class, PatternLayoutEncoder.class)
                .addStatement("encoder.setPattern(\"%cyan(%d{HH:mm:ss.SSS}) %gray([%thread]) %highlight(%-5level) %magenta(%logger{36}) - %msg%n\")")
                .addStatement("encoder.setContext(context)")
                .addStatement("console.setEncoder(encoder)")
                .addStatement("$T logger = loggerContext.getLogger($T.ROOT_LOGGER_NAME)", ch.qos.logback.classic.Logger.class, ch.qos.logback.classic.Logger.class)
                .addStatement("logger.addAppender(console)")
                .addStatement("logger.setLevel($T.INFO)", Level.class)
                .addStatement("logger = loggerContext.getLogger(\"io.micronaut.core.io.service.SoftServiceLoader\")")
                .addStatement("logger.setLevel($T.DEBUG)", Level.class)
                .addStatement("logger.setAdditive(false)")
                .addStatement("logger.addAppender(console)")
                .addStatement("encoder.start()")
                .addStatement("console.start()")
                .build();
    }

    private static FieldSpec contextField() {
        return FieldSpec.builder(Context.class, "context")
                .addModifiers(Modifier.PRIVATE)
                .build();
    }
}
