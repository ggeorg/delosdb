# DelosDB

DelosDB is a Java-native modular database platform built from the proven Apache Derby codebase. Its goal is to preserve Derby's strongest properties — embeddable SQL, JDBC compatibility, small operational footprint, and pure-Java deployment — while evolving the project into a modern Gradle-based platform with explicit extension points, auditable compatibility, and a clean long-term architecture.

DelosDB is currently in modernization preview. The foundation is intentionally compatibility-first: JDBC and Derby-compatible runtime jars remain the stable baseline while the codebase is modularized, verified, benchmarked, and prepared for future SPI work.

## Current status

DelosDB now uses **Gradle as the only supported build system**. The inherited Ant workflow is not part of the supported developer path.

The current foundation includes:

- Java 21 Gradle-only build and verification workflow.
- Real Gradle subprojects for the inherited runtime modules.
- Reproducible runtime jars with DelosDB manifest metadata and Apache Derby attribution.
- Local Maven publication baseline with DelosDB-branded coordinates.
- Storeless compiler/optimizer boot module for future storage-extension work.
- Modernization audit gate for legacy Java patterns and build hygiene.
- Benchmark baseline for regression tracking.
- Activated inherited Derby test islands under Gradle, split into compile-only and curated execution buckets.

DelosDB is not yet a finished platform SPI product. The next architectural phase is to introduce explicit API/SPI boundaries, stability annotations, and extension contracts without breaking the proven JDBC baseline.

## Build requirements

- JDK 21 or newer
- Gradle Wrapper from this repository

## Standard verification gate

From the repository root:

```bash
./gradlew clean build
./gradlew fullVerification
chmod +x dev/modernization-audit.sh
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

The gate compiles the runtime modules, generates required sources/resources, assembles jars, validates release metadata and attribution, runs smoke coverage, runs activated inherited test islands, verifies modernization audit expectations, and records the benchmark baseline.

## Useful Gradle tasks

```bash
./gradlew build
./gradlew fullVerification
./gradlew smoke
./gradlew smokeFromJars
./gradlew modernizationSmoke
./gradlew networkServerSmoke
./gradlew sysinfo
./gradlew sysinfoFromJars
./gradlew jars
./gradlew verifyJars
./gradlew verifyReleaseArtifacts
./gradlew verifyReleaseDistribution
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
./gradlew dist
./gradlew printArtifactInventory
./gradlew verifyArtifactInventory
./gradlew :delosdb-tests:listActivatedDerbyTestIslands
./gradlew :delosdb-tests:compileActivatedDerbyTests
./gradlew :delosdb-tests:runActivatedDerbySmokeExecutionIslands
```

## Gradle subprojects

The runtime build is split into real Gradle subprojects:

| Subproject | Responsibility | Runtime artifact |
| --- | --- | --- |
| `:delosdb-osgi-stub` | inherited OSGi stub compatibility | `osgi-framework-stub.jar` |
| `:delosdb-commons` | shared runtime classes | `derbyshared.jar` |
| `:delosdb-engine` | embedded SQL engine | `derby.jar` |
| `:delosdb-client` | network client | `derbyclient.jar` |
| `:delosdb-tools` | command-line and admin tools | `derbytools.jar` |
| `:delosdb-runner` | inherited command launcher | `derbyrun.jar` |
| `:delosdb-optionaltools` | optional tool integrations | `derbyoptionaltools.jar` |
| `:delosdb-server` | network server | `derbynet.jar` |
| `:delosdb-storeless` | compiler/optimizer boot without storage | development module |
| `:delosdb-tests` | activated inherited test islands | test module |
| `:delosdb-pptesting` | package-private inherited test support | test module |
| `:delosdb-buildtools` | build-time generators and scanners | build tooling |
| `:delosdb-locales` | generated locale verification | verification module |

The root build coordinates product-level verification. Shared Java compiler settings and release metadata are centralized through root-level Gradle conventions so Java release, encoding, module-path handling, legal files, and manifest metadata stay consistent across assembled jars.

## Runtime artifacts

Generated jars are written to:

```text
build/libs/
```

Current runtime jar names intentionally remain Derby-compatible while Maven coordinates are DelosDB-branded:

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

This is a deliberate preview-stage compatibility policy. The target direction is:

- keep JDBC and existing Derby-compatible launcher behavior as the migration baseline;
- publish DelosDB-branded Maven coordinates;
- decide DelosDB-branded binary jar names as a separate release-hardening step, not as part of the modernization gate.

Each runtime jar includes DelosDB manifest metadata and the required legal attribution files under `META-INF/`.

## Binary distribution

DelosDB has a verified binary distribution layout. The distribution includes runtime jars, legal attribution files, documentation, a smoke-test SQL example, and small launcher scripts:

```bash
./gradlew dist
```

Distribution archives are written to:

```text
build/distributions/delosdb-0.1.0-dev-bin.zip
build/distributions/delosdb-0.1.0-dev-bin.tar.gz
```

The launcher runs the inherited Derby command entry point through the DelosDB runtime classpath:

```bash
build/release/delosdb-0.1.0-dev/bin/delosdb sysinfo
```

## Local Maven publication

DelosDB can publish DelosDB-branded coordinates to Maven Local and verify that a fresh external Gradle consumer can resolve and run the embedded engine from those publications:

```bash
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

Published local coordinates use:

```text
io.github.ggeorg.delosdb:delosdb-engine:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-client:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-server:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-tools:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-runner:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-commons:0.1.0-dev
io.github.ggeorg.delosdb:delosdb-optionaltools:0.1.0-dev
```

This is intentionally local-only for now. Maven Central signing, staging, sources jars, javadocs, and final artifact naming are separate release-hardening steps.

## Test activation strategy

DelosDB does not run `derbyall` and does not restore the old Derby harness as the supported workflow. Inherited tests are recovered through reviewed Gradle islands:

- compile-only islands for broad source recovery;
- curated execution islands for stable runtime coverage;
- bucket aliases for smoke, JDBC, non-JDBC, management, large-data, JDBC API, JDBC4, tools, and other focused areas;
- an activation ledger task for source counts, execution buckets, and deferred tails.

Useful commands:

```bash
./gradlew :delosdb-tests:listActivatedDerbyTestIslands
./gradlew :delosdb-tests:compileActivatedDerbyTests
./gradlew :delosdb-tests:runActivatedDerbySmokeExecutionIslands
./gradlew :delosdb-tests:runActivatedDerbyExecutionIslands
```

## Relationship to Apache Derby

This project is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for additional fork attribution.

## Documentation

- `docs/BUILDING.md` — developer build workflow
- `docs/BUILD-INVENTORY.md` — current Gradle build map and migration inventory
- `docs/ARTIFACTS.md` — runtime artifact inventory and planned subproject names
- `docs/MODULE-SPLIT-PLAN.md` — ordered plan for moving from root build to real Gradle subprojects
- `docs/PUBLISHING.md` — local Maven publication workflow
- `docs/ROADMAP.md` — modernization and platform roadmap
- `docs/LEGACY-ARTIFACTS.md` — inherited Derby artifact directories and Gradle ownership notes
