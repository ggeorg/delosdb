# DelosDB Roadmap

DelosDB is a Java 21, Derby-compatible database kernel. The project keeps the
SQL/JDBC surface familiar while replacing risky inherited internals through
small executable proofs.

North star:

```text
A Java developer can build and test serious database internals — storage,
indexing, optimizer costing, recovery, and MVCC — against a real SQL engine,
without losing Derby compatibility by accident.
```

## Current rule

The A44--A52 MVCC semantic-correctness sprint is closed. Choose the next lane
explicitly before starting new work.

Do not start new provider families, research-platform subsystems, new SQL
surfaces, or a global default-store flip as a side effect of MVCC cleanup.

Workspace metadata is not a cleanup target. Local workspace ZIP snapshots may
contain `.git/`, `.gradle`, and `.idea`. Cleanup scripts must never delete those
paths.

## Closed major lane: ASM bytecode

ASM is now the production generated-bytecode backend. The old Derby bytecode
backend and old classfile writer are quarantined. The permanent proof is:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

Do not reopen ASM work unless a concrete build/test failure points back to
bytecode generation.

## Finished provider seams

### CostModelProvider v2

Status: finished seam.

Active native path:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProviderResolver
  -> CostModelProvider
```

Proven implementations:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

Known boundary: Derby's `StoreCostResult` can propagate only total cost and
estimated row count. Richer path/cost vectors are future optimizer work.

### IndexProvider v2

Status: finished abstraction proof.

Proven implementations:

```text
index btree  -> Derby-compatible SQL-backed index provider
index memory -> provider-owned runtime operations proof
```

`btree` remains the only normal SQL-creatable index provider. `memory` is visible
in the registry and has its own runtime proof, but `CREATE INDEX ... USING
memory` is intentionally rejected until a real Derby executor/storage bridge
exists for non-B-tree physical indexes.

## Frozen shallow seams

These remain deliberately shallow while MVCC is the active lane:

- `FunctionProvider`: built-in DelosDB function surface only.
- `TypeProvider`: metadata-only Derby type visibility.
- new provider families: not opened.

## Closed lane: MVCC semantic correctness sprint

The MVCC path has moved beyond source mapping and simple provider smokes. It now
has an isolated storage module, provider SPI, page-backed durability proofs,
provider-owned index proofs, SQL opt-in smokes, a guarded default-provider
candidate path, and the A44--A52 correctness gates.

Current rule:

```text
No property:
  CREATE TABLE ...        -> normal Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

The global default store is not flipped.

Important current gates:

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

Closed A44--A52 plan:

```text
A44 — missing-history / prune-safety
A45 — vacuum watermark integration
A46 — command sequence model
A47 — statement snapshot visibility
A48 — SQL statement-boundary smoke
A49 — durable transaction outcome log
A50 — unresolved outcome recovery
A51 — captured visibility-state snapshot
A52 — MVCC SQL compatibility candidate matrix
```

The detailed mission and four-engine comparison live in:

```text
docs/MVCC-MISSION.md
```

## Research-friendly constraint

DelosDB should be friendly to database-systems research and university teaching,
but that must not become a second product before the engine is correct.

Allowed now:

```text
small proof-level traces;
readable assertion messages;
inspectable internal value objects;
proof output that explains a decision already being tested.
```

Still parked until a separate post-A52 decision:

```text
new SQL EXPLAIN surfaces;
teaching profiles;
research property families;
deterministic scheduler;
fault-injection framework;
labs/ directory;
artifact packaging;
benchmark harness.
```

## Post-A52 decision point

A44--A52 are green. The next work should be selected deliberately from one lane:

1. row locks / `SELECT FOR UPDATE`;
2. version-aware indexes;
3. additional property-gated default-provider expansion;
4. performance / benchmark sanity for the guarded MVCC candidate.

Until that choice is made, keep Derby heap as default and keep `delos_mvcc`
guarded by explicit syntax or `delosdb.storage.defaultProvider=delos_mvcc`.

## Future direction

After the next lane is selected and proven, DelosDB can consider deeper lanes:

```text
row locks / SELECT FOR UPDATE;
semi-consistent reads for UPDATE/DELETE conflict checks;
wait/retry/abort conflict decisions;
serializable isolation;
version-aware indexes;
tombstone/side-index experiments;
adaptive version storage;
optimizer path vocabulary;
benchmark/research artifact support;
formal teaching labs.
```

These are future lanes, not near-term gates.

## Documentation cleanup

Active MVCC planning belongs in `docs/MVCC-MISSION.md`.

`docs/postgres-class-storage-concurrency.md` is retained as historical source
trail for the early PostgreSQL-class storage/concurrency campaign.

`docs/postgresql-deep-gap-map.md` is stale and should be removed; it belongs to
the pre-MVCC source-mapping phase and contains obsolete guidance.

## Book rule

Do not update the book as part of MVCC roadmap cleanup. Book changes require a
separate pass with source trails and chapter verification status updates.
