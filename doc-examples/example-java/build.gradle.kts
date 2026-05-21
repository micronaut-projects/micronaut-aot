plugins {
    `java-library`
    jacoco
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

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}
