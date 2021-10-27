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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.async.publisher.PublishersOptimizations;
import io.micronaut.core.optim.StaticOptimizations;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An optimizer which is responsible for determining what reactive
 * types are found at build time.
 */
public class PublishersSourceGenerator extends AbstractSourceGenerator {
    public static final String ID = "scan.reactive.types";
    public static final String DESCRIPTION = "Scans reactive types at build time instead of runtime";

    private List<String> knownReactiveTypes;
    private List<String> knownSingleTypes;
    private List<String> knownCompletableTypes;

    @Override
    @NonNull
    public Optional<String> getDescription() {
        return Optional.of(DESCRIPTION);
    }

    protected final void doInit() {
        knownReactiveTypes = typeNamesOf(Publishers.getKnownReactiveTypes());
        knownSingleTypes = typeNamesOf(Publishers.getKnownSingleTypes());
        knownCompletableTypes = typeNamesOf(Publishers.getKnownCompletableTypes());
    }

    @Override
    @NonNull
    public String getId() {
        return ID;
    }

    @Override
    @NonNull
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

    private static List<String> typeNamesOf(Collection<Class<?>> classes) {
        return classes.stream().map(Class::getName).collect(Collectors.toList());
    }

}
