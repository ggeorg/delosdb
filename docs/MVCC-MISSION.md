# DelosDB MVCC Mission and Near-Term Plan

DelosDB modernizes Derby by keeping the familiar SQL/JDBC surface while opening
engine seams for storage, indexing, recovery, optimizer costing, and generated
execution. The active storage mission is MVCC, but the MVCC path remains guarded,
opt-in, and proof-driven.

This document is not a book chapter. It is the project-facing mission note for
the next MVCC correctness milestones.

## Mission boundary

DelosDB should become:

```text
A Java 21, Derby-compatible database kernel with a real versioned-storage path,
durable recovery, statement-correct SQL visibility, and small executable proofs
for every behavior boundary.
```

DelosDB is not trying to become PostgreSQL, H2, InnoDB, or MariaDB. Those engines
are reference systems for design pressure. DelosDB keeps Derby compatibility and
promotes the new storage path through explicit proof gates:

```text
opt-in provider proof
  -> SQL bridge proof
  -> property-gated default-provider candidate
  -> semantic correctness ladder
  -> durability/vacuum correctness ladder
  -> compatibility matrix
  -> possible default-store promotion much later
```

## Current storage rule

```text
No property:
  CREATE TABLE ...        -> normal Derby-compatible heap path

-Ddelosdb.storage.defaultProvider=delos_mvcc:
  bare CREATE TABLE ...   -> guarded delos_mvcc candidate path

CREATE TABLE ... USING delos_mvcc:
  explicit experimental MVCC path
```

The global default store is not flipped. Heap remains the default until MVCC has
green proofs for statement visibility, history safety, durable transaction
outcomes, vacuum watermarks, SQL compatibility, and crash/recovery behavior.

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
2. add missing-history/pruned-history safety before deeper feature work;
3. add command/statement visibility before row locks;
4. delay captured transaction-state optimization until the correct visibility
   model exists;
5. keep row locks, semi-consistent reads, serializable isolation, version-aware
   indexes, and HTAP-style research features as future lanes.

## Completed storage/MVCC lane

The ASM bytecode switch is closed. ASM is the production bytecode compiler and
the permanent bytecode proof remains:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
```

The MVCC lane has advanced from isolated storage proofs to a guarded SQL
candidate path. Important current gates include:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccTransactionLockOrderProof
./gradlew mvccKernelReviewCloseoutProof
```

`mvccKernelReviewCloseoutProof` is a closeout proof for already-claimed kernel
behavior. It is not a new feature lane.

## Near-term MVCC proof ladder

### A43 — kernel review closeout proof

Status: present in the current codebase.

Purpose: lock down low-level MVCC kernel behavior that code review traced as
correct but fragile:

```text
stale snapshot writer loses;
failed stale update does not append a ghost version;
aborted replacement remains invisible;
later writer can reuse the original version after an aborted delete marker;
committed delete after aborted delete can vacuum cleanly.
```

Gate:

```bash
./gradlew mvccKernelReviewCloseoutProof
```

### A44 — missing-history / prune-safety proof

Purpose: make a wrong vacuum/prune decision fail loudly instead of silently
returning “row not found.”

Required behavior:

```text
insert after snapshot        -> empty, not error
delete before snapshot       -> empty, not error
aborted creator              -> empty, not error
safe vacuum for new snapshot -> no error
needed history pruned        -> typed missing-history/pruned-history failure
```

This is the InnoDB/MariaDB lesson adapted to DelosDB's resident-chain model.

### A45 — command sequence model

Purpose: add the small primitive needed for statement visibility.

Expected model:

```text
MvccCommandSequence

MvccVersion:
  createdBy transaction
  createdAt command sequence
  deletedBy transaction, optional
  deletedAt command sequence, optional
```

The proof must include a read-only command between writes, not only write/write
ordering:

```text
T1 command 1: INSERT id=1 value='a'
T1 command 2: read snapshot S2 sees 'a'
T1 command 3: UPDATE id=1 value='b'
S2 evaluated again still sees 'a'
T1 command 4: new statement snapshot sees 'b'
```

And the delete variant:

```text
T1 command 1: INSERT id=1
T1 command 2: read snapshot S2 sees id=1
T1 command 3: DELETE id=1
S2 still sees id=1
T1 command 4: new statement snapshot no longer sees id=1
```

### A46 — statement snapshot visibility

Purpose: make `MvccSnapshot` distinguish transaction ownership from the owner's
statement/command visibility boundary.

Expected snapshot shape:

```text
owner
visibleThroughCommit
activeAtCapture
ownerVisibleThroughCommand
```

### A47 — SQL statement-boundary smoke

Purpose: make the SQL bridge advance command sequence at statement boundaries
without changing Derby heap behavior or flipping the global default store.

### A48 — captured visibility-state snapshot

Purpose: stop using repeated live catalog lookup as the only visibility
mechanism after the visibility model is semantically correct.

This waits until after A44--A47 because it is a scaling/robustness improvement
for a snapshot model that those earlier milestones define. Building it first
would optimize the wrong shape.

### A49 — durable transaction outcome log

Purpose: record whether a creating/deleting transaction committed, aborted, or
remained unresolved across recovery.

Initial policy should be strict: unresolved outcome must fail loudly or stay
invisible until DelosDB has enough recovery machinery to make a safer automatic
choice.

### A50 — unresolved outcome recovery proof

Purpose: crash recovery must not expose orphaned versions.

### A51 — vacuum watermark integration

Purpose: vacuum must use active snapshot watermarks, not only local chain checks.

### A52 — MVCC SQL compatibility candidate matrix

Purpose: start proving real SQL behavior under the guarded MVCC candidate path.

Still use:

```text
-Ddelosdb.storage.defaultProvider=delos_mvcc
```

No global default flip.

## Research-friendly constraint

DelosDB should be friendly to database-systems research and university teaching,
but research friendliness is a constraint on engine work, not a second product
roadmap.

Allowed during A44--A52:

```text
small test-level visibility traces;
readable assertion messages;
inspectable internal value objects;
proof output that explains a decision already being tested.
```

Not allowed during A44--A52 unless a separate decision is made:

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

A research-facing feature is allowed now only when it is attached to the current
engine proof and makes that proof easier to inspect or reproduce. If it requires
a new subsystem, it waits.

## Documentation cleanup decision

`docs/MVCC-MISSION.md` is the active MVCC planning document.

`docs/postgres-class-storage-concurrency.md` is retained as historical source
trail because it records how the early storage/concurrency campaign reached the
MVCC module.

`docs/postgresql-deep-gap-map.md` is stale and should be removed. It belongs to
the pre-MVCC source-mapping phase and contains obsolete guidance such as “Do not
implement MVCC yet.”

## Future lanes

After A44--A52 are green, DelosDB can revisit larger research-platform features
and deeper storage work:

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

Those are future lanes, not near-term gates.
