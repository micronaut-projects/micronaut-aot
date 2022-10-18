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
    id("io.micronaut.build.shared.settings") version "6.0.1"
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "aot-parent"

include("aot-core")
include("aot-std-optimizers")
include("aot-api")
include("aot-cli")

configure<io.micronaut.build.MicronautBuildSettingsExtension> {
    importMicronautCatalog()
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { setUrl("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
    }
}

