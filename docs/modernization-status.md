# DelosDB Modernization Status

Last updated: 2026-06-18

DelosDB is a Gradle-only Java 21 modernization fork of Apache Derby with a
Derby-compatible SQL/JDBC baseline and a controlled DelosDB extension surface.
The A44--A52 MVCC semantic-correctness sprint is closed. The current priority is
to choose the next post-A52 lane deliberately, not to start broad expansion.

Workspace metadata such as `.git/`, `.gradle/`, and `.idea/` is valid local
state and may appear in developer ZIP snapshots. Cleanup scripts must not delete
it.

## Current verification gates

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Broader checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Closed major lane

### ASM bytecode backend

ASM is the production generated-bytecode backend. The old Derby bytecode backend
and old classfile writer are quarantined. The permanent proof remains:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

`asmSwitchComplete` is the closeout gate for the switch. Do not reopen ASM work
unless a concrete failure points there.

## Finished provider seams

### CostModelProvider v2

The native cost path is resolver-driven through `StoreCostControllerBridge` and
supports two built-in providers:

```text
heap  -> factory id 0
btree -> factory id 1
```

The old `FromBaseTable` / `IndexProviderCostBridge` path is legacy diagnostic
only. It must not consume or mutate planner cost.

### IndexProvider v2

The index provider abstraction has two built-in implementations:

```text
btree  -> Derby-compatible SQL-backed provider
memory -> provider-owned runtime proof
```

`CREATE INDEX ... USING memory` remains intentionally rejected until DelosDB
builds a real Derby executor/storage bridge for non-B-tree physical indexes.

## Closed lane: MVCC semantic correctness

The MVCC path is no longer just a future design sketch. It has an isolated
storage module, provider SPI proofs, page-backed durability proofs,
provider-owned index proofs, SQL opt-in smokes, a guarded default-provider
candidate path, and the A44--A52 semantic-correctness sprint.

Current rule:

```text
No property:
  CREATE TABLE ...        -> normal Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

Current high-value gates:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccTransactionLockOrderProof
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

Closed A44--A52 lane:

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

See `docs/MVCC-MISSION.md` for the post-A52 mission state and four-engine comparison.

## Frozen shallow seams

- `FunctionProvider`: built-in DelosDB function surface; no external function
  loading yet.
- `TypeProvider`: metadata-only type visibility; no parser, binder, or storage
  changes yet.
- New provider families are not opened until the post-A52 next lane is selected.

## Current product state

Green/current product areas:

- runtime/product smokes through `derbyRuntimeSmoke`;
- inherited Derby language suite through `:delosdb-tests:runDerbyLangSuite`;
- ASM generated-bytecode backend;
- CostModelProvider v2 through heap and B-tree store-cost providers;
- IndexProvider v2 through B-tree and memory providers;
- FunctionProvider metadata/execution/visibility for built-in DelosDB function;
- TypeProvider metadata and SQL visibility;
- unified extension registry through `DELOSDB_EXTENSIONS()`;
- type metadata visibility through `DELOSDB_TYPES()`;
- MVCC guarded candidate path through `mvccDefaultProviderCandidateMatrix`;
- MVCC A44--A52 semantic-correctness sprint through `mvccSqlCompatibilityCandidate`.

## Research-friendly constraint

DelosDB should be friendly to database-systems research and university teaching,
but near-term research-facing work is limited to proof-level observability:
readable assertions, small traces, and inspectable internal objects tied to the
current engine proof.

Do not add a separate labs directory, teaching profile system, deterministic
scheduler, fault-injection framework, benchmark harness, artifact pipeline, or
new SQL explain surface without a separate post-A52 decision.

## Current documentation state

- `docs/MVCC-MISSION.md` is the active MVCC planning document.
- `docs/postgres-class-storage-concurrency.md` is retained as historical source
  trail.
- `docs/postgresql-deep-gap-map.md` is stale and should be removed.
- `docs/book/` is not part of this roadmap cleanup.

## Current inherited-code static analysis

The regenerated inherited-code summary is maintained in
`docs/inherited-code-static-analysis.md`. It records the current Derby-vs-DelosDB
source delta, the modernization areas completed in the inherited engine, and the
remaining algorithmic areas that must stay conservative.

## Current cleanup priority

Before adding features:

1. keep the root project layout modern: supported modules, `dev/`, `docs/`,
   `bin/`, and `tools/java/` only for required checked-in build jars;
2. remove stale inherited Derby web/release artifacts and stale pre-MVCC planning
   docs through explicit cleanup scripts;
3. keep generated LaTeX/PDF build outputs out of source control;
4. never delete local `.git/`, `.gradle/`, or `.idea/`;
5. avoid opening a new provider family;
6. choose the next post-A52 MVCC/storage lane explicitly before coding.
