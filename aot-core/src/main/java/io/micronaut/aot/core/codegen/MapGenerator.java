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
import com.squareup.javapoet.TypeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.STATIC;

/**
 * A helper class to generate maps.
 */
public class MapGenerator {
    private int methodCount = 0;

    public final CodeBlock generateMap(TypeSpec.Builder builder, Map<String, Object> values) {
        CodeBlock.Builder mapBuilder = CodeBlock.builder();
        mapBuilder.add("new $T() {{\n", HashMap.class);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            mapBuilder.add("$L", CodeBlock.of("put($S, $L)", key, convertValueToSource(value, builder)));
            mapBuilder.add(";\n");
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
                return CodeBlock.of("$S", value).toString();
            } else if (Number.class.isAssignableFrom(valueClass) || Boolean.class.isAssignableFrom(valueClass)) {
                return convertNumberOrBoolean(valueClass, value);
            } else if (List.class.isAssignableFrom(valueClass)) {
                return generateListMethod((List<?>) value, builder);
            } else if (Map.class.isAssignableFrom(valueClass)) {
                return generateMapMethod((Map<?, ?>) value, builder);
            } else {
                throw new UnsupportedOperationException("Configuration map contains an entry of type " + valueClass + " which is not supported yet. Please file a bug report.");
            }
        }
    }

    private String convertNumberOrBoolean(Class<?> valueClass, Object value) {
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
    }

    private String generateListMethod(List<?> value, TypeSpec.Builder builder) {
        String methodName = "list" + methodCount++;
        MethodSpec.Builder listMethod = MethodSpec.methodBuilder(methodName)
                .addModifiers(PRIVATE, STATIC)
                .returns(List.class);
        if (value.isEmpty()) {
            listMethod.addStatement("return $T.emptyList()", Collections.class);
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

}
