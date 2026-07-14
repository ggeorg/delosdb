# Phase 7.6 — Immutable Prepared Commit and Table Durability Coordinator

## Purpose

Phase 7.5 showed that same-table commit time is dominated by page/checkpoint
publication and ordered-index rebuilding. Moving only a few microseconds of
validation outside the table write lock would not materially improve throughput.

Phase 7.6 therefore introduces an explicit ownership boundary without claiming
concurrent physical publication:

```text
two non-conflicting same-table writers may prepare immutable commit input
concurrently

one per-table durability coordinator still publishes commits in order
```

No durable format, force count, recovery rule, or SQL behavior changes in this
slice.

## Current commit route

A writable commit now follows this order:

```text
commit request accounting
    short table read-lock snapshot
        verify the transaction handle is active
        capture surviving write intents
        capture the write-intent revision
    release table read lock

    immutable preparation outside the table write lock
        deep-copy logical row values
        encode every non-delete row payload
        validate page-record size limits
        retain immutable logical changes and encoded page payloads

    process backup durable-mutation guard
        per-table durability coordinator
            inherited-table write lock
                revalidate active handle and write-intent revision
                revalidate same-row writer ownership
                force COMMITTED transaction status
                publish WAL/payload/outcome/page/checkpoint state
                rebuild ordered-index state
                publish transaction diagnostics and remove active handle
                evaluate or run current foreground maintenance
            release table write lock
        release durability coordinator
    release backup guard
commit acknowledgement
```

The preparation snapshot uses the table read lock only long enough to copy the
transaction-local intent list and revision. Other prepared commits may take the
same read lock concurrently. Inserts, updates, deletes, savepoint rollback, and
abort still require the table write lock and therefore advance or invalidate the
captured revision before publication.

## Immutable prepared commit

`MvccPreparedCommit` owns:

```text
transaction handle and native transaction identity
immutable logical changed-row list
immutable encoded page payload batch
captured write-intent revision
captured surviving write-intent count
immutable diagnostic payload summaries
```

`PageVolumeMvccStateStore.prepareChangedRows()` performs row encoding and page
payload-size validation before the durability coordinator. The resulting
`PreparedChanges` object does not expose its encoded byte arrays. The durable
path consumes those already prepared bytes instead of encoding rows again while
holding the table write lock.

## Publication revalidation

Immediately before transaction-status publication, while the table write lock
is held, the coordinator verifies:

```text
the transaction handle is still active
the write-intent revision still matches the prepared snapshot
no other active transaction owns a write intent for any prepared row
```

A transaction mutated after preparation is rejected rather than publishing
stale prepared state. Same-row writers remain conflicting; non-conflicting rows
can prepare concurrently.

If payload preparation fails, transaction abort cleanup still enters the backup
durable-mutation guard before forcing the ABORT status. This preserves the
existing backup exclusion rule even though encoding itself occurs before that
guard.

## Explicit durability coordinator

The initial Phase 7.6 implementation used a fair per-table `ReentrantLock` to
own durable publication order. The follow-up enrollment slice replaces that
implicit wait set with a bounded FIFO coordinator while preserving the same
one-at-a-time publication behavior. It remains separate from the inherited-table
read/write lock.

The distinction remains intentional:

```text
durability coordinator
    owns ordering of physical same-table commit publication

table write lock
    protects mutable table, transaction-handle, page/index, and maintenance state
```

Physical publication remains serialized in this slice. The coordinator makes
that serialization explicit instead of relying on the table lock as both the
logical transaction boundary and durability queue.

Vacuum, purge, drop, close, and backup retain their existing exclusion behavior.
They are not made concurrent with commit publication.

## Observability

The commit JFR event adds:

```text
preparationNanos
durabilityCoordinatorWaitNanos
durabilityCoordinatorHoldNanos
tablePreparationConcurrency
processPreparationConcurrency
```

The existing fields remain:

```text
tableLockWaitNanos
tableLockHoldNanos
tableDurabilityQueueConcurrency
tableDurabilityExecutionConcurrency
```

The standalone concurrent-commit benchmark includes the measurements in
console, CSV, JSON, and human reports. The bounded enrollment follow-up also
reports coordinator mode and enrollment depth.

The expected first proof is:

```text
tablePreparationConcurrency > 1
tableDurabilityExecutionConcurrency = 1
```

That result means logical/encoded commit preparation overlaps while durable
same-table publication remains ordered.

## Force and recovery contract

For ordinary inline-row commits, the established contract remains:

```text
2 transaction-status forces
1 transaction-outcome force
1 WAL force
2 page-volume forces
```

The transaction-complete outcome fence, one main-table page batch, crash replay,
checkpoint ordering, and ordered-index rebuild are unchanged.

## Remaining bottlenecks

Phase 7.6 does not reduce the measured dominant publication work:

```text
page/checkpoint state persistence
complete ordered-index rebuild
```

The next evidence-driven slice should use the new coordinator measurements to
choose between:

```text
incremental ordered-index maintenance
checkpoint publication amortization
same-table durability pipelining
cross-transaction group commit
```

Cross-transaction group commit remains premature until the page/checkpoint and
index publication boundaries are decomposed enough to admit more than one
same-table transaction to a shared durability fence.

## Out of scope

This slice does not:

```text
allow concurrent same-table page publication
allow concurrent ordered-index rebuilding
change commit-sequence allocation
change transaction-status ordering
change WAL, mutation-log, outcome-log, page, checkpoint, or recovery formats
change force counts
add group commit
move maintenance to a database-level service
change SQL, JDBC, DRDA, or catalog behavior
```

## Phase 7.7 follow-up

Phase 7.7 acts on the dominant serialized page-state path identified after the
prepared-commit split. It batches free-space-map and row-directory publication
at the same transaction boundary. See
`docs/PHASE-7-TRANSACTION-SIDECAR-FORCE-BATCH.md`.

## Bounded enrollment follow-up

Prepared commits now enter a bounded FIFO before individual durability
publication. Normal execution uses queued mode; the former direct fair-lock mode
is retained temporarily for differential tests only. See:

```text
docs/PHASE-7-COMMIT-ENROLLMENT-QUEUE.md
```

This follow-up still is not group commit: no force is shared and each transaction
receives an individual result.

## Transaction group-commit follow-up

The prepared-commit boundary now supports bounded leader/follower groups. The
first shared durability operations are the COMMITTED status append and final
ordered-index rebuild. Page-state persistence remains individually fenced. See
`docs/PHASE-7-TRANSACTION-GROUP-COMMIT.md`.
