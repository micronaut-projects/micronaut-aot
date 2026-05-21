plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(mn.micronaut.core.bom))
    implementation(mn.micronaut.context)
    implementation(projects.micronautAotCore)

    testImplementation(mnTest.junit.jupiter.api)
    testRuntimeOnly(mnTest.junit.jupiter.engine)
    testRuntimeOnly(mnTest.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
