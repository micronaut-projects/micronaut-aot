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

import com.squareup.javapoet.ParameterizedTypeName;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.aot.core.config.MetadataUtils;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.reflect.ClassUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A source generator which will check for existence of a number of classes
 * at build time.
 * Missing classes will be recorded and injected at runtime as optimizations.
 */
@AOTModule(
    id = KnownMissingTypesSourceGenerator.ID,
    description = KnownMissingTypesSourceGenerator.DESCRIPTION,
    options = {
        @Option(
            key = "known.missing.types.list",
            description = "A list of types that the AOT analyzer needs to check for existence (comma separated)",
            sampleValue = "io.reactivex.Observable,reactor.core.publisher.Flux,kotlinx.coroutines.flow.Flow,io.reactivex.rxjava3.core.Flowable,io.reactivex.rxjava3.core.Observable,io.reactivex.Single,reactor.core.publisher.Mono,io.reactivex.Maybe,io.reactivex.rxjava3.core.Single,io.reactivex.rxjava3.core.Maybe,io.reactivex.Completable,io.reactivex.rxjava3.core.Completable,io.methvin.watchservice.MacOSXListeningWatchService,io.micronaut.core.async.publisher.CompletableFuturePublisher,io.micronaut.core.async.publisher.Publishers.JustPublisher,io.micronaut.core.async.subscriber.Completable"
        )
    }
)
public class KnownMissingTypesSourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "known.missing.types";
    public static final Option OPTION = MetadataUtils.findMetadata(KnownMissingTypesSourceGenerator.class).get().options()[0];
    public static final String DESCRIPTION = "Checks of existence of some types at build time instead of runtime";

    private List<String> findMissingClasses(List<String> classNames) {
        var knownMissingClasses = new ArrayList<String>();
        ClassLoader cl = this.getClass().getClassLoader();
        for (String name : classNames) {
            try {
                cl.loadClass(name);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                knownMissingClasses.add(name);
            }
        }
        return knownMissingClasses;
    }

    @Override
    public void generate(@NonNull AOTContext context) {
        context.registerStaticOptimization("KnownMissingTypesOptimizationLoader", ClassUtils.Optimizations.class, body -> {
            List<String> classNames = context.getConfiguration().stringList(OPTION.key());
            body.addStatement("$T knownMissingTypes = new $T()", ParameterizedTypeName.get(Set.class, String.class), ParameterizedTypeName.get(HashSet.class, String.class));
            for (String knownMissingClass : findMissingClasses(classNames)) {
                body.addStatement("knownMissingTypes.add($S)", knownMissingClass);
            }
            body.addStatement("return new $T(knownMissingTypes)", ClassUtils.Optimizations.class);
        });

    }
}
