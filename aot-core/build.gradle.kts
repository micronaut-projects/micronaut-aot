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
plugins {
    id("io.micronaut.build.internal.aot-module")
}

description = "Micronaut AOT source generators"

//                      WARNING
// While the architecture of the optimizer itself would
// benefit from using Micronaut Inject, we can't do it because
// we perform analysis of the user application classpath and this
// analysis must happen within the same classloaders. It means
// that when we would build the application context of Micronaut
// AOT, we would see customizations from the user environment,
// which we don't want.

dependencies {
    // Most AOT dependencies need to be compile only API
    // because the Micronaut runtime that is going to be
    // used is the one from the application being analyzed
    compileOnlyApi(mn.micronaut.context)
    compileOnlyApi(mn.micronaut.core.reactive)

    api(libs.javapoet)

    compileOnly(mn.logback)

    testFixturesImplementation(libs.javapoet)
    testFixturesImplementation(mn.spock)

    testImplementation(mn.spock)

    // Runtime libraries used to introspect Micronaut context
    testRuntimeOnly(mn.micronaut.context)
    testRuntimeOnly(mn.micronaut.core.reactive)
}
