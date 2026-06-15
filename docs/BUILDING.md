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

For inherited Derby cleanup planning, generate the guarded hotspot reports:

```bash
./gradlew inheritedCodeQualityAudit
./gradlew legacyDerbyHarnessAudit
./gradlew generatedBytecodeJvm21Proof
./gradlew generatedBytecodeVersionExperimentProbe
./gradlew generatedMethodDispatchAudit
./gradlew deprecatedApiCleanupAudit
./gradlew xmlHardeningAudit
./gradlew sortMemoryObservabilityAudit
```

`legacyDerbyHarnessAudit` classifies the old Derby function-test harness and
verifies the active source quarantine. DelosDB keeps the historical sources in
the tree for reference, but the active Gradle language-suite path runs the JUnit
`_Suite` class directly and excludes obsolete VM adapter classes plus the old
`RunSuite` launcher from test-module compilation.

`generatedBytecodeJvm21Proof` records the inherited Derby generated-class path
before JVM 21 bytecode modernization. It checks the current `ClassHolder`
classfile header, writes `build/reports/generated-bytecode-jvm21/generated-bytecode-jvm21-proof.md`,
and keeps the rule explicit: do not bump generated classfile versions until the
verifier behavior for generated activation classes is proven.

`generatedBytecodeVersionExperimentProbe` is the next guarded JVM step. It does
not change production bytecode generation; it defines and invokes tiny
Derby-written classfiles at legacy candidate versions 45, 49, and 50 so the
project has evidence before considering a real version bump.

`generatedMethodDispatchAudit` records the generated-method dispatch path after
the small `ReflectGeneratedClass` cleanup. The hot `e0..e9` generated activation
methods remain direct calls; the reflective fallback remains in place for less
common generated methods.

`deprecatedApiCleanupAudit` verifies that Java 21 cleanup does not reintroduce
the old non-locator CLOB deprecated byte-stream helper. The replacement keeps
the inherited low-byte mapping so the legacy wire path remains compatibility
focused rather than silently switching to charset conversion.

`xmlHardeningAudit` verifies the guarded XML parser/transformer compatibility
hardening pass. It also runs `secureXmlFactoryProbe`, which checks that the
centralized XML factory helper blocks external general entity expansion while
preserving Derby SQL/XML behavior for DTD default attributes and internal entity
expansion-limit errors.

`sortMemoryObservabilityAudit` verifies the external-sort buffer sizing
guardrail. It also runs `sortMemoryPolicyProbe`, which records the DelosDB
JVM-aware automatic memory target, the inherited 1 MiB floor, the 16 MiB cap,
the row-count override path, the slush adjustment, and the minimum clamp.

When a previous test run was interrupted, start with a clean build:

```bash
./gradlew clean
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```


## Repository layout and inherited cleanup

DelosDB keeps the active build structure small and Gradle-owned:

```text
delosdb-*        Gradle subprojects
dev/             focused proof/smoke/audit scripts
docs/            maintained docs and book sources
bin/             distribution launchers
tools/java/      checked-in build/test jars still required by the Gradle build
```

The inherited Derby Ant/release layout is not supported in the DelosDB workflow. Cleanup removes stale top-level web/release artifacts such as `index.html`, `RELEASE-NOTES.html`, `published_api_overview.html`, `STATUS`, and `releaseSummary.xml`, and removes unused inherited directories such as `maven2/`, `plugins/`, `release/`, and empty `java/`.

Do not remove `tools/java/` during cleanup. The Gradle build still references its checked-in jars for JavaCC, JUnit, Lucene optional-tool compilation, and inherited servlet/json dependencies. Other old `tools/*` Ant/release/Javadoc helper folders are not part of the supported build.

Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` may exist locally and must not be deleted by cleanup scripts.

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

The inherited Derby harness still contains old Ant-era process-launcher code and
obsolete concrete VM adapter classes for JDK/IBM/J9 variants. DelosDB keeps those
sources in the tree for source reference, but the active Gradle test module
quarantines the proven-unused adapter layer from compilation. Keep
`RunTest.java`, `RunList.java`, `NetServer.java`, `jvm.java`, and
`currentjvm.java` compiled until the remaining inherited utility references are
removed.

The harness quarantine guardrail is:

```bash
./dev/legacy-derby-harness-audit.sh --verify
./gradlew legacyDerbyHarnessAudit
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
