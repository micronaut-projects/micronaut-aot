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

import ch.qos.logback.classic.spi.Configurator;
import ch.qos.logback.core.Context;
import ch.qos.logback.core.status.Status;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.codegen.AbstractSingleClassFileGenerator;
import io.micronaut.core.annotation.NonNull;

import javax.lang.model.element.Modifier;
import java.util.Locale;

import static io.micronaut.aot.std.sourcegen.Logback14GeneratorHelper.configureMethod;

/**
 * A source generator responsible for converting a logback.xml configuration into
 * Java configuration.
 */
@AOTModule(
        id = LogbackConfigurationSourceGenerator.ID,
        description = LogbackConfigurationSourceGenerator.DESCRIPTION
)
public class LogbackConfigurationSourceGenerator extends AbstractSingleClassFileGenerator {
    public static final String ID = "logback.xml.to.java";
    public static final String DESCRIPTION = "Replaces logback.xml with a pure Java configuration (Experimental)";

    @Override
    @NonNull
    protected JavaFile generate() {
        try {
            Class.forName("ch.qos.logback.core.model.Model");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("The logback.xml conversion feature requires logback 1.4 on the AOT optimizer classpath.");
        }
        TypeSpec typeSpec = TypeSpec.classBuilder("StaticLogbackConfiguration")
                .addModifiers(Modifier.PUBLIC)
                .addSuperinterface(Configurator.class)
                .addField(contextField())
                .addMethod(configureMethod(getLogbackFileName(), getContext()))
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
        return javaFile(typeSpec);
    }

    /**
     * Returns the name of the logback configuration file.
     * Can be overridden in tests.
     *
     * @return the name of the logback configuration file
     */
    protected String getLogbackFileName() {
        return "logback.xml";
    }

    @Override
    public void generate(@NonNull AOTContext context) {
        super.generate(context);
        context.registerExcludedResource(getLogbackFileName());
        context.registerServiceImplementation(Configurator.class, "StaticLogbackConfiguration");
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

    private static FieldSpec contextField() {
        return FieldSpec.builder(Context.class, "context")
                .addModifiers(Modifier.PRIVATE)
                .build();
    }

}
