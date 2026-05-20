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
}
