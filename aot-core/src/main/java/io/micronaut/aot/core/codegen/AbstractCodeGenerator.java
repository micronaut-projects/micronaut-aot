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
package io.micronaut.aot.core.codegen;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.aot.core.AOTCodeGenerator;
import io.micronaut.aot.core.AOTContext;

import javax.lang.model.element.Modifier;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.function.Consumer;

/**
 * Base class for code generators which need access to the generation
 * context (for example to get a handle on the analyzed application classloader,
 * or to register resources to be excluded from the final binary).
 */
public abstract class AbstractCodeGenerator implements AOTCodeGenerator {
    public static String simpleNameOf(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf(".") + 1);
    }

    protected static MethodSpec staticMethodBuilder(String name, Consumer<? super MethodSpec.Builder> consumer) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        consumer.accept(builder);
        return builder.build();
    }

    protected static MethodSpec staticMethod(String name, Consumer<? super CodeBlock.Builder> body) {
        CodeBlock.Builder bodyBuilder = CodeBlock.builder();
        body.accept(bodyBuilder);
        return staticMethodBuilder(name, m -> m.addCode(bodyBuilder.build()));
    }

    protected final void writeServiceFile(AOTContext context, Class<?> serviceType, String simpleServiceName) {
        context.registerGeneratedResource("META-INF/services/" + serviceType.getName(), serviceFile -> {
            try (PrintWriter wrt = new PrintWriter(new FileWriter(serviceFile))) {
                wrt.println(context.getPackageName() + "." + simpleServiceName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }
}
