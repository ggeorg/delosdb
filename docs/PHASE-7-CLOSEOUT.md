# Phase 7 closeout

## Status

Phase 7 is complete.

The phase established the concurrent commit pipeline, removed intra-transaction
force amplification, introduced real transaction group commit, moved
maintenance ownership to the database, and scoped backup coordination to the
database being copied.

## Completed plan

### 7.1 Concurrent-write truth

The benchmark and JFR evidence cover:

```text
writers: 1, 2, 4, 8, 16
topologies: same table, different tables, different databases
transaction shapes: one-row and multi-row inserts and updates
```

The evidence distinguished preparation concurrency from serialized physical
publication and measured every established durability force.

### 7.2 Durability protocol authority

The repository contains one explicit transaction durability protocol and crash
invariant. Commit acknowledgment requires a recoverable complete transaction;
recovery must never expose a partial committed transaction.

### 7.3 Intra-transaction force consolidation

The phase reduced:

```text
per-row outcome forces         N -> 1
per-row main-page forces       N + 1 -> 2
row-directory/FSM sidecar work row-dependent -> constant per transaction
```

Every removed force has focused crash and reopen proof.

### 7.4 Writer-concurrency boundary

Immutable commit preparation now occurs outside the ordered durability section.
Two non-conflicting writers on one table can prepare independently and enroll
concurrently. Physical publication remains ordered.

### 7.5 Transaction group commit

Prepared commits enter a bounded FIFO and form bounded leader/follower groups.
A group shares:

```text
one forced COMMITTED transaction-status append
one final ordered-index rebuild and force
```

Individual WAL, outcome, main-page, checkpoint, and recovery-record fences stay
transaction-owned. Each member receives its own result. Shared failures,
preparation failures, shutdown, and backup interaction have deterministic proof.

Production table construction always selects group mode. `DIRECT` and `QUEUED`
remain package-private focused-test modes that execute the same publication
implementation. The former `delosdb.mvcc.commit.mode` JVM property is retired
and ignored.

### 7.6 Database maintenance service

One database-owned service replaces per-table executors. It provides:

```text
bounded workers
commit-triggered wakeups
periodic idle-table scans
visibility-debt priority
reader-horizon rechecks
checkpoint and backup coordination
clean database shutdown
metrics and diagnostics
```

The proven purge algorithm remains table-owned; only scheduling and ownership
changed.

### 7.7 Database backup coordination

The freeze-based backup design remains the consistency mechanism, but the
coordinator is now database-scoped rather than JVM-global. The declared durable
mutation boundary covers ACTIVE, COMMIT, ABORT, preparation cleanup, vacuum,
maintenance, drop, and close.

Focused and SQL integration proofs show:

```text
same-database writers wait at the real backup boundary
different databases continue independently
backup start/end committed-status counts remain stable
ACTIVE and ABORTED status writes cannot tear across the copy interval
```

## SERIALIZABLE behavior decision

For `delos_mvcc`, the current JDBC/Derby isolation mapping is:

```text
READ COMMITTED and weaker -> statement snapshot
REPEATABLE READ           -> transaction snapshot
SERIALIZABLE              -> transaction snapshot compatibility mapping
```

`SERIALIZABLE` is not a full-serializability guarantee. The implementation does
not currently provide:

```text
predicate locks
range or gap locks
SSI rw-conflict tracking
write-skew prevention
serialization-failure detection
```

`MvccSqlSerializableSemanticsTest` is the executable truth gate. It proves that
two SERIALIZABLE transactions can read the same invariant, update disjoint
rows, and both commit a write-skew result while retaining transaction-snapshot
visibility.

True serializability is intentionally deferred to a separate architecture
phase. That phase must choose deliberately among:

```text
SSI
predicate/range locking
Derby lock-layer integration
explicitly unsupported full SERIALIZABLE semantics for delos_mvcc
```

It must not be implemented opportunistically inside the commit coordinator.

## Work outside Phase 7

The following are real future improvements, not incomplete Phase 7 scope:

```text
sharing additional WAL/outcome/page durability fences across transactions
full serializability
mature database-wide buffer and I/O management
checkpoint-generation plus WAL-tail backup
broader end-to-end concurrency and process-crash stress
operational tooling and field hardening
```

Any future force-sharing change must preserve the current direct crash invariant
and add a differential recovery proof before removing a force.

## Closeout gates

The closeout is guarded by:

```text
:delosdb-tests:runDelosMvccSerializableSemanticsTest
:delosdb-storage-mvcc:runMvccPreparedCommitCoordinatorTest
:delosdb-storage-mvcc:runMvccTransactionGroupCommitTest
:delosdb-storage-mvcc:runMvccTransactionGroupCommitHardeningTest
:delosdb-storage-mvcc:runMvccDatabaseMaintenanceServiceTest
:delosdb-storage-mvcc:runMvccDatabaseBackupCoordinatorTest
:delosdb-tests:runDelosMvccSqlIntegrationTest
s0CloseoutVerification
```

## Production-hardening addendum

The post-closeout static review found two release blockers in failure handling:
post-COMMITTED publication failures could leave ambiguous live state, and
maintenance shutdown could continue while a worker still owned table resources.

The production-hardening overlay closes them by:

```text
staging complete payloads before grouped COMMITTED status publication
recording explicit transaction-status/page-transaction correlation
using database COMMITTED status to repair a missing local outcome mirror
forbidding ABORT after durable COMMITTED
marking page or ordered-index publication failures recovery-required
separating maintenance failure from transaction success
requiring real maintenance-worker quiescence before resource close
making store close exception-safe
```

The authoritative protocol is `PHASE-7-DURABILITY-PROTOCOL.md`. The focused
change and proof inventory is `PHASE-7-PRODUCTION-HARDENING.md`.

Additional hardening gates are:

```text
:delosdb-storage-mvcc:runMvccPreparedCommitBatchTest
:delosdb-storage-mvcc:runDelosMvccLongReaderValidation
```
