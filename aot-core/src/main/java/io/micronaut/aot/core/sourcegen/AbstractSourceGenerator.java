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
package io.micronaut.aot.core.sourcegen;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.aot.core.SourceGenerationContext;
import io.micronaut.aot.core.AOTSourceGenerator;
import io.micronaut.core.annotation.NonNull;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Base class for source generators which need access to the generation
 * context (for example to get a handle on the analyzed application classloader,
 * or to register resources to be excluded from the final binary).
 */
public abstract class AbstractSourceGenerator implements AOTSourceGenerator {
    protected SourceGenerationContext context;

    public static String simpleNameOf(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf(".") + 1);
    }

    /**
     * Returns the source generation context.
     * @return the source generation context
     */
    protected SourceGenerationContext getContext() {
        if (context == null) {
            throw new IllegalStateException("Cannot call getContext before context is injected");
        }
        return context;
    }

    @Override
    public final void init(@NonNull SourceGenerationContext context) {
        this.context = context;
        try {
            doInit();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected void doInit() throws Exception {

    }

    protected static Optional<MethodSpec> staticMethodBuilder(String name, Consumer<? super MethodSpec.Builder> consumer) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(name)
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC);
        consumer.accept(builder);
        return Optional.of(builder.build());
    }

    protected static Optional<MethodSpec> staticMethod(String name, Consumer<? super CodeBlock.Builder> body) {
        CodeBlock.Builder bodyBuilder = CodeBlock.builder();
        body.accept(bodyBuilder);
        return staticMethodBuilder(name, m -> {
            m.addCode(bodyBuilder.build());
        });
    }

    protected final void writeServiceFile(File targetDirectory, Class<?> serviceType, String simpleServiceName) {
        File serviceDir = new File(targetDirectory, "META-INF/services");
        if (serviceDir.isDirectory() || serviceDir.mkdirs()) {
            File serviceFile = new File(serviceDir, serviceType.getName());
            try (PrintWriter wrt = new PrintWriter(new FileWriter(serviceFile))) {
                wrt.println(getContext().getPackageName() + "." + simpleServiceName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
