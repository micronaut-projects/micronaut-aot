plugins {
    id("io.micronaut.build.internal.bom")
}

val isGreaterThan2_0 = provider {
    version.toString()
        .split('.')
        .let { it[0].toInt() >= 2 && (it[1].toInt() > 0) }
}

micronautBuild {
    binaryCompatibility.enabled.set(isGreaterThan2_0)
}
