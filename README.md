# DelosDB

DelosDB is a Java-native modular database platform built from the Apache Derby
codebase. It preserves Derby's strongest properties — embeddable SQL, JDBC
compatibility, small operational footprint, and pure-Java deployment — while
opening selected engine seams for DelosDB extension work.

DelosDB is not a finished external-plugin product yet. The current project state
is a Java 21 Gradle-only modernization baseline with two finished provider seam
proofs and several deliberately frozen shallow surfaces.

## Current status

The supported developer path is Gradle-only. The inherited Ant workflow is not
part of the supported DelosDB workflow.

Finished seams:

- `CostModelProvider` v2: heap and B-tree providers through Derby's native
  `StoreCostController` seam.
- `IndexProvider` v2: B-tree SQL-backed provider plus memory provider-owned
  runtime proof.

Frozen shallow seams:

- `StorageProvider`: heap-only.
- `FunctionProvider`: built-in DelosDB function only.
- `TypeProvider`: metadata-only.

## Build requirements

- JDK 21 or newer
- Gradle Wrapper from this repository

## Main verification gate

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Broader checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

If a previous Derby suite run was interrupted, start with:

```bash
./gradlew clean
```

## Useful Gradle tasks

```bash
./gradlew build
./gradlew fullVerification
./gradlew derbyRuntimeSmoke
./gradlew smoke
./gradlew smokeFromJars
./gradlew modernizationSmoke
./gradlew networkServerSmoke
./gradlew sysinfo
./gradlew sysinfoFromJars
./gradlew verifyJars
./gradlew verifyReleaseArtifacts
./gradlew verifyReleaseDistribution
./gradlew dist
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
./gradlew :delosdb-tests:compileDerbyTestsModule
./gradlew :delosdb-tests:runDerbyLangSuite
```

## Gradle subprojects

| Subproject | Responsibility | Runtime artifact |
|---|---|---|
| `:delosdb-osgi-stub` | inherited OSGi stub compatibility | `osgi-framework-stub.jar` |
| `:delosdb-commons` | shared runtime classes | `derbyshared.jar` |
| `:delosdb-engine` | embedded SQL engine | `derby.jar` |
| `:delosdb-client` | network client | `derbyclient.jar` |
| `:delosdb-tools` | command-line and admin tools | `derbytools.jar` |
| `:delosdb-runner` | inherited command launcher | `derbyrun.jar` |
| `:delosdb-optionaltools` | optional tool integrations | `derbyoptionaltools.jar` |
| `:delosdb-server` | network server | `derbynet.jar` |
| `:delosdb-storeless` | compiler/optimizer boot without storage | development module |
| `:delosdb-tests` | inherited Derby test suite activation | test module |
| `:delosdb-pptesting` | package-private inherited tests | test module |
| `:delosdb-buildtools` | build-time generators/scanners | build tooling |
| `:delosdb-locales` | generated locale verification | verification module |

## Runtime artifacts

Runtime jars are written to `build/libs/` and intentionally keep Derby-compatible
file names during this preview phase:

```text
derby.jar
derbyclient.jar
derbynet.jar
derbyoptionaltools.jar
derbyrun.jar
derbyshared.jar
derbytools.jar
osgi-framework-stub.jar
```

Maven coordinates are DelosDB-branded and can be verified through Maven Local.

## Binary distribution

```bash
./gradlew dist
./gradlew verifyReleaseDistribution
```

Outputs:

```text
build/distributions/delosdb-0.1.0-dev-bin.zip
build/distributions/delosdb-0.1.0-dev-bin.tar.gz
```

## Local Maven publication

```bash
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

This is a Maven Local verification baseline only. Maven Central publication is a
separate future release-hardening task.

## Relationship to Apache Derby

This project is based on Apache Derby 10.17.1.0 source code. Apache Derby was
developed by the Apache Software Foundation and distributed under the Apache
License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the
Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the
Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for
additional fork attribution.

## Documentation

Current top-level docs:

- `docs/BUILDING.md` — build, test, distribution, and Maven Local workflow.
- `docs/ROADMAP.md` — current product direction and frozen/finished seams.
- `docs/modernization-status.md` — current green state and cleanup priority.
- `docs/DERBY-COMPATIBILITY.md` — Derby compatibility policy.
- `docs/sql-extensions.md` — supported DelosDB SQL extension surface.
- `docs/BENCHMARKS.md` — local benchmark baseline.
- `docs/book/` — source-checked Derby/DelosDB internals manuscript.
