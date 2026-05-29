# Building DelosDB

DelosDB uses Gradle as its supported build system.

The build is now Gradle-native for the active developer workflow: source generation, parser generation, module compilation, resource generation, jar assembly, smoke testing, and CI are all driven by Gradle.

## Requirements

- JDK 21 or newer
- The checked-in Gradle Wrapper

## Main commands

```bash
./gradlew build
./gradlew smoke
./gradlew sysinfo
./gradlew jars
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
10. Verify that public build docs expose only the Gradle workflow.
11. Run the embedded JDBC smoke test.

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

## Smoke test

```bash
./gradlew smoke
```

This runs `dev/smoke.sql` through `ij` using the Gradle-built classes.

## System info

```bash
./gradlew sysinfo
```

This runs the inherited Derby `sysinfo` entry point from the Gradle-built classes.

## Notes

This is still a conservative baseline. Package names remain compatible with Apache Derby 10.17.1.0 while the project identity, build workflow, documentation, and release path move toward DelosDB.
