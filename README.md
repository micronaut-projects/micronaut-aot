# Micronaut AOT

[![Maven Central](https://img.shields.io/maven-central/v/io.micronaut.aot/micronaut-aot.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22io.micronaut.aot%22%20AND%20a:%22micronaut-aot%22)
[![Build Status](https://github.com/micronaut-projects/micronaut-aot/workflows/Java%20CI/badge.svg)](https://github.com/micronaut-projects/micronaut-aot/actions)

Micronaut AOT is an ahead-of-time optimizer for Micronaut projects, typically used by build plugins to perform build time optimizations.

## Documentation

See the [Documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/) for more information. 

See the [Snapshot Documentation](https://micronaut-projects.github.io/micronaut-aot/snapshot/guide/) for the current development docs.

## Snapshots and Releases

Snapshots are automatically published to [Sonatype Snapshots](https://s01.oss.sonatype.org/content/repositories/snapshots/io/micronaut/) using [Github Actions](https://github.com/micronaut-projects/micronaut-aot/actions).

See the documentation in the [Micronaut Docs](https://docs.micronaut.io/latest/guide/index.html#usingsnapshots) for how to configure your build to use snapshots.

Releases are published to Maven Central via [Github Actions](https://github.com/micronaut-projects/micronaut-aot/actions).

Releases are completely automated. To perform a release use the following steps:

* [Publish the draft release](https://github.com/micronaut-projects/micronaut-aot/releases). There should be already a draft release created, edit and publish it. The Git Tag should start with `v`. For example `v1.0.0`.
* [Monitor the Workflow](https://github.com/micronaut-projects/micronaut-aot/actions?query=workflow%3ARelease) to check it passed successfully.
* If everything went fine, [publish to Maven Central](https://github.com/micronaut-projects/micronaut-aot/actions?query=workflow%3A"Maven+Central+Sync").
* Celebrate!
