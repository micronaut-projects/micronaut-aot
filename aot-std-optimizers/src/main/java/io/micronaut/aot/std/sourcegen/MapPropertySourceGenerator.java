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

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;
import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.Option;
import io.micronaut.aot.core.codegen.AbstractSingleClassFileGenerator;
import io.micronaut.context.env.MapPropertySource;
import io.micronaut.core.annotation.Generated;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.order.Ordered;
import io.micronaut.core.util.StringUtils;

import java.util.HashMap;
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

    private final String resourceName;
    private final Map<String, Object> values;

    public MapPropertySourceGenerator(
            String resourceName,
            Map<String, Object> values) {
        this.resourceName = resourceName;
        this.values = values;
    }

    private CodeBlock generateMap() {
        CodeBlock.Builder mapBuilder = CodeBlock.builder();
        mapBuilder.add("new $T() {{\n", HashMap.class);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            mapBuilder.add("put(\"" + key + "\", ");
            if (value == null) {
                mapBuilder.add("null");
            } else if (CharSequence.class.isAssignableFrom(value.getClass())) {
                mapBuilder.add("\"" + value + "\"");
            } else if (Number.class.isAssignableFrom(value.getClass())) {
                mapBuilder.add(String.valueOf(value));
            } else {
                throw new UnsupportedOperationException("Configuration map contains an entry of type " + value.getClass() + " which is not supported yet. Please file a bug report.");
            }
            mapBuilder.add(");\n");
        }
        mapBuilder.add("}}");
        return mapBuilder.build();
    }

    @Override
    @NonNull
    protected JavaFile generate() {
        String typeName = computeTypeName();
        String orderKey = BASE_ORDER_OPTION + "." + resourceName;
        int order = getContext().getConfiguration()
                .optionalValue(orderKey, value ->
                        value.map(Integer::parseInt).orElse(Ordered.HIGHEST_PRECEDENCE));
        TypeSpec type = TypeSpec.classBuilder(typeName)
                .addModifiers(PUBLIC)
                .superclass(MapPropertySource.class)
                .addMethod(MethodSpec.constructorBuilder()
                        .addStatement("super($S, $L)", resourceName, generateMap())
                        .build())
                .addMethod(MethodSpec.methodBuilder("getOrder")
                        .addModifiers(PUBLIC)
                        .returns(int.class)
                        .addStatement("return $L", order)
                        .build())
                .addAnnotation(Generated.class)
                .build();
        return javaFile(type);
    }

    private String computeTypeName() {
        return StringUtils.capitalize(resourceName.replaceAll("[^A-Za-z0-9]", "_") + "StaticPropertySource");
    }
}
