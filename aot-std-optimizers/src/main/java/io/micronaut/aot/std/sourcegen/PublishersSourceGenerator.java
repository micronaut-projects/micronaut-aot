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

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.core.async.publisher.PublishersOptimizations;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * An optimizer which is responsible for determining what reactive
 * types are found at build time.
 */
@AOTModule(
    id = PublishersSourceGenerator.ID,
    description = PublishersSourceGenerator.DESCRIPTION
)
public class PublishersSourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "scan.reactive.types";
    public static final String DESCRIPTION = "Scans reactive types at build time instead of runtime";

    @Override
    public void generate(@NonNull AOTContext context) {
        context.registerStaticOptimization("PublishersOptimizationsLoader", PublishersOptimizations.class, body -> {
            List<String> knownReactiveTypes = typeNamesOf(Publishers.getKnownReactiveTypes());
            List<String> knownSingleTypes = typeNamesOf(Publishers.getKnownSingleTypes());
            List<String> knownCompletableTypes = typeNamesOf(Publishers.getKnownCompletableTypes());
            body.addStatement(
                "return new $T($L, $L, $L)",
                PublishersOptimizations.class,
                asClassList(knownReactiveTypes),
                asClassList(knownSingleTypes),
                asClassList(knownCompletableTypes)
            );
        });

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
        return classes.stream()
            .map(Class::getName)
            .toList();
    }

}
