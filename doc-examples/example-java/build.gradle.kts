plugins {
    `java-library`
    jacoco
}

repositories {
    mavenCentral()
}

// The runtime class path of the application that the Logback precompiler example compiles for. It is a class
// path of its own: the precompiler takes Logback from the paths it is given, never from its caller's class path.
val logbackApplicationRuntime: Configuration = configurations.create("logbackApplicationRuntime") {
    isCanBeConsumed = false
}

dependencies {
    implementation(platform(mn.micronaut.core.bom))
    implementation(mn.micronaut.context)
    implementation(projects.micronautAotCore)
    implementation(projects.micronautAotLogback)

    logbackApplicationRuntime(libs.logback.precompiler.classic)

    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
    testRuntimeOnly(mnTest.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val applicationRuntime: FileCollection = logbackApplicationRuntime
    inputs.files(applicationRuntime).withPropertyName("logbackApplicationRuntime")
        .withNormalizer(ClasspathNormalizer::class)
    doFirst {
        systemProperty("example.logback.runtimeClasspath", applicationRuntime.asPath)
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}
