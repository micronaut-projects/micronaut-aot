plugins {
    id("io.micronaut.build.internal.bom")
}

micronautBuild {
    binaryCompatibility {
        // TODO required for now. Remove after Micronaut 4 release
        baselineVersion.set("2.0.0-M4")
        enabled.set(true)
    }
}
