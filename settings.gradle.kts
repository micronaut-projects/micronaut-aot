pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    includeBuild("build-logic") {
        name = "aot-build-logic"
    }
}

plugins {
    id("io.micronaut.build.shared.settings") version "5.1.2"
}

enableFeaturePreview("VERSION_CATALOGS")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "aot-parent"

include("aot-core")
include("aot-std-optimizers")
include("aot-api")
include("aot-cli")

val micronautVersion = providers.gradleProperty("micronautVersion")
        .forUseAtConfigurationTime()

dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            content {
                includeGroup("io.micronaut")
            }
        }
        mavenCentral()
    }

    versionCatalogs {
        create("mn") {
            from(micronautVersion.map { v -> "io.micronaut:micronaut-bom:${v}" }.get())
        }
    }
}
