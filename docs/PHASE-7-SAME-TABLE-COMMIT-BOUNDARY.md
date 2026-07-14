# Phase 7.5 — Same-Table Commit Boundary Audit

## Purpose

Phase 7.4 removed per-row outcome and main-page force amplification. The JDK 25
benchmark then showed that one-row and eight-row same-table transactions still
execute with:

```text
table durability execution concurrency = 1
```

This slice measures and documents the remaining table-wide commit boundary
before that boundary is changed. It does not remove or replace a lock.

## Current boundary

A writable commit currently enters the boundaries in this order:

```text
commit request accounting
    process backup mutation guard
        inherited-table write lock
            changed-row validation
            transaction-status COMMITTED publication
            page/WAL/outcome/checkpoint persistence
            ordered-index rebuild
            transaction-handle publication and removal
            optional foreground purge decision or execution
        release inherited-table write lock
    release backup mutation guard
commit acknowledgement
```

The backup coordinator is process-wide. The inherited-table write lock is
per table. Different tables and databases can therefore execute inside their
own table boundaries concurrently, while commits to one table remain serialized.

## Measured phases

The existing `org.apache.derby.delosdb.mvcc.Commit` JFR event now reports these
non-overlapping phases inside the current table write lock:

| Field | Current work |
|---|---|
| `validationNanos` | Derive surviving changed rows, encode-check payloads, and abort invalid active transactions. |
| `transactionStatusCommitNanos` | Allocate the commit sequence and force the transaction-table COMMITTED record. |
| `pageStatePersistenceNanos` | Plan changed rows; force the page WAL, prepared payload batch, outcome fence, and main-table page batch; publish row-directory, visibility, recovery, and checkpoint state. |
| `orderedIndexRebuildNanos` | Rebuild and force the ordered-index image from committed rows. |
| `transactionStatePublicationNanos` | Publish commit diagnostics, clear transaction-local write intents, and remove the transaction handle from the active registry. |
| `maintenanceNanos` | Evaluate or run the current post-commit purge path. |

`tableLockHoldNanos` remains the outer measurement. The focused audit proof
requires it to contain the sum of all six measured phases.

The benchmark CSV, JSON, human report, and console output expose the same phase
measurements. These measurements are diagnostic only and are collected only
when the commit JFR event is enabled.

## Other table-lock users

The table write lock also currently protects:

```text
transaction-handle registration and removal
write-intent append and same-row conflict scans
savepoint mutation
row-id allocation
abort handling
ordered-index fallback counters and mutable lookup state
drop, close, vacuum, and purge
```

The table read lock protects snapshots, scans, reads, committed-image reads, and
most diagnostics. This is broader than the final architecture should require,
but it is the current correctness boundary.

## Lower-level serialization already present

Removing the inherited-table write lock by itself would not create a safe or
useful concurrent commit path:

1. `MvccTransactionManager.commit()` is synchronized because it allocates the
   commit sequence and changes transaction-table state.
2. `MvccTransactionStatusStore` serializes forced status-log appends.
3. `PageBackedMvccTable.persistCommittedTransaction()` is synchronized around
   mutable directory, page-cache, mutation-log, outcome-log, visibility-map,
   purge-queue, and page-store state.
4. `PageVolumeMvccStateStore` owns mutable page-volume transaction identifiers,
   checkpoint publication, and recovery-record sequencing that are not an
   independent concurrent commit protocol.
5. `MvccInheritedIndexMaintenance.rebuildFromCommittedRows()` rebuilds the
   complete ordered index after every commit and requires one stable committed
   image.

The table lock currently composes these authorities into one safe order. A lock
removal that ignores them would either remain serialized at a hidden monitor or
introduce ordering and recovery defects.

## Required lock split

The next behavior slice should introduce explicit ownership boundaries rather
than deleting the table lock in one step.

### Transaction preparation boundary

A short transaction-state boundary should own:

```text
transaction handle validity
stable snapshot of surviving write intents
same-row writer ownership
savepoint/write-intent mutation exclusion
commit-in-progress state
```

Two transactions that modify different rows should be able to complete this
preparation independently.

### Commit-sequence boundary

A short transaction-table coordinator should own:

```text
commit-sequence allocation
COMMITTED status publication
terminal transaction-table state
```

This ordering remains serialized, but it does not require the table-wide lock.

### Storage durability boundary

The first split may retain one per-table durability coordinator around:

```text
page-volume identifiers
WAL/payload/outcome/page/checkpoint publication
recovery-record sequencing
```

That still serializes physical commits, but it lets non-conflicting writers
prepare independently and queue at an explicit durability boundary. The JFR
queue and phase timings then show the next real bottleneck.

### Ordered-index boundary

The full ordered-index rebuild must remain exclusive until it is replaced by an
incremental, transaction-correlated index update protocol. Moving it outside an
exclusive publication boundary would expose an index image that does not match
the committed row image.

### Maintenance boundary

Vacuum and purge must remain mutually exclusive with page/index publication.
Foreground commit should eventually schedule database-level maintenance rather
than execute table-local maintenance while holding the commit boundary.

## First behavior target

The next implementation should prove only this:

```text
Two same-table transactions that modify different rows can create immutable
prepared commits concurrently and queue at an explicit per-table durability
coordinator, while same-row writers still conflict deterministically.
```

It should not yet promise concurrent page materialization or group commit.

Required proofs:

```text
non-conflicting prepared commits can overlap
same-row conflict ownership is preserved
one transaction cannot commit or abort twice
savepoint rollback cannot mutate an already prepared commit
commit sequences remain strictly ordered
visibility follows commit-sequence order
crash recovery remains all-or-none
vacuum and backup remain excluded from durable publication
```

## Out of scope

This audit does not:

```text
change table-lock scope
change transaction status ordering
make PageBackedMvccTable concurrent
replace full ordered-index rebuild
add group commit
move maintenance to a database service
change durable formats
change SQL, JDBC, DRDA, or catalog behavior
```
