# DelosDB

DelosDB is a working-name community fork of Apache Derby 10.17.1.0, a relational database implemented entirely in Java.

The goal is to preserve Derby's strongest property — a small, embeddable, standards-oriented Java SQL engine — while modernizing the project for current Java, GitHub-based development, CI, documentation, and release workflows.

## Status

Early fork/bootstrap stage.

DelosDB now uses **Gradle as the only supported build system**. The inherited Ant build surface has been removed from the supported developer workflow.


## Gradle subprojects

The extracted build subprojects are now `:delosdb-commons`, `:delosdb-engine`, `:delosdb-client`, `:delosdb-tools`, `:delosdb-runner`, `:delosdb-optionaltools`, and `:delosdb-server`. They own compilation of the inherited commons, engine, client, tools, runner, optional tools, and server JPMS modules, and they now own their runtime jars: `:delosdb-commons` assembles `derbyshared.jar`, `:delosdb-engine` assembles `derby.jar`, `:delosdb-client` assembles `derbyclient.jar`, `:delosdb-tools` assembles `derbytools.jar`, `:delosdb-runner` assembles `derbyrun.jar`, `:delosdb-optionaltools` assembles `derbyoptionaltools.jar`, and `:delosdb-server` assembles `derbynet.jar`. The root build coordinates product-level verification and the remaining OSGi stub jar only.

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
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
```

Generated jars are written to:

```text
build/libs/
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
