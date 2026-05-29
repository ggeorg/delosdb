# DelosDB

DelosDB is a working-name community fork of Apache Derby 10.17.1.0, a relational database implemented entirely in Java.

The goal is to preserve Derby's strongest property — a small, embeddable, standards-oriented Java SQL engine — while modernizing the project for current Java, GitHub-based development, CI, documentation, and release workflows.

## Status

Early fork/bootstrap stage.

The current build direction is **Gradle-only** for DelosDB. The old inherited Ant build is no longer the official build path.

## Relationship to Apache Derby

This project is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for additional fork attribution.

## Requirements

- JDK 21 or newer
- Gradle, or the Gradle Wrapper once generated and committed
- Git

## Build

From the repository root:

```bash
./gradlew build
```

or, before the wrapper exists:

```bash
gradle build
```

## Smoke test

```bash
./gradlew smoke
```

This runs a small embedded SQL script through `ij` using classes produced by the Gradle build.

## Useful Gradle tasks

```bash
./gradlew build
./gradlew smoke
./gradlew sysinfo
./gradlew jars
```

Generated jars are written to:

```text
build/libs/
```

## Near-term roadmap

See `docs/ROADMAP.md`.
