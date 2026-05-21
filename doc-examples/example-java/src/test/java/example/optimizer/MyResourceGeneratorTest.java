/*
 * Copyright 2017-2021 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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
package example.optimizer;

import io.micronaut.aot.core.AOTContext;
import io.micronaut.aot.core.Configuration;
import io.micronaut.aot.core.Runtime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MyResourceGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void generatesConfiguredResource() throws Exception {
        MyResourceGenerator generator = new MyResourceGenerator();
        generator.generate(contextWithMessage("Hello, docs!"));

        assertEquals(
            "Hello, docs!",
            Files.readString(temporaryDirectory.resolve("hello.txt"))
        );
    }

    private AOTContext contextWithMessage(String message) {
        Configuration configuration = configurationWithMessage(message);
        return (AOTContext) Proxy.newProxyInstance(
            AOTContext.class.getClassLoader(),
            new Class<?>[] { AOTContext.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getConfiguration" -> configuration;
                case "registerGeneratedResource" -> {
                    String path = (String) args[0];
                    @SuppressWarnings("unchecked")
                    Consumer<? super File> consumer = (Consumer<? super File>) args[1];
                    consumer.accept(temporaryDirectory.resolve(path).toFile());
                    yield null;
                }
                case "getRuntime" -> Runtime.JIT;
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static Configuration configurationWithMessage(String message) {
        return (Configuration) Proxy.newProxyInstance(
            Configuration.class.getClassLoader(),
            new Class<?>[] { Configuration.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "containsKey" -> "greeter.message".equals(args[0]);
                case "mandatoryValue" -> message;
                case "optionalValue" -> {
                    @SuppressWarnings("unchecked")
                    Function<Optional<String>, ?> producer = (Function<Optional<String>, ?>) args[1];
                    yield producer.apply(Optional.of(message));
                }
                case "getRuntime" -> Runtime.JIT;
                default -> throw new UnsupportedOperationException(method.getName());
            }
        );
    }
}
