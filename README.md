# DelosDB

DelosDB is a working-name community fork of Apache Derby 10.17.1.0, a relational database implemented entirely in Java.

The goal is to preserve Derby's strongest property — a small, embeddable, standards-oriented Java SQL engine — while modernizing the project for current Java, GitHub-based development, CI, documentation, and release workflows.

## Status

Early fork/bootstrap stage.

DelosDB now uses **Gradle as the only supported build system**. The inherited Ant build surface has been removed from the supported developer workflow.


## Gradle subprojects

The extracted build subprojects are now `:delosdb-osgi-stub`, `:delosdb-commons`, `:delosdb-engine`, `:delosdb-client`, `:delosdb-tools`, `:delosdb-runner`, `:delosdb-optionaltools`, and `:delosdb-server`. They own compilation of the inherited OSGi stub, commons, engine, client, tools, runner, optional tools, and server JPMS modules, and they now own their runtime jars: `:delosdb-osgi-stub` assembles `osgi-framework-stub.jar`, `:delosdb-commons` assembles `derbyshared.jar`, `:delosdb-engine` assembles `derby.jar`, `:delosdb-client` assembles `derbyclient.jar`, `:delosdb-tools` assembles `derbytools.jar`, `:delosdb-runner` assembles `derbyrun.jar`, `:delosdb-optionaltools` assembles `derbyoptionaltools.jar`, and `:delosdb-server` assembles `derbynet.jar`. The root build coordinates product-level verification only.

Useful check:

```bash
./gradlew :delosdb-commons:compileDerbyCommons
./gradlew :delosdb-client:compileDerbyClient
./gradlew :delosdb-client:derbyClientJar
./gradlew :delosdb-tools:compileDerbyTools
./gradlew :delosdb-tools:derbyToolsJar
./gradlew :delosdb-runner:compileDerbyRunner
./gradlew :delosdb-runner:derbyRunJar
./gradlew :delosdb-optionaltools:compileDerbyOptionalTools
./gradlew :delosdb-engine:compileDerbyEngine
./gradlew :delosdb-engine:derbyJar
./gradlew :delosdb-server:compileDerbyServer :delosdb-server:derbyNetJar
```

## Relationship to Apache Derby

This project is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for additional fork attribution.

## Requirements

- JDK 21 or newer
- Gradle Wrapper from this repository
- Git

## Build

From the repository root:

```bash
./gradlew build
```

The `build` lifecycle compiles the inherited Derby/DelosDB modules, generates the required legacy sources/resources, assembles jars, verifies the public build surface, verifies jar release metadata and legal attribution files, and runs embedded smoke tests from both classes and assembled jars.

## Useful Gradle tasks

```bash
./gradlew build
./gradlew smoke
./gradlew smokeFromJars
./gradlew sysinfo
./gradlew sysinfoFromJars
./gradlew jars
./gradlew verifyJars
./gradlew verifyReleaseArtifacts
./gradlew verifyReleaseDistribution
./gradlew dist
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
```

Generated jars are written to:

```text
build/libs/
```

Binary distribution archives are written to:

```text
build/distributions/delosdb-0.1.0-dev-bin.zip
build/distributions/delosdb-0.1.0-dev-bin.tar.gz
```

## Current outputs

```text
build/libs/derby.jar
build/libs/derbyclient.jar
build/libs/derbynet.jar
build/libs/derbyoptionaltools.jar
build/libs/derbyrun.jar
build/libs/derbyshared.jar
build/libs/derbytools.jar
build/libs/osgi-framework-stub.jar
```

Each runtime jar includes DelosDB manifest metadata and the required legal attribution files under `META-INF/`.

## Documentation

- `docs/BUILDING.md` — developer build workflow
- `docs/BUILD-INVENTORY.md` — current Gradle build map and migration inventory
- `docs/ARTIFACTS.md` — runtime artifact inventory and planned subproject names
- `docs/MODULE-SPLIT-PLAN.md` — ordered plan for moving from root build to real Gradle subprojects
- `docs/ROADMAP.md` — modernization roadmap

## Shared Gradle release metadata

Runtime jar subprojects now use shared root-level helpers for legal files and manifest attributes. This keeps `LICENSE`, `NOTICE`, `NOTICE-FORK.md`, DelosDB version metadata, Apache Derby base-version metadata, build revision metadata, and JDK metadata consistent across all assembled jars.

### Gradle convention ownership

The root build centralizes shared Java compiler settings and release metadata. Extracted subprojects own their compile and jar tasks, but they consume the common `delosdbConfigureJavaCompile` and runtime manifest helpers from the root build so Java release, encoding, module-path handling, and jar metadata stay consistent.



## Binary distribution

DelosDB now has a verified binary distribution layout. The distribution includes the runtime jars, legal attribution files, documentation, a smoke-test SQL example, and small launcher scripts:

```bash
./gradlew dist
```

The launcher runs the inherited Derby command entry point through the DelosDB runtime classpath:

```bash
build/release/delosdb-0.1.0-dev/bin/delosdb sysinfo
```
