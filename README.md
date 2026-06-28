# DelosDB

DelosDB is a Java 25, Gradle-only, Derby-compatible database kernel built from
the Apache Derby codebase. It preserves Derby's embeddable SQL/JDBC surface while
modernizing selected internals through small executable proofs.

DelosDB is not a finished external-plugin product yet. The current mission is to
keep Derby compatibility boring while turning the engine into a modular,
inspectable platform for storage, indexing, optimizer, recovery, and MVCC work.


## DelosDB direction

DelosDB is intended to become a Derby-compatible, pluggable RDBMS research
kernel for education and experimentation. The core idea is simple:

```text
Define the RDBMS block contracts first.
Keep Derby as the default compatible implementation/adaptation.
Allow selected alternative implementations for research and student proofs.
Observe and explain behavior after the contracts and implementations exist.
```

The center of the project is not tracing, documentation, or one experimental
storage path. The center is a set of explicit database-system blocks that can be
studied, adapted, and eventually replaced in small executable proofs.

Primary RDBMS blocks include:

```text
SQL frontend
  parser, binder, validator

Catalog
  schemas, tables, columns, indexes, constraints

Types
  SQL values, nulls, comparisons, conversions

Planner / optimizer
  logical plans, physical plans, access paths, costs

Execution
  operators, scans, filters, joins, row flow

Storage
  tables, rows, scans, indexes, providers

Transactions and concurrency
  transaction identity, commit, rollback, isolation, locks, snapshots

Recovery and durability
  WAL, checkpoints, replay, durable state

Diagnostics
  trace, explain output, counters, teaching reports
```

The implementation discipline is:

```text
1. Define the smallest useful contract for one RDBMS block.
2. Adapt the inherited Derby implementation to that contract.
3. Prove the Derby-backed path still works.
4. Add an alternative implementation only when the contract is real enough.
5. Prove both paths through the same focused behavior test.
```

Contract placement should follow the role:

```text
Internal engine contracts
  DelosDB-owned RDBMS block contracts used inside the engine.

Provider SPI
  contracts implemented by pluggable providers such as storage, index,
  function, type, planner, or teaching/research implementations.

Implementation
  inherited Derby code or DelosDB-owned adapters that satisfy the contracts.

Diagnostics
  optional observation and explanation around working contracts and
  implementations; diagnostics must not define the architecture.
```

The default implementation remains Derby-compatible. Alternative implementations
must be introduced one block at a time and must not break the embeddable
SQL/JDBC surface.

## Current status

The supported developer path is Gradle-only. The inherited Ant workflow is not
part of the supported DelosDB workflow.

Closed major lane:

- MVCC semantic-correctness sprint A44--A52 is green.
- `delos_mvcc` is guarded and opt-in/property-gated.
- The normal legacy Derby-compatible heap path remains the default.
- The global default store has not been flipped.

Closed major lane:

- The inherited Derby store source ownership has moved to `delosdb-storage-derby`.
- `org.apache.derby.iapi.store.*` and `org.apache.derby.impl.store.*` remain package-compatible.
- `derby.jar` still includes the inherited Derby store runtime classes for existing users.
- Existing users do not need to manually add a separate storage jar yet.

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
  CREATE TABLE ...        -> normal legacy Derby-compatible heap path

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

- JDK 25
- Gradle Wrapper from this repository

Use `./gradlew`, not a system `gradle` command. The wrapper pins a Gradle runtime compatible with the project baseline.

Generated SQL bytecode is emitted by the ASM backend at the project classfile baseline, not at a machine-dependent runtime level.

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
| `:delosdb-storage-derby` | source home for inherited Derby-compatible heap/raw/access/WAL store | packaged in `derby.jar` for compatibility |
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

- `docs/MVCC-MISSION.md` — active MVCC/storage boundary notes.
- `docs/BUILDING.md` — build and verification commands.
- `docs/DERBY-COMPATIBILITY.md` — Derby compatibility policy.
- `docs/sql-extensions.md` — supported DelosDB SQL extension surface.
