# Building DelosDB

DelosDB uses Gradle as its supported build system.

The build is now Gradle-native for the active developer workflow: source generation, parser generation, module compilation, resource generation, jar assembly, smoke testing, jar metadata verification, and CI are all driven by Gradle.

## Requirements

- JDK 21 or newer
- The checked-in Gradle Wrapper

## Main commands

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

## What `./gradlew build` does

The main build lifecycle now performs these steps:

1. Generate `SanityState.java`.
2. Run JavaCC for the SQL parser.
3. Run JavaCC for the `ij` parser.
4. Compile the core JPMS modules.
5. Compile the small inherited build-time generators still needed by the source tree.
6. Generate split engine/client message bundles.
7. Generate `ClassSizeCatalogImpl`.
8. Generate product `info.properties` resources.
9. Assemble jars into `build/libs/`.
10. Verify that all expected runtime jars were assembled.
11. Verify release metadata, manifest attributes, and legal attribution files inside each jar.
12. Verify that public build docs expose only the Gradle workflow.
13. Verify that the artifact/module inventory docs match the current Gradle build model.
14. Run the embedded JDBC smoke test from compiled classes.
15. Run the embedded JDBC smoke test from assembled jars.

## Current jar outputs

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

## Release artifact metadata

Each runtime jar is expected to contain:

```text
META-INF/MANIFEST.MF
META-INF/LICENSE
META-INF/NOTICE
META-INF/NOTICE-FORK.md
```

The manifest includes DelosDB identity metadata, compatibility metadata for the Apache Derby 10.17.1.0 base, and the Git build revision when available.

Use this command to verify the release artifact shape:

```bash
./gradlew verifyReleaseArtifacts
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
```


## Artifact inventory

```bash
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
```

`printArtifactInventory` shows the planned Gradle subproject name, current JPMS module, source root, compile task, jar task, jar name, dependencies, generated inputs, and extraction risk for each artifact.

`verifyArtifactInventory` keeps `docs/ARTIFACTS.md`, `docs/MODULE-SPLIT-PLAN.md`, and `docs/BUILD-INVENTORY.md` aligned with the current Gradle build model. This is the guardrail before the real multi-project split begins.

## Smoke tests

```bash
./gradlew smoke
./gradlew smokeFromJars
```

`smoke` runs `dev/smoke.sql` through `ij` using the Gradle-built class directories.

`smokeFromJars` runs the same script from the assembled jars in `build/libs/`. This is important because it verifies the artifact shape, not only the compiler output tree.

## System info

```bash
./gradlew sysinfo
./gradlew sysinfoFromJars
```

`sysinfo` runs the inherited Derby `sysinfo` entry point from the Gradle-built class directories.

`sysinfoFromJars` runs it from the assembled jars in `build/libs/`.

## Notes

This is still a conservative baseline. Package names remain compatible with Apache Derby 10.17.1.0 while the project identity, build workflow, documentation, and release path move toward DelosDB.
