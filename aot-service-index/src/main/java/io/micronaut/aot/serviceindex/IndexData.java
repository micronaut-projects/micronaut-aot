/*
 * Copyright 2017-2026 original authors
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
package io.micronaut.aot.serviceindex;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What a {@code ServiceIndex} holds, as plain JDK collections, in the order it iterates.
 *
 * @param micronautServices the entries under {@code META-INF/micronaut/<type>/} of each type
 * @param standardServices  the names the {@code META-INF/services/<type>} files list, of each indexed type
 * @param classPath         the class path the index lists, or {@code null} if it lists none
 */
record IndexData(Map<String, List<String>> micronautServices,
                 Map<String, List<String>> standardServices,
                 @Nullable List<Entry> classPath) {

    /**
     * How a second index differs from this one, order included.
     *
     * @param actual the other index
     * @return the first difference, or {@code null} if there is none
     */
    @Nullable String difference(IndexData actual) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * An entry of the class path an index lists.
     *
     * @param name the file name of the entry when the application runs
     * @param size its size in bytes, or a negative number to not compare it
     */
    record Entry(String name, long size) {
    }
}
