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
package example.bytecode;

import io.micronaut.aot.bytecode.ClassPathTransform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * What a Micronaut build plugin does with the class path transform when it packages a fat JAR or a Docker image.
 */
final class ClassPathRewriting {

    private ClassPathRewriting() {
    }

    static List<Path> stripThirdPartyJars(List<Path> runtimeClasspath,
                                          List<Path> thirdPartyJars,
                                          Path outputDirectory,
                                          Path reportFile,
                                          Consumer<String> log,
                                          Consumer<String> warn) throws IOException {
        //tag::strip[]
        ClassPathTransform.Result result = ClassPathTransform.run(
            ClassPathTransform.Request.builder()
                .classPath(runtimeClasspath)
                .outputDirectory(outputDirectory)
                .stripLocalVariables(thirdPartyJars)
                .build());
        log.accept(result.summary());
        result.warnings().forEach(warn);
        Files.writeString(reportFile, result.report());
        List<Path> packaged = result.classPath();
        //end::strip[]
        return packaged;
    }
}
