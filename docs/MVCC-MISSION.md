# DelosDB MVCC Mission and Post-A52 State

DelosDB modernizes Derby by keeping the familiar SQL/JDBC surface while opening
engine seams for storage, indexing, recovery, optimizer costing, and generated
execution. The MVCC path is guarded, opt-in, and proof-driven.

This document is not a book chapter. It is the project-facing mission/status note
for DelosDB MVCC after the A44--A52 correctness sprint.

## Mission boundary

DelosDB should become:

```text
A Java 21, Derby-compatible database kernel with a real versioned-storage path,
durable recovery, statement-correct SQL visibility, and small executable proofs
for every behavior boundary.
```

DelosDB is not trying to become PostgreSQL, H2, InnoDB, or MariaDB. Those engines
are reference systems for design pressure. DelosDB keeps Derby compatibility and
promotes new storage behavior only through explicit proof gates.

## Current storage rule

```text
No property:
  CREATE TABLE ...        -> normal Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

The global default store is not flipped. Heap remains the default. `delos_mvcc`
is a guarded candidate path, not the production default.

## Four-engine MVCC synthesis

The current DelosMVCC design has been compared against four mature systems:

| System | Useful lesson for DelosDB | What DelosDB should not copy now |
|---|---|---|
| PostgreSQL | Snapshot visibility needs command/statement granularity, delete visibility, vacuum discipline, and row-lock-aware semantics. | Do not mechanically copy `xmin`/`xmax`, tuple headers, hint bits, subtransactions, or multixact. |
| H2 MVStore | A Java storage layer still separates transaction snapshots from statement snapshots; conflict handling can later become retry/wait/abort rather than binary throw. | Do not replace DelosMVCC resident version chains with H2's one-overlay MVMap model. |
| InnoDB / MariaDB | A reader must distinguish “row legitimately not visible” from “history needed by this snapshot was pruned.” | Do not switch DelosMVCC to undo-log reconstruction as the first storage architecture. |
| DelosMVCC | Resident version chains are simple, inspectable, and good for the current proof ladder. | Do not treat simple chains as default-store-ready without history safety, statement visibility, recovery outcome logging, and vacuum watermarks. |

The transferable decisions are:

1. keep resident version chains for the near term;
2. distinguish missing/pruned history from legitimate row absence;
3. make same-transaction visibility command/statement-aware;
4. use durable transaction outcomes before strict recovery exposes versions;
5. capture visibility state after the correct semantics exist;
6. keep row locks, semi-consistent reads, serializable isolation, version-aware
   indexes, HTAP-style research features, and production default-store promotion
   as future lanes.

## Completed storage/MVCC lane

The ASM bytecode switch is closed. ASM is the production bytecode compiler and
the permanent bytecode proof remains:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

The MVCC lane has advanced from isolated storage proofs to a guarded SQL
candidate path with semantic correctness gates. Important gates now include:

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

## Closed A44--A52 correctness sprint

The A44--A52 sprint is closed and green. It was the required semantic-correctness
lane before any further default-provider expansion.

| Gate | Status | Behavior boundary closed |
|---|---|---|
| A44 — missing-history / prune-safety | green | A read can distinguish legitimate invisibility from history needed by a snapshot being pruned. |
| A45 — vacuum watermark integration | green | Long-lived snapshots pin history and prevent unsafe vacuum pruning. |
| A46 — command sequence model | green | Versions carry create/delete command ordering; read-between-update and read-before-delete cases are proven. |
| A47 — statement snapshot visibility | green | Same-transaction statement snapshots see earlier commands, not later commands. |
| A48 — SQL statement-boundary smoke | green | SQL bridge advances MVCC statement command boundaries without flipping heap default. |
| A49 — durable transaction outcome log | green | Strict outcome-log path records committed/aborted/unresolved transaction outcomes. |
| A50 — unresolved outcome recovery | green | Strict recovery does not expose orphaned versions when transaction outcome is missing. |
| A51 — captured visibility-state snapshot | green | Visibility inputs can be captured without changing the semantics proven earlier. |
| A52 — MVCC SQL compatibility candidate matrix | green | Guarded MVCC SQL candidate supports core create/insert/update/delete/select/index/recovery behavior. |

## What A52 does and does not mean

A52 means `delos_mvcc` is no longer only a toy provider smoke path. It has passed
a coherent candidate matrix that includes history safety, vacuum watermarking,
command/statement visibility, outcome logging, strict unresolved recovery,
captured visibility, and guarded SQL behavior.

A52 does **not** mean:

```text
MVCC is production-ready;
MVCC is the global default;
row locks / SELECT FOR UPDATE exist;
semi-consistent UPDATE/DELETE reads exist;
serializable isolation is complete;
version-aware indexes are complete;
performance claims can be made.
```

## Current post-A52 decision point

The next technical lane should be selected explicitly. Do not start more than one
of these at the same time:

1. **Row locks / SELECT FOR UPDATE** — add locking-read semantics and the first
   row-lock metadata model.
2. **Version-aware indexes** — reduce base-table visibility checks and prepare
   for more serious index-only/range behavior.
3. **Default-provider expansion** — broaden property-gated bare-SQL coverage, but
   still do not flip the global default.
4. **Performance / benchmark sanity** — add a small repeatable regression signal
   for MVCC candidate behavior without making marketing claims.

The safest next discussion is a lane choice, not another preflight gate.

## Research-friendly constraint

DelosDB should be friendly to database-systems research and university teaching,
but research friendliness is a constraint on engine work, not a second product
roadmap.

Allowed now:

```text
small test-level visibility traces;
readable assertion messages;
inspectable internal value objects;
proof output that explains a decision already being tested.
```

Still parked until a separate decision is made:

```text
new SQL EXPLAIN surfaces;
teaching profiles;
research property families;
deterministic scheduler;
fault-injection framework;
labs/ directory;
artifact packaging;
benchmark harness beyond small regression sanity.
```

A research-facing feature is allowed only when it is attached to the current
engine proof and makes that proof easier to inspect or reproduce. If it requires
a new subsystem, it waits.

## Documentation cleanup decision

`docs/MVCC-MISSION.md` is the active MVCC planning/status document.

`docs/postgres-class-storage-concurrency.md` is retained as historical source
trail because it records how the early PostgreSQL-class storage/concurrency
campaign reached the MVCC module.

`docs/postgresql-deep-gap-map.md` is stale and should be removed. It belongs to
the pre-MVCC source-mapping phase and contains obsolete guidance such as “Do not
implement MVCC yet.”

## Future lanes

After the next lane is selected, DelosDB can continue with one focused sequence:

```text
row locks / SELECT FOR UPDATE;
semi-consistent read for UPDATE/DELETE conflict checks;
wait/retry/abort conflict decisions;
serializable isolation;
version-aware indexes;
tombstone/side-index experiments;
adaptive version storage;
reproducible benchmark harness;
formal teaching labs and artifact packaging.
```

Those are future lanes, not active concurrent work.
