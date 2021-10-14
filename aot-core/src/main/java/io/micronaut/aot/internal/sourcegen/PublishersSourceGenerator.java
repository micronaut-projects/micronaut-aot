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
package io.micronaut.aot.internal.sourcegen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.core.async.publisher.PublishersOptimizations;
import io.micronaut.core.optim.StaticOptimizations;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An optimizer which is responsible for determining what reactive
 * types are found at build time.
 */
public class PublishersSourceGenerator extends AbstractSourceGenerator {
    private List<String> knownReactiveTypes;
    private List<String> knownSingleTypes;
    private List<String> knownCompletableTypes;

    public PublishersSourceGenerator(SourceGenerationContext context) {
        super(context);
    }

    private static CodeBlock asClassList(List<String> types) {
        CodeBlock.Builder knownReactiveBlock = CodeBlock.builder()
                .add("$T.asList(", Arrays.class);
        for (int i = 0; i < types.size(); i++) {
            String knownReactiveType = types.get(i);
            knownReactiveBlock.add("$T.class", ClassName.bestGuess(knownReactiveType.replace('$', '.')));
            if (i < types.size() - 1) {
                knownReactiveBlock.add(", ");
            }
        }
        knownReactiveBlock.add(")");
        return knownReactiveBlock.build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> findPublishersType(Class<?> publishers, String method) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        return ((List<Class<?>>) publishers.getDeclaredMethod(method)
                .invoke(null))
                .stream()
                .map(Class::getName)
                .collect(Collectors.toList());
    }

    protected final void doInit() {
        URLClassLoader cl = getClassLoader();
        try {
            Class<?> publishers = cl.loadClass("io.micronaut.core.async.publisher.Publishers");
            knownReactiveTypes = findPublishersType(publishers, "getKnownReactiveTypes");
            knownSingleTypes = findPublishersType(publishers, "getKnownSingleTypes");
            knownCompletableTypes = findPublishersType(publishers, "getKnownCompletableTypes");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    @Override
    public Optional<MethodSpec> generateStaticInit() {
        return staticMethod("preparePublishers", body -> body.addStatement(
                "$T.set(new $T($L, $L, $L))",
                StaticOptimizations.class,
                PublishersOptimizations.class,
                asClassList(knownReactiveTypes),
                asClassList(knownSingleTypes),
                asClassList(knownCompletableTypes)
        ));
    }
}
