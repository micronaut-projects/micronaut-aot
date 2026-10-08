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

description = "Compiles an application's logback.xml into a Logback Configurator at build time"

// The Logback front end of LogbackPrecompiler, and the classes it copies into applications. It is compiled against
// Logback but never put on this module's class path: it ships as a jar resource and runs only in an isolated class
// loader next to the application's own Logback jars. So the main code has no Logback dependency and the module
// publishes none.
val logbackFrontend: SourceSet = sourceSets.create("logbackFrontend")

dependencies {
    // Annotations only, none of which is needed at run time: @Internal and nullability. micronaut-core is
    // not transitive here, so that the main code compiles against nothing but the JDK besides them.
    compileOnly(mn.micronaut.core) {
        isTransitive = false
    }
    compileOnly(mn.jspecify)

    "logbackFrontendCompileOnly"(libs.logback.precompiler.classic)

    testImplementation(platform(mnTest.boms.junit))
    testImplementation(mnTest.junit.jupiter.api)
    testImplementation(mnTest.junit.jupiter.params)
    // The differential tests configure Logback with Joran and with the generated classes.
    testImplementation(libs.logback.precompiler.classic)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
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

tasks.named<JavaCompile>("compileLogbackFrontendJava") {
    options.release.set(25)
    // JoranFallback and ClassPathGuard are copied into applications verbatim: no invokedynamic string
    // concatenation in them.
    options.compilerArgs.add("-XDstringConcat=inline")
}

val logbackFrontendJar = tasks.register<Jar>("logbackFrontendJar") {
    group = BasePlugin.BUILD_GROUP
    description = "Packages the Logback front end that the module carries as a resource"
    archiveFileName.set("logback-frontend.jar")
    destinationDirectory.set(layout.buildDirectory.dir("logback-frontend"))
    from(logbackFrontend.output)
}

tasks.named<ProcessResources>("processResources") {
    from(logbackFrontendJar) {
        into("META-INF/micronaut-aot")
    }
}

tasks.named<Jar>("sourcesJar") {
    from(logbackFrontend.allSource)
}

// PublishedMetadataTest reads what the module publishes and what its main code compiles against. The marker
// tells it that the build runs it, so that a property this build no longer passes fails the test instead of
// skipping it.
val publishedPom = tasks.named<GenerateMavenPom>("generatePomFileForMavenPublication").map { it.destination }
val publishedModule = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenPublication")
    .flatMap { it.outputFile }
val mainCompileClasspath = sourceSets.main.map { it.compileClasspath }

tasks.named<Test>("test") {
    inputs.files(publishedPom).withPropertyName("publishedPom").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(publishedModule).withPropertyName("publishedModule").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(mainCompileClasspath).withPropertyName("mainCompileClasspath").withNormalizer(ClasspathNormalizer::class)
    systemProperty("aot.logback.build", "true")
    doFirst {
        systemProperty("aot.logback.pom", publishedPom.get().absolutePath)
        systemProperty("aot.logback.module", publishedModule.get().asFile.absolutePath)
        systemProperty("aot.logback.compileClasspath", mainCompileClasspath.get().asPath)
    }
}
