# DelosDB

DelosDB is a Java 21, Gradle-only, Derby-compatible database kernel built from
the Apache Derby codebase. It preserves Derby's embeddable SQL/JDBC surface while
modernizing selected internals through small executable proofs.

DelosDB is not a finished external-plugin product yet. The current mission is to
keep Derby compatibility boring while turning the engine into a modular,
inspectable platform for storage, indexing, optimizer, recovery, and MVCC work.

## Current status

The supported developer path is Gradle-only. The inherited Ant workflow is not
part of the supported DelosDB workflow.

Closed major lane:

- ASM is the production generated-bytecode backend.
- The old Derby bytecode backend and old classfile writer are quarantined.
- Permanent bytecode proof: `generatedBytecodeAsmJvm21Proof`.

Closed major lane:

- MVCC semantic-correctness sprint A44--A52 is green.
- `delos_mvcc` is guarded and opt-in/property-gated.
- The normal Derby heap path remains the default.
- The global default store has not been flipped.

Finished provider seams:

- `CostModelProvider` v2 through Derby's native `StoreCostController` seam.
- `IndexProvider` v2 through B-tree SQL-backed and memory provider-owned proofs.
- Unified extension registry visibility through system routines.

Frozen shallow seams:

- `FunctionProvider`: built-in DelosDB function only.
- `TypeProvider`: metadata-only.
- New provider families are not opened while MVCC correctness is the active lane.

## MVCC mission

The active MVCC plan is documented in:

```text
docs/MVCC-MISSION.md
```

Current storage rule:

```text
No property:
  CREATE TABLE ...        -> normal Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

Completed MVCC correctness sprint:

```text
A44 missing-history / prune safety
A45 vacuum watermark integration
A46 command sequence model
A47 statement snapshot visibility
A48 SQL statement-boundary smoke
A49 durable transaction outcome log
A50 unresolved outcome recovery
A51 captured visibility-state snapshot
A52 MVCC SQL compatibility candidate matrix
```

Research/university friendliness is a constraint on engine proofs, not a second
product roadmap. Small proof-level traces and readable assertions are acceptable;
new labs, profiles, artifact systems, and SQL explain surfaces still require a
separate post-A52 decision.

## Build requirements

- JDK 21 or newer
- Gradle Wrapper from this repository

## Main verification gates

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

MVCC-focused gates:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccKernelReviewCloseoutProof
./gradlew mvccHistoryPrunedSafetyProof
./gradlew mvccVacuumWatermarkProof
./gradlew mvccCommandSequenceProof
./gradlew mvccStatementSnapshotVisibilityProof
./gradlew mvccSqlStatementBoundarySmoke
./gradlew mvccTransactionOutcomeLogProof
./gradlew mvccUnresolvedOutcomeRecoveryProof
./gradlew mvccCapturedVisibilitySnapshotProof
./gradlew mvccSqlCompatibilityCandidate
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
| `:delosdb-storage-mvcc` | opt-in MVCC/versioned-storage kernel and proofs | development module |
| `:delosdb-storeless` | compiler/optimizer boot without storage | development module |
| `:delosdb-tests` | inherited Derby test suite activation | test module |
| `:delosdb-pptesting` | package-private inherited tests | test module |
| `:delosdb-buildtools` | build-time generators/scanners | build tooling |
| `:delosdb-locales` | generated locale verification | verification module |

## Repository layout

| Path | Purpose | Status |
|---|---|---|
| `delosdb-*` | Gradle subprojects for runtime, storage experiments, tools, tests, build tools, demos, and compatibility modules | supported |
| `dev/` | focused smoke/proof programs and local audit/benchmark scripts | supported |
| `docs/` | maintained project documentation and book sources | supported |
| `bin/` | launchers included in the binary distribution | supported |
| `tools/java/` | checked-in build/test jars required by the Gradle build | supported legacy dependency bucket |
| `tools/*` other than `tools/java/` | inherited Ant/release/Javadoc helpers | removed by cleanup |
| `maven2/`, `plugins/`, `release/`, `java/` | inherited Derby release/IDE/empty layout | removed by cleanup |

Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` may appear in local
ZIP snapshots. They are not project cleanup targets and must not be deleted by
cleanup scripts.

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

Root-level Markdown is limited to project-facing essentials:

- `README.md` — orientation and current state.
- `CONTRIBUTING.md` — contribution workflow and current project rules.
- `GOVERNANCE.md` — maintainer model and release gates.
- `SECURITY.md` — vulnerability-reporting policy.
- `NOTICE-FORK.md` — Apache Derby fork attribution.

Maintained technical docs live under `docs/`:

- `docs/MVCC-MISSION.md` — active MVCC mission, A44--A52 closeout, and post-A52 decision point.
- `docs/BUILDING.md` — build, test, distribution, and Maven Local workflow.
- `docs/ROADMAP.md` — current product direction and near/future milestones.
- `docs/modernization-status.md` — current green state and cleanup priority.
- `docs/DERBY-COMPATIBILITY.md` — Derby compatibility policy.
- `docs/sql-extensions.md` — supported DelosDB SQL extension surface.
- `docs/BENCHMARKS.md` — local benchmark baseline.
- `docs/book/` — source-checked Derby/DelosDB internals manuscript.
