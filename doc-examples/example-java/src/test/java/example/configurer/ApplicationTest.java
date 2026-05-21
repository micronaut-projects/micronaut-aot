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
package example.configurer;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ApplicationTest {
    @Test
    void applicationStartsWithContextConfigurer() {
        new Application();
        assertDoesNotThrow(() -> Application.main());
    }

    @Test
    void configurerAppliesContextConfiguration() {
        Application.MyConfigurer configurer = new Application.MyConfigurer();

        assertDoesNotThrow(() -> configurer.configure(ApplicationContext.builder()));
    }
}
