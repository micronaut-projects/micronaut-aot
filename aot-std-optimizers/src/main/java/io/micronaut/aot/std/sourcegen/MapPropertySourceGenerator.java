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

import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.codegen.AbstractSingleClassFileGenerator;
import io.micronaut.aot.core.codegen.MapGenerator;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.util.StringUtils;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.micronaut.aot.std.sourcegen.MapPropertySourceGenerator.BASE_ORDER_OPTION;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A source generator which generates a map property source with a fixed
 * set of values at build time.
 */
@AOTModule(
    id = MapPropertySourceGenerator.BASE_ID,
    options = {
        @Option(
            key = BASE_ORDER_OPTION,
            description = "The order of the generated property source",
            sampleValue = "1000"
        )
    }
)
public class MapPropertySourceGenerator extends AbstractSingleClassFileGenerator {
    public static final String BASE_ID = "map.property";
    public static final String BASE_ORDER_OPTION = "map.property.order";

    private final String namePrefix;
    private final String resourceName;
    private final Map<String, Object> values;
    @Nullable
    private final Integer order;

    public MapPropertySourceGenerator(
        String namePrefix,
        String resourceName,
        Map<String, Object> values,
        @Nullable
        Integer order
    ) {
        this.namePrefix = namePrefix;
        this.resourceName = resourceName;
        this.values = values;
        this.order = order;
    }

    public MapPropertySourceGenerator(
        String resourceName,
        Map<String, Object> values
    ) {
        this("", resourceName, values, null);
    }

    @Override
    @NonNull
    protected JavaFile generate() {
        String typeName = computeTypeName();
        String orderKey = BASE_ORDER_OPTION + "." + resourceName;
        Integer configuredOrder = getContext().getConfiguration()
            .optionalValue(orderKey, value ->
                value.map(Integer::parseInt)
                    .orElse(this.order != null ? this.order : Ordered.HIGHEST_PRECEDENCE));
        int order = configuredOrder != null ? configuredOrder : Ordered.HIGHEST_PRECEDENCE;
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(typeName)
            .addModifiers(PUBLIC)
            .superclass(MapPropertySource.class);
        var generator = new MapGenerator();
        typeBuilder.addMethod(MethodSpec.constructorBuilder()
                .addStatement("super($S, $L)", resourceName, generator.generateMap(typeBuilder, values))
                .build())
            .addMethod(MethodSpec.methodBuilder("getOrder")
                .addModifiers(PUBLIC)
                .returns(int.class)
                .addStatement("return $L", order)
                .build())
            .addAnnotation(Generated.class);
        return javaFile(typeBuilder.build());
    }

    private String computeTypeName() {
        return StringUtils.capitalize(resourceName.replaceAll("[^A-Za-z0-9]", "_") + namePrefix + "StaticPropertySource");
    }
}
