# Building DelosDB

DelosDB is built with the checked-in Gradle Wrapper. The supported developer path
is Java 21 and Gradle only. The inherited Derby Ant workflow is not a supported
DelosDB workflow.

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

## ASM bytecode gates

The ASM switch is closed. The production module points directly at `AsmJava`; ASM
is a normal engine module/runtime dependency; temporary backend override tasks
are retired.

Permanent proof:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

Closeout gate:

```bash
./gradlew asmSwitchComplete
```

Do not add new ASM proof tasks unless a concrete bytecode failure requires one.

## MVCC gates

The MVCC storage path is active but guarded. Heap remains the default store.

Current high-value MVCC gates:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccTransactionLockOrderProof
./gradlew mvccKernelReviewCloseoutProof
```

Aggregate MVCC storage check:

```bash
./gradlew mvccStorageModelTest
```

Current default-provider candidate behavior:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
```

This proves that normal Derby heap remains the default without the property, and
that bare SQL can route through `delos_mvcc` only when explicitly enabled with:

```text
-Ddelosdb.storage.defaultProvider=delos_mvcc
```

The next MVCC work is documented in `docs/MVCC-MISSION.md` and should proceed in
small proof gates, not broad preflight tasks.

## Other focused modernization gates

For inherited Derby cleanup planning, use focused gates instead of broad source
rewrites:

```bash
./gradlew inheritedCodeQualityAudit
./gradlew legacyDerbyHarnessAudit
./gradlew generatedMethodDispatchAudit
./gradlew deprecatedApiCleanupAudit
./gradlew xmlHardeningAudit
./gradlew sortMemoryObservabilityAudit
```

`legacyDerbyHarnessAudit` classifies the old Derby function-test harness and
verifies the active source quarantine. DelosDB keeps historical sources for
reference, but the active Gradle language-suite path runs the JUnit `_Suite`
class directly and excludes obsolete VM adapter classes plus the old `RunSuite`
launcher from test-module compilation.

`generatedMethodDispatchAudit`, `deprecatedApiCleanupAudit`,
`xmlHardeningAudit`, and `sortMemoryObservabilityAudit` are guardrails for
conservative Java 21 modernization. They are not invitations for broad inherited
engine rewrites.

## Repository layout and inherited cleanup

DelosDB keeps the active build structure small and Gradle-owned:

```text
delosdb-*        Gradle subprojects
dev/             focused proof/smoke/audit scripts
docs/            maintained docs and book sources
bin/             distribution launchers
tools/java/      checked-in build/test jars still required by the Gradle build
```

The inherited Derby Ant/release layout is not supported in the DelosDB workflow.
Cleanup removes stale top-level web/release artifacts such as `index.html`,
`RELEASE-NOTES.html`, `published_api_overview.html`, `STATUS`, and
`releaseSummary.xml`, and removes unused inherited directories such as `maven2/`,
`plugins/`, `release/`, and empty `java/`.

Do not remove `tools/java/` during cleanup. The Gradle build still references its
checked-in jars for JavaCC, JUnit, Lucene optional-tool compilation, and inherited
servlet/json dependencies. Other old `tools/*` Ant/release/Javadoc helper folders
are not part of the supported build.

Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` may exist locally and
must not be deleted by cleanup scripts.

## Runtime subprojects and artifacts

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
| `:delosdb-storage-mvcc` | opt-in MVCC/versioned-storage kernel and proofs | development module |
| `:delosdb-storeless` | compiler/optimizer boot without storage | development module |
| `:delosdb-tests` | inherited Derby test suite activation | test module |
| `:delosdb-pptesting` | package-private inherited tests | test module |
| `:delosdb-buildtools` | build-time generators/scanners | build tooling |
| `:delosdb-locales` | generated locale verification | verification module |

Runtime jars are assembled under `build/libs/` and intentionally retain Derby
compatible file names during this preview phase:

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

## Distribution and Maven Local

Build and verify the binary distribution:

```bash
./gradlew dist
./gradlew verifyReleaseDistribution
```

Publish and verify Maven Local artifacts:

```bash
./gradlew publishToMavenLocal
./gradlew verifyMavenPublications
./gradlew verifyMavenLocalConsumer
```

Maven Central publication is a separate future release-hardening task.
