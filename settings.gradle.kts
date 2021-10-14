pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "4.2.1"
}

enableFeaturePreview("VERSION_CATALOGS")

rootProject.name = "aot-parent"

include("aot-core")

val micronautVersion = providers.gradleProperty("micronautVersion")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal()
    }
    versionCatalogs {
        create("mn") {
            from("io.micronaut:micronaut-bom:${micronautVersion.get()}")
        }
    }
}
