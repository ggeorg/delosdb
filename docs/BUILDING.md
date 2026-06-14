# Building DelosDB

DelosDB uses the checked-in Gradle Wrapper as the supported build system. The
active build targets Java 21 and keeps Derby-compatible runtime jar names while
using DelosDB project identity, metadata, and verification gates.

## Requirements

- JDK 21 or newer
- The checked-in Gradle Wrapper

## Main developer gates

Use these commands from the repository root:

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

For the broader release/modernization gate:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

When a previous test run was interrupted, start with a clean build:

```bash
./gradlew clean
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

## Runtime subprojects and artifacts

The runtime build is split into Gradle subprojects. Source packages remain
Derby-compatible; Gradle ownership and DelosDB release metadata are modernized.

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

Runtime jars are assembled under:

```text
build/libs/
```

Current runtime jar names intentionally remain Derby-compatible:

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

## Build lifecycle

`./gradlew build` performs the normal product build:

1. generates inherited Derby sources and resources;
2. compiles the JPMS runtime modules;
3. assembles runtime jars;
4. verifies jar metadata and legal attribution;
5. runs embedded JDBC smoke tests from classes and jars;
6. builds and verifies the binary distribution shape.

Useful focused tasks:

```bash
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
```

## Inherited Derby test suite

The active inherited-language-suite gate is:

```bash
./gradlew :delosdb-tests:runDerbyLangSuite
```

For a compile-only check of the activated Derby test module:

```bash
./gradlew :delosdb-tests:compileDerbyTestsModule
```

`runDerbyLangSuite` uses an isolated Gradle test work directory. If a suite run
is interrupted, run `./gradlew clean` before retrying.

## Binary distribution

Build the local binary distribution with:

```bash
./gradlew dist
./gradlew verifyReleaseDistribution
```

Distribution archives are written to:

```text
build/distributions/delosdb-0.1.0-dev-bin.zip
build/distributions/delosdb-0.1.0-dev-bin.tar.gz
```

The distribution contains launchers, documentation, examples, legal attribution
files, and the verified runtime jars.

## Maven Local publication

DelosDB currently supports Maven Local publication as a verification baseline,
not as a Maven Central release workflow:

```bash
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

Published coordinates use the DelosDB group and artifact IDs, for example:

```text
io.github.ggeorg.delosdb:delosdb-engine:0.1.0-dev
```

Remote publication still requires release-hardening work such as sources jars,
javadocs, signing, staging, and final artifact naming.

## Documentation ownership

The old document split between artifact inventory, build inventory, publishing,
legacy artifacts, and Java cleanup has been collapsed into this file plus the
current status/roadmap files. Keep build instructions here and avoid creating
parallel build-status documents.
