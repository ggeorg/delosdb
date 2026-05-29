# DelosDB

DelosDB is a working-name community fork of Apache Derby 10.17.1.0, a relational database implemented entirely in Java.

The goal is to preserve Derby's strongest property — a small, embeddable, standards-oriented Java SQL engine — while modernizing the project for current Java, GitHub-based development, CI, documentation, and release workflows.

## Status

Early fork/bootstrap stage.

The first milestone is intentionally modest: make the inherited codebase easy to build, smoke-test, and discuss in public before deeper source changes begin.

## Relationship to Apache Derby

This project is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for additional fork attribution.

## Requirements

- JDK 21 or newer
- Gradle, or a Gradle Wrapper once generated and committed
- Git

## Build

From the repository root:

```bash
gradle build
```

After generating and committing the Gradle Wrapper:

```bash
./gradlew build
```

## Smoke test

```bash
gradle smoke
```

or:

```bash
./gradlew smoke
```

This runs a tiny embedded SQL test through `ij`.

## Why the build still uses Ant internally

The inherited Derby source tree is Ant-based. The Gradle file in this repository is a thin bootstrap layer that:

1. provides a modern one-command entry point,
2. removes the need to manually pass SVN-era revision metadata,
3. gives GitHub Actions a stable target,
4. keeps the existing build behavior intact while modernization proceeds.

A full Gradle or Maven module migration should happen after CI is stable.

## Near-term roadmap

See `docs/ROADMAP.md`.
