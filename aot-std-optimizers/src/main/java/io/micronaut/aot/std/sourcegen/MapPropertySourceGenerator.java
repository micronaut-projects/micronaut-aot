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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.micronaut.aot.std.sourcegen.MapPropertySourceGenerator.BASE_ORDER_OPTION;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.element.Modifier.STATIC;

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
    private int methodCount = 0;

    public MapPropertySourceGenerator(
            String resourceName,
            Map<String, Object> values) {
        this.resourceName = resourceName;
        this.values = values;
    }

    private CodeBlock generateMap(TypeSpec.Builder builder) {
        CodeBlock.Builder mapBuilder = CodeBlock.builder();
        mapBuilder.add("new $T() {{\n", HashMap.class);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            mapBuilder.add("put(\"" + key + "\", " + convertValueToSource(value, builder));
            mapBuilder.add(");\n");
        }
        mapBuilder.add("}}");
        return mapBuilder.build();
    }

    private String convertValueToSource(Object value, TypeSpec.Builder builder) {
        if (value == null) {
             return "null";
        } else {
            Class<?> valueClass = value.getClass();
            if (CharSequence.class.isAssignableFrom(valueClass)) {
                return "\"" + value + "\"";
            } else if (Number.class.isAssignableFrom(valueClass) || Boolean.class.isAssignableFrom(valueClass)) {
                String format = String.valueOf(value);
                String prefix = "";
                String appendix = "";
                if (Long.class.equals(valueClass)) {
                    appendix = "L";
                } else if (Double.class.equals(valueClass)) {
                    appendix = "D";
                } else if (Float.class.equals(valueClass)) {
                    appendix = "F";
                } else if (Byte.class.equals(valueClass)) {
                    prefix = "(byte) ";
                } else if (Short.class.equals(valueClass)) {
                    prefix = "(short) ";
                }
                return prefix + format + appendix;
            } else if (List.class.isAssignableFrom(valueClass) ) {
                return generateListMethod((List<?>) value, builder);
            } else if (Map.class.isAssignableFrom(valueClass) ) {
                return generateMapMethod((Map<?, ?>) value, builder);
            } else {
                throw new UnsupportedOperationException("Configuration map contains an entry of type " + valueClass + " which is not supported yet. Please file a bug report.");
            }
        }
    }

    private String generateListMethod(List<?> value, TypeSpec.Builder builder) {
        String methodName = "list" + methodCount++;
        MethodSpec.Builder listMethod = MethodSpec.methodBuilder(methodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(List.class);
        if (value.isEmpty()) {
            listMethod.addStatement("return $.emptyList()", Collections.class);
        } else if (value.size() == 1) {
            listMethod.addStatement("return $T.singletonList($L)", Collections.class, convertValueToSource(value.get(0), builder));
        } else {
            listMethod.addStatement("$T result = new $T<>($L)", List.class, ArrayList.class, value.size());
            for (Object o : value) {
                listMethod.addStatement("result.add(" + convertValueToSource(o, builder) + ")");
            }
            listMethod.addStatement("return result");
        }
        builder.addMethod(listMethod.build());
        return methodName + "()";
    }

    private String generateMapMethod(Map<?, ?> value, TypeSpec.Builder builder) {
        String methodName = "map" + methodCount++;
        MethodSpec.Builder mapMethod = MethodSpec.methodBuilder(methodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(Map.class);
        if (value.isEmpty()) {
            mapMethod.addStatement("return $.emptyMap()", Collections.class);
        } else if (value.size() == 1) {
            Map.Entry<?, ?> entry = value.entrySet().iterator().next();
            mapMethod.addStatement("return $T.singletonMap($L, $L)", Collections.class, convertValueToSource(entry.getKey(), builder), convertValueToSource(entry.getValue(), builder));
        } else {
            mapMethod.addStatement("$T result = new $T<>($L)", Map.class, LinkedHashMap.class, value.size());
            for (Map.Entry<?, ?> entry : value.entrySet()) {
                mapMethod.addStatement("result.put(" + convertValueToSource(entry.getKey(), builder) + ", " + convertValueToSource(entry.getValue(), builder) + ")");
            }
            mapMethod.addStatement("return result");
        }
        builder.addMethod(mapMethod.build());
        return methodName + "()";
    }

    @Override
    @NonNull
    protected JavaFile generate() {
        String typeName = computeTypeName();
        String orderKey = BASE_ORDER_OPTION + "." + resourceName;
        int order = getContext().getConfiguration()
                .optionalValue(orderKey, value ->
                        value.map(Integer::parseInt).orElse(Ordered.HIGHEST_PRECEDENCE));
        TypeSpec.Builder typeBuilder = TypeSpec.classBuilder(typeName)
                .addModifiers(PUBLIC)
                .superclass(MapPropertySource.class);
        typeBuilder.addMethod(MethodSpec.constructorBuilder()
                        .addStatement("super($S, $L)", resourceName, generateMap(typeBuilder))
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
        return StringUtils.capitalize(resourceName.replaceAll("[^A-Za-z0-9]", "_") + "StaticPropertySource");
    }
}
