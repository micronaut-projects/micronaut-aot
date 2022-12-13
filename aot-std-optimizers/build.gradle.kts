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
    id("io.micronaut.build.internal.aot-project")
}

description = "Standard optimizers implemented directly as part of Micronaut AOT"

dependencies {
    // Most AOT dependencies need to be compile only API
    // because the Micronaut runtime that is going to be
    // used is the one from the application being analyzed
    compileOnlyApi(mn.micronaut.context)
    compileOnlyApi(mn.micronaut.core.reactive)

    compileOnlyApi(projects.aotCore)
    compileOnly(mn.logback.classic) {
        version {
            require(libs.versions.logback.future.get())
        }
    }

    testImplementation(testFixtures(projects.aotCore))
    testImplementation(mn.micronaut.context)
    testImplementation(mn.micronaut.core.reactive)
    testCompileOnly(projects.aotCore)
    testImplementation(mn.logback.classic) {
        version {
            require(libs.versions.logback.future.get())
        }
    }
    testRuntimeOnly(mn.snakeyaml)
}
