# Phase 7 — Bounded Commit Enrollment Before Group Commit

## Purpose

The prepared-commit split proved that non-conflicting same-table writers can
prepare independently, but the previous durability coordinator was still only
a fair lock. That was enough to serialize publication, but it did not establish
the explicit bounded enrollment boundary required by real group commit.

This slice replaces that implicit wait set with a bounded FIFO enrollment queue:

```text
prepared commit
    bounded table enrollment
        ordered individual durability publication
```

It deliberately does not share a force between transactions. Every enrolled
transaction still executes the complete existing durability protocol and
receives its own success or failure.

## Coordinator modes

The coordinator has two package-private modes:

```text
DIRECT
    the previous fair-lock behavior

QUEUED
    bounded FIFO enrollment followed by one-at-a-time publication
```

Normal tables use `QUEUED`. `DIRECT` is retained temporarily for differential
proof only. Both modes call the same commit-publication code; there are not two
commit engines.

The comparison mode verifies identical:

```text
logical committed rows
close-and-reopen state
status, outcome, WAL, sidecar, and page force counts
pre-publication conflict result
```

Once cross-transaction grouping is proven and rollback evidence is no longer
needed, the direct mode should be removed rather than maintained permanently.

## Bounded FIFO enrollment

Queued mode uses a fair bounded permit set and a FIFO ticket queue. The default
capacity is 64 enrolled transactions per table, including the transaction
currently publishing.

A transaction:

```text
acquires one queue-capacity permit
appends one FIFO enrollment ticket
waits until its ticket is first
executes its own durability publication on its calling thread
removes its ticket
signals the next ticket
releases queue capacity
```

The calling thread remains responsible for its transaction. This slice does not
introduce a background commit thread, leader-owned follower execution, shared
failure state, or cancellation protocol.

The queue is released in `finally` through an `AutoCloseable` permit, so a
validation or durability failure cannot strand later enrollments.

## Backup and table locking

The existing ordering remains:

```text
backup durable-mutation guard
    bounded table enrollment
        inherited-table write lock
            revalidation
            transaction status
            page/checkpoint publication
            ordered-index rebuild
            transaction-state publication
            maintenance
```

Queued transactions continue to hold the shared backup mutation guard while
waiting for their turn. Backup hardening remains a later phase; this slice does
not change the current freeze semantics.

Physical same-table publication remains serialized and the table write lock
still protects the mutable publication state.

## Observability

The commit JFR event adds:

```text
durabilityCoordinatorMode
durabilityEnrollmentDepth
```

`durabilityEnrollmentDepth` is the number of tickets present immediately after
the transaction enrolls, including the currently publishing transaction.

The benchmark reports:

```text
coordinatorMode=<direct|queued>
enrollmentDepth=<maximum observed depth>
```

The required transition proof is:

```text
tablePreparationConcurrency = 2
durabilityEnrollmentDepth   = 2
tableDurabilityExecutionConcurrency = 1
```

This proves that multiple prepared commits reach an explicit queue while each
still performs individual ordered durability work.

## Failure semantics

This slice preserves individual transaction results:

```text
failure before enrollment
    no queue entry is created

failure during one enrolled publication
    that transaction receives the failure
    its permit is removed
    the next enrollment may proceed

successful publication
    only that transaction is acknowledged
```

No transaction inherits another transaction's result. Shared force failure
propagation belongs to the real group-commit phase.

## Force and recovery contract

No force is removed, deferred, or shared. Ordinary inline-row commits retain:

```text
2 transaction-status forces
1 transaction-outcome force
1 WAL force
constant transaction sidecar forces
2 page-volume forces
```

The prepared mutation batch, transaction-complete outcome fence, crash recovery,
checkpoint order, and ordered-index rebuild are unchanged.

## Gate before real group commit

Real group commit may begin only after these proofs are green:

```text
bounded queue preserves FIFO order
queue capacity is enforced
queued publication cannot strand followers after failure
DIRECT and QUEUED produce the same durable result
DIRECT and QUEUED produce the same pre-publication conflict result
multiple same-table prepared commits reach enrollment depth greater than one
physical durability execution remains one
force counts remain unchanged
```

The next phase may add leader/follower grouping and one shared durability
operation, initially behind the same differential proof boundary.

## Out of scope

This slice does not:

```text
share WAL, status, outcome, sidecar, or page forces
create a group-commit leader
execute follower commits on another thread
change commit-sequence allocation
change durable formats
change recovery behavior
change physical same-table serialization
change checkpoint or index publication
change backup, vacuum, purge, SQL, JDBC, DRDA, or catalog behavior
```
