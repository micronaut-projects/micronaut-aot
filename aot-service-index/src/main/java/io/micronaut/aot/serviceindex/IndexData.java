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
import java.util.Objects;

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

    private static final int REPORTED = 10;

    /**
     * How a second index differs from this one, order included: micronaut-core loads services in the order the
     * index iterates them.
     *
     * @param actual the other index
     * @return the first difference, or {@code null} if there is none
     */
    @Nullable String difference(IndexData actual) {
        String difference = difference("META-INF/micronaut", micronautServices, actual.micronautServices);
        if (difference == null) {
            difference = difference("META-INF/services", standardServices, actual.standardServices);
        }
        if (difference == null && !Objects.equals(classPath, actual.classPath)) {
            difference = mismatch("the class path is ", actual.classPath, classPath);
        }
        return difference;
    }

    /**
     * @return the number of {@code META-INF/micronaut} entries
     */
    int micronautEntries() {
        return micronautServices.values().stream().mapToInt(List::size).sum();
    }

    /**
     * @return the number of {@code META-INF/services} names
     */
    int standardNames() {
        return standardServices.values().stream().mapToInt(List::size).sum();
    }

    private static @Nullable String difference(String location, Map<String, List<String>> expected,
                                               Map<String, List<String>> actual) {
        List<String> expectedTypes = List.copyOf(expected.keySet());
        List<String> actualTypes = List.copyOf(actual.keySet());
        if (!expectedTypes.equals(actualTypes)) {
            return mismatch("the " + location + " types are ", actualTypes, expectedTypes);
        }
        for (String type : expectedTypes) {
            if (!expected.get(type).equals(actual.get(type))) {
                return mismatch("the names of " + location + "/" + type + " are ", actual.get(type),
                        expected.get(type));
            }
        }
        return null;
    }

    private static String mismatch(String subject, @Nullable List<?> actual, @Nullable List<?> expected) {
        return subject + abbreviated(actual) + " instead of " + abbreviated(expected);
    }

    private static String abbreviated(@Nullable List<?> list) {
        if (list == null || list.size() <= REPORTED) {
            return String.valueOf(list);
        }
        return list.subList(0, REPORTED).toString().replaceFirst("]$", ", and " + (list.size() - REPORTED) + " more]");
    }

    /**
     * An entry of the class path an index lists.
     *
     * @param name the file name of the entry when the application runs
     * @param size its size in bytes, or a negative number to not compare it
     */
    record Entry(String name, long size) {

        @Override
        public String toString() {
            return name + " (" + size + ")";
        }
    }
}
