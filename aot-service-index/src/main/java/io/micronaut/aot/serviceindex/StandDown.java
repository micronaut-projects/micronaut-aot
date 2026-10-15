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

import java.io.Serial;

/**
 * Why the class path is not one an index can describe: the generator generates nothing, and that is expected.
 */
final class StandDown extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param reason why, completing "No service index was generated because"
     */
    StandDown(String reason) {
        super(reason, null, false, false);
    }
}
