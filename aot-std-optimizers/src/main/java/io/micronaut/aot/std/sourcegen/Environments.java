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

/**
 * Constants used for configuration of environments.
 */
public final class Environments {
    public static final String POSSIBLE_ENVIRONMENTS_NAMES = "possible.environments";
    public static final String POSSIBLE_ENVIRONMENTS_DESCRIPTION = "The list of environment names that this application can possibly use at runtime.";
    public static final String POSSIBLE_ENVIRONMENTS_SAMPLE = "dev,prod,aws,gcs";
}
