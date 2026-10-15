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
package io.micronaut.aot.serviceindex.probe;

import io.micronaut.core.io.service.MicronautMetaServiceLoaderUtils;
import io.micronaut.core.io.service.SoftServiceLoader;
import io.micronaut.core.optim.StaticOptimizations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The main class of the applications the tests start. It does what an application does first, which runs the
 * static optimization loaders, then looks up the services its arguments name, the way micronaut-core's own lookups
 * do, and prints the names it finds, sorted:
 * <ul>
 *     <li>{@code services:<type>}: the names of the {@code META-INF/services/<type>} files;</li>
 *     <li>{@code micronaut:<type>}: the entries of {@code META-INF/micronaut/<type>/};</li>
 *     <li>{@code optimization:<class>}: whether a loader set a static optimization of that class.</li>
 * </ul>
 * It ends with {@value #END}, so that a test knows it ran to the end.
 */
public final class ServiceIndexProbe {

    /** The last line the probe prints. */
    public static final String END = "END";

    private ServiceIndexProbe() {
    }

    /**
     * Runs the lookups.
     *
     * @param args the lookups
     * @throws Exception if a lookup fails
     */
    public static void main(String[] args) throws Exception {
        // The first thing a Micronaut application does: it runs the loaders, which register the index.
        StaticOptimizations.isEnvironmentCached();
        ClassLoader classLoader = ServiceIndexProbe.class.getClassLoader();
        StringBuilder out = new StringBuilder();
        for (String arg : args) {
            int colon = arg.indexOf(':');
            String kind = arg.substring(0, colon);
            String name = arg.substring(colon + 1);
            List<String> found = new ArrayList<>();
            switch (kind) {
                case "services" -> SoftServiceLoader.newCollector(name, null, classLoader, entry -> entry).collect(found);
                case "micronaut" -> found.addAll(MicronautMetaServiceLoaderUtils.findMicronautMetaServiceEntries(classLoader, name));
                case "optimization" -> found.add(String.valueOf(
                        StaticOptimizations.get(Class.forName(name, false, classLoader)).isPresent()));
                default -> throw new IllegalArgumentException(arg);
            }
            Collections.sort(found);
            out.append(arg).append(' ').append(found).append('\n');
        }
        System.out.print(out);
        System.out.println(END);
    }
}
