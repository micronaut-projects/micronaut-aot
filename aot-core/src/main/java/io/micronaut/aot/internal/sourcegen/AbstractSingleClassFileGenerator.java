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
package io.micronaut.aot.internal.sourcegen;

import com.squareup.javapoet.JavaFile;

import java.util.Collections;
import java.util.List;

/**
 * Base class for source generators which generate a single class file.
 */
public abstract class AbstractSingleClassFileGenerator extends AbstractSourceGenerator {
    protected AbstractSingleClassFileGenerator(SourceGenerationContext context) {
        super(context);
    }

    protected abstract JavaFile generate();

    @Override
    public final List<JavaFile> generateSourceFiles() {
        JavaFile file = generate();
        if (file == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(file);
    }
}
