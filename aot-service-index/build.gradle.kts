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

description = "Generates micronaut-core's service index for a packaged application at build time"

repositories {
    // micronaut-core 5.3.0-SNAPSHOT for the tests only, until a micronaut-core release has ServiceIndexBuilder.
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent {
            snapshotsOnly()
        }
        content {
            includeModule("io.micronaut", "micronaut-core")
        }
    }
}

// The main class of the applications the tests start: it looks services up the way an application does and prints
// what it finds. It is compiled against the catalog's micronaut-core, whose lookup API is the same in 5.3, and copied
// into the applications; it is never on this module's class paths.
val probe: SourceSet = sourceSets.create("probe")

// The class paths of the applications the tests generate an index for. They are passed to the tests as files and are
// never on the test class path: the generator loads micronaut-core from the class path it is given, never from its
// caller's own.
val serviceIndexCore: Configuration = configurations.create("serviceIndexCore") {
    isCanBeConsumed = false
}
val serviceIndexLogging: Configuration = configurations.create("serviceIndexLogging") {
    isCanBeConsumed = false
    isTransitive = false
}
val legacyCore: Configuration = configurations.create("legacyCore") {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    // Annotations only, none of which is needed at run time: @Internal and nullability. micronaut-core is
    // not transitive here, so that the main code compiles against nothing but the JDK besides them.
    compileOnly(mn.micronaut.core) {
        isTransitive = false
    }
    compileOnly(mn.jspecify)

    "probeCompileOnly"(mn.micronaut.core)

    // micronaut-core with ServiceIndexBuilder (micronaut-core#13403), with slf4j-api.
    serviceIndexCore(libs.service.index.micronaut.core)
    serviceIndexLogging(libs.service.index.slf4j.simple)
    // A micronaut-core without ServiceIndexBuilder.
    legacyCore(libs.service.index.legacy.micronaut.core)

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

tasks.named<JavaCompile>("compileProbeJava") {
    options.release.set(25)
}

// PublishedMetadataTest reads what the module publishes and what its main code compiles against. The marker
// tells it that the build runs it, so that a property this build no longer passes fails the test instead of
// skipping it.
val publishedPom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication").map { it.destination }
val publishedModule = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenPublication")
    .flatMap { it.outputFile }
val mainCompileClasspath = sourceSets.main.map { it.compileClasspath }
val probeClasses = probe.output.classesDirs

tasks.named<Test>("test") {
    inputs.files(publishedPom).withPropertyName("publishedPom").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(publishedModule).withPropertyName("publishedModule").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(mainCompileClasspath).withPropertyName("mainCompileClasspath").withNormalizer(ClasspathNormalizer::class)
    inputs.files(probeClasses).withPropertyName("probe").withNormalizer(ClasspathNormalizer::class)
    inputs.files(serviceIndexCore).withPropertyName("serviceIndexCore").withNormalizer(ClasspathNormalizer::class)
    inputs.files(serviceIndexLogging).withPropertyName("serviceIndexLogging").withNormalizer(ClasspathNormalizer::class)
    inputs.files(legacyCore).withPropertyName("legacyCore").withNormalizer(ClasspathNormalizer::class)
    systemProperty("aot.serviceindex.build", "true")
    doFirst {
        systemProperty("aot.serviceindex.pom", publishedPom.get().absolutePath)
        systemProperty("aot.serviceindex.module", publishedModule.get().asFile.absolutePath)
        systemProperty("aot.serviceindex.compileClasspath", mainCompileClasspath.get().asPath)
        systemProperty("aot.serviceindex.probe", probeClasses.asPath)
        systemProperty("aot.serviceindex.core", serviceIndexCore.asPath)
        systemProperty("aot.serviceindex.logging", serviceIndexLogging.asPath)
        systemProperty("aot.serviceindex.legacyCore", legacyCore.asPath)
    }
}
