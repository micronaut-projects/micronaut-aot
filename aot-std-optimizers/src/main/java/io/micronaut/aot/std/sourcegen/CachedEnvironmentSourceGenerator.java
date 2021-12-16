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

import io.micronaut.aot.core.AOTModule;
import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.codegen.AbstractCodeGenerator;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.optim.StaticOptimizations;

/**
 * Generates the code used to enable environment variables and system
 * properties caching in Micronaut.
 */
@AOTModule(
        id = CachedEnvironmentSourceGenerator.ID,
        description = CachedEnvironmentSourceGenerator.DESCRIPTION
)
public class CachedEnvironmentSourceGenerator extends AbstractCodeGenerator {
    public static final String ID = "cached.environment";
    public static final String DESCRIPTION = "Caches environment property values: environment properties will be deemed immutable after application startup.";

    @Override
    public void generate(@NonNull AOTContext context) {
        context.registerStaticInitializer(staticMethod("enableEnvironmentCaching", body ->
                body.addStatement("$T.cacheEnvironment()", StaticOptimizations.class)));
    }

}
