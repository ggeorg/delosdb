# Phase 7.5 transaction group commit

## Scope

This slice introduces the first real cross-transaction durability sharing for
prepared commits on one `delos_mvcc` table.

The bounded enrollment queue now has a leader/follower group mode. The FIFO head
waits for at most one millisecond and drains at most sixteen already-prepared
commits from the bounded sixty-four-entry enrollment queue.

The group executes under one backup mutation guard and one inherited-table write
lock. Physical publication remains ordered.

## Shared durability in this slice

The group leader performs these operations once for the group:

```text
allocate ordered commit sequences
append every COMMITTED transaction-status record
force the transaction-status log once
rebuild and force the ordered index once after member persistence
run post-commit maintenance once
```

Each member still owns an individual:

```text
page-volume WAL transaction batch and force
prepared mutation payload batch and force
transaction-outcome fence and force
main-table page batch and force
checkpoint publication
subsystem recovery publication
```

This is real transaction group commit because multiple transactions share a
transaction-status durability force. It is intentionally not yet the final
all-subsystem group fence.

## Modes and comparison boundary

Normal production construction always uses group mode:

```text
group   bounded leader/follower grouping
```

The former JVM property `delosdb.mvcc.commit.mode` is retired. Production code
no longer reads it, so deployment configuration cannot silently select the
older paths.

The coordinator still has package-private `DIRECT` and `QUEUED` modes for
focused differential tests:

```text
direct  one synchronous commit under the former fair lock
queued  bounded FIFO enrollment with group size one
```

All modes execute the same publication implementation. The comparison modes
are test construction choices, not supported production configuration.

## Ordering

For one successful group:

```text
concurrent immutable preparation
bounded FIFO enrollment
bounded group-formation delay
backup durable-mutation guard
inherited-table write lock
per-member revalidation
one ordered commit-sequence allocation batch
one forced COMMITTED status append
per-member page-state persistence
one ordered-index rebuild
per-member transaction-state publication
one maintenance decision
individual acknowledgements
```

Commit sequences preserve FIFO group order.

## Failure semantics

```text
failure before enrollment
    no group request exists

member revalidation conflict
    only that member is aborted and rejected
    non-conflicting members may continue

shared status append or force failure
    every surviving member receives the shared failure
    no page publication is attempted by the group

individual page-state persistence failure
    that member receives its failure
    other status-committed members continue publication

shared ordered-index or maintenance failure
    every otherwise successful member receives the shared failure

coordinator processor failure
    leader and followers receive the same failure
    queue ownership is released
```

A transaction is acknowledged only after its individual page-state publication
and the shared post-publication work succeed.

## Observability

The commit JFR event adds:

```text
groupCommitId
groupCommitSize
groupCommitLeader
groupCommitWaitNanos
groupCommitSharedForceCount
groupCommitLeaderFailure
groupCommitFollowerFailure
```

The benchmark reports:

```text
groups
grouped commits
average transactions per group
maximum group size
average group wait
shared forces per commit
leader and follower failures
```

The expected two-transaction force comparison is:

```text
direct:
    transaction-status forces = 4
    page-volume forces         = 4

group:
    transaction-status forces = 3
    page-volume forces         = 3
```

The status total consists of two ACTIVE forces and one shared COMMITTED force.
The page total consists of two member main-table forces and one shared ordered-
index force.

## Required proof

The focused gate must prove:

```text
two non-conflicting transactions form one group
one leader and one follower are reported
commit sequences remain ordered
one status force is shared
one ordered-index rebuild is shared
direct and group modes reopen to the same logical state
single-transaction force counts remain unchanged
a shared processor failure reaches leader and follower
```

## Coordinator lifecycle hardening

The coordinator now has an explicit graceful shutdown contract:

```text
stop accepting new submissions
allow already-enrolled groups to finish
wait until the FIFO and in-flight group are empty
mark the coordinator closed
reject later submissions
```

`MvccInheritedTable.close()` drains the commit coordinator before closing page,
sidecar, index, and purge resources. A table close therefore cannot race an
already-enrolled commit into a closed page store.

Submissions that have not entered the queue when shutdown begins are rejected
with `CoordinatorClosedException`. Already-enrolled transactions retain their
individual commit result; shutdown does not silently convert them into aborts.

## Backup and failure hardening

The focused hardening proof covers:

```text
backup snapshot owns the exclusive mutation boundary
    prepared commits may enroll
    the group blocks before durable publication
    the backup-visible committed image remains unchanged

concurrent table close
    stops new commit enrollment
    waits for the backup-blocked group
    closes storage only after the enrolled commits finish

shared COMMITTED-status force failure
    leader and follower receive the same failure
    no member page publication begins
    transactions remain active and may be explicitly aborted

one preparation failure beside one valid transaction
    the invalid transaction is aborted before enrollment
    the valid transaction commits and reopens normally
```

This proves the required Phase 7.5 cases for partial preparation failure,
leader/shared-force failure, backup start with queued commits, and shutdown with
queued commits.

## Phase 7.5 closeout

The planned Phase 7 transaction group-commit requirement is complete: prepared
same-table commits form bounded groups, share a real durability operation,
retain individual results, propagate shared failures honestly, drain during
shutdown, and coordinate with backup.

WAL, prepared-payload, outcome, main-page, checkpoint, and subsystem-recovery
forces remain individually fenced. Sharing any of those boundaries is a future
throughput optimization, not unfinished Phase 7 scope, and requires new crash
proof before a force is removed.

`DIRECT` and `QUEUED` remain package-private test modes only. Production table
construction always selects `GROUP`.


## Database-scoped backup ownership

Group publication now enters the coordinator owned by the transaction's
database store. Grouped commits in one database remain frozen during that
database's sidecar backup, while unrelated databases continue independently.
The coordinator records committed-status counts at backup start and end; the
counts remain equal across the exclusive copy interval.
