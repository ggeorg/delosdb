# Building DelosDB

DelosDB uses Gradle as its official build system.

The inherited Ant build is no longer part of the DelosDB build path. Gradle now owns the core source generation, compilation, resource generation, jar assembly, and smoke-test lifecycle.

## Requirements

- JDK 21 or newer
- Gradle 8.x, or the checked-in Gradle Wrapper

## Main commands

```bash
./gradlew build
./gradlew smoke
./gradlew sysinfo
./gradlew jars
```

If the Gradle Wrapper has not been generated yet, use:

```bash
gradle build
gradle smoke
gradle sysinfo
gradle jars
```

## What Gradle now does directly

Gradle now handles the previously hidden legacy build steps directly:

- generates `SanityState.java`
- runs JavaCC for the SQL parser
- runs JavaCC for the `ij` parser
- compiles the Derby/DelosDB JPMS modules
- compiles the small legacy build generators still needed by the codebase
- generates split engine/client message bundles
- generates `ClassSizeCatalogImpl`
- generates product `info.properties` resources
- assembles jars into `build/libs/`
- runs the embedded JDBC smoke test

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

## Notes

This is the first Gradle-native baseline. It is intentionally conservative: package names remain compatible with Apache Derby 10.17.1.0 while the project identity, build system, documentation, and release path move toward DelosDB.
