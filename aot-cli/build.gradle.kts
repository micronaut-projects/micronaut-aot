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
    application
}

description = "A CLI tool leveraging Micronaut AOT"

val testAotRuntime by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    extendsFrom(configurations.testRuntimeClasspath.get())
}

dependencies {
    implementation(projects.aotApi)
    implementation(mn.picocli)

    testImplementation(mn.spock)
    testImplementation(projects.aotCore)
    testImplementation(projects.aotStdOptimizers)
    testImplementation(mn.micronaut.core)

    testAotRuntime(mn.micronaut.context)
    testAotRuntime(mn.micronaut.inject)
    testAotRuntime(projects.aotStdOptimizers)
}

tasks.named<Test>("test") {
    inputs.files(testAotRuntime)
    doFirst {
        systemProperty("aot.runtime", testAotRuntime.asPath)
    }
}

application {
    mainClass.set("io.micronaut.aot.cli.Main")
}

val versionInfo = tasks.register<io.micronaut.build.internal.VersionInfo>("versionInfo") {
    version.set(project.version as String)
    outputDirectory.set(layout.buildDirectory.dir("generated/version-info"))
}

sourceSets {
    main {
        resources.srcDir(versionInfo)
    }
}
