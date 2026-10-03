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
plugins {
    // Not aot-project: this module publishes no dependency and no platform.
    id("io.micronaut.build.internal.aot-base")
}

description = "Rewrites the class files of an application's runtime class path at build time"

dependencies {
    // @Internal only, which is not needed at run time. micronaut-core is not transitive here, so that the main
    // code compiles against nothing but the JDK besides it.
    compileOnly(mn.micronaut.core) {
        isTransitive = false
    }

    testImplementation(platform(mnTest.boms.junit))
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.params)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
    testRuntimeOnly(mnTest.junit.platform.launcher)
}

micronautBuild {
    binaryCompatibility {
        // The module is new in 3.2.0: there is nothing to compare with before that release.
        enabledAfter("3.2.0")
    }
    descriptor {
        // The generated descriptor extends a type of micronaut-module-info. This jar is a build tool library
        // that is never on an application class path, and none of its classes may need anything but the JDK.
        enabled.set(false)
    }
}

// PublishedMetadataTest reads what the module publishes and what its main code compiles against. The marker
// tells it that the build runs it, so that a property this build no longer passes fails the test instead of
// skipping it.
val publishedPom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication").map { it.destination }
val publishedModule = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenPublication")
    .flatMap { it.outputFile }
val mainCompileClasspath = sourceSets.main.map { it.compileClasspath }

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("class-path-corpus")
    }
    inputs.files(publishedPom).withPropertyName("publishedPom").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(publishedModule).withPropertyName("publishedModule").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(mainCompileClasspath).withPropertyName("mainCompileClasspath").withNormalizer(ClasspathNormalizer::class)
    systemProperty("aot.bytecode.build", "true")
    doFirst {
        systemProperty("aot.bytecode.pom", publishedPom.get().absolutePath)
        systemProperty("aot.bytecode.module", publishedModule.get().asFile.absolutePath)
        systemProperty("aot.bytecode.compileClasspath", mainCompileClasspath.get().asPath)
    }
}

// The class path corpus: the runtime class paths of real Micronaut applications, which classPathCorpusTest runs
// ClassPathTransform over. Resolving them downloads a few hundred jars, so the task is not part of check: the
// "Class path corpus" workflow runs it. Each configuration is one application, or one family of Micronaut
// modules, on the Micronaut platform that micronaut-runner's sample applications use.
val corpusApplications = mapOf(
    "benchmark-large" to listOf(
        "io.micronaut:micronaut-http-server-netty",
        "io.micronaut:micronaut-http-client",
        "io.micronaut:micronaut-management",
        "io.micronaut.serde:micronaut-serde-jackson",
        "ch.qos.logback:logback-classic"),
    "hello-netty" to listOf(
        "io.micronaut:micronaut-http-server-netty",
        "io.micronaut.serde:micronaut-serde-jackson",
        "ch.qos.logback:logback-classic"),
    "data-jdbc" to listOf(
        "io.micronaut.data:micronaut-data-jdbc",
        "io.micronaut.sql:micronaut-jdbc-hikari",
        "io.micronaut.flyway:micronaut-flyway"),
    "http-client" to listOf(
        "io.micronaut:micronaut-http-client",
        "io.micronaut.reactor:micronaut-reactor-http-client"),
    "kotlin" to listOf(
        "io.micronaut.kotlin:micronaut-kotlin-runtime",
        "org.jetbrains.kotlinx:kotlinx-coroutines-core"),
    "security-jwt" to listOf(
        "io.micronaut.security:micronaut-security-jwt"))

val corpusClassPaths = corpusApplications.map { (application, modules) ->
    val configuration = configurations.create("corpus" + application.split('-')
        .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }) {
        isCanBeConsumed = false
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        }
    }
    dependencies {
        configuration(platform(libs.corpus.micronaut.platform))
        modules.forEach { configuration(it) }
    }
    application to configuration
}

tasks.register<Test>("classPathCorpusTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs ClassPathTransform over the runtime class paths of real Micronaut applications"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("class-path-corpus")
    }
    corpusClassPaths.forEach { (application, configuration) ->
        inputs.files(configuration).withPropertyName("corpus.$application").withNormalizer(ClasspathNormalizer::class)
    }
    // The test prints what each application's class path holds and what the transform did to it, and it is the
    // only run of the corpus: neither an up-to-date check nor predictive test selection may skip it.
    outputs.upToDateWhen { false }
    extensions.findByType<com.gradle.develocity.agent.gradle.test.DevelocityTestConfiguration>()
        ?.predictiveTestSelection { enabled.set(false) }
    testLogging.showStandardStreams = true
    doFirst {
        corpusClassPaths.forEach { (application, configuration) ->
            systemProperty("aot.bytecode.corpus.$application", configuration.asPath)
        }
    }
}
