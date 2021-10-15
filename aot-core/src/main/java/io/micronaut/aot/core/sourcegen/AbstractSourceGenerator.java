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

import javax.lang.model.element.Modifier;
import java.net.URLClassLoader;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Base class for source generators which need access to the generation
 * context (for example to get a handle on the analyzed application classloader,
 * or to register resources to be excluded from the final binary).
 */
public abstract class AbstractSourceGenerator implements SourceGenerator {
    protected final SourceGenerationContext context;

    protected AbstractSourceGenerator(SourceGenerationContext context) {
        this.context = context;
    }

    public static String simpleNameOf(String fqcn) {
        return fqcn.substring(fqcn.lastIndexOf(".") + 1);
    }

    /**
     * Returns the classloader which can be used to load application
     * classes.
     * Consumers must not close this classloader as its the responsibility
     * of the creator to do it.
     * @return the application classloader
     */
    public URLClassLoader getClassLoader() {
        return context.getClassloader();
    }

    /**
     * Returns the source generation context.
     * @return the source generation context
     */
    protected SourceGenerationContext getContext() {
        return context;
    }

    @Override
    public final void init() {
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
}
