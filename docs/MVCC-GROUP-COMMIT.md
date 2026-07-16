# MVCC transaction group commit

## Purpose

The `delos_mvcc` commit coordinator allows independently prepared transactions
on one table to share selected durable publication work while preserving
ordered visibility and individual transaction outcomes.

Normal production construction uses a bounded leader/follower group:

```text
maximum enrollment depth    64
maximum group size          16
maximum collection delay    1 millisecond
```

The FIFO head becomes the leader. It collects already-prepared followers,
then publishes the group under one database backup-mutation guard and one
inherited-table write lock.

## Concurrency boundary

A write transaction performs immutable logical preparation before it enters the
coordinator. Non-conflicting same-table transactions can therefore prepare and
enroll concurrently.

Physical publication remains ordered:

```text
concurrent immutable preparation
    -> bounded FIFO enrollment
    -> bounded group formation
    -> database durable-mutation guard
    -> one inherited-table write-lock acquisition
    -> ordered group publication
    -> individual acknowledgements
```

This narrows the former per-transaction commit lock boundary without claiming
fine-grained concurrent page publication.

## Shared and individual durability work

The leader performs these operations once for the group:

```text
assign ordered commit sequences
append all COMMITTED transaction-status records
force the transaction-status log once
rebuild and force the ordered index once
run one post-commit maintenance decision
```

Each transaction retains its own:

```text
page-volume WAL payload and force
prepared page-mutation payload and force
local transaction-outcome record and force
main-page materialization and force
subsystem recovery publication
checkpoint publication
```

This is real cross-transaction group commit because multiple transactions share
a terminal transaction-status force. It is intentionally a partial durability
group rather than a PostgreSQL- or InnoDB-style shared WAL flush for every
member.

## Production and comparison modes

Production table construction always uses group mode. The former JVM property:

```text
delosdb.mvcc.commit.mode
```

is retired and no longer influences production behavior.

The coordinator retains package-private construction modes for focused tests:

```text
DIRECT
    synchronous publication through the same implementation

QUEUED
    bounded FIFO enrollment with group size one

GROUP
    bounded leader/follower grouping
```

These modes are comparison tools, not supported deployment configuration. They
must not become separate commit engines.

## Publication sequence

For one successful group:

```text
1. revalidate every prepared member
2. reject and abort only members that fail before durable commit authority
3. assign ordered commit sequences to surviving members
4. force complete WAL and prepared page-mutation payloads per member
5. force one grouped COMMITTED transaction-status append
6. publish each committed member's local outcome, pages, sidecars, and checkpoint
7. rebuild the ordered index once from committed rows
8. attempt post-commit maintenance once
9. return each member's individual outcome
```

Commit sequences preserve FIFO group order. An unused sequence created by a
member that fails before COMMITTED publication is a harmless gap.

## Failure semantics

### Before grouped COMMITTED publication

```text
preparation or revalidation failure
    abort and reject only that member
    allow unrelated valid members to continue

payload-staging failure
    append local/WAL ABORT when possible
    abort and reject only that member

failure before the grouped status force starts
    abort every staged member
    publish no committed transaction
```

### At the grouped status-force ambiguity boundary

If the grouped transaction-status publication call fails, the implementation
cannot safely infer that no COMMITTED record reached durable storage.

It therefore:

```text
appends no contradictory ABORT
marks the table recovery-required
returns an explicit outcome-unknown or committed/recovery-required failure
serves no later table operation until reopen and strict recovery
```

### After grouped COMMITTED publication

A page, sidecar, checkpoint, recovery-record, or ordered-index publication
failure does not undo the durable commit decision.

The implementation:

```text
keeps the transaction committed
marks the table recovery-required
returns an explicit committed/recovery-required failure
prevents later live reads or writes
uses reopen recovery to reconstruct authoritative state
```

If one committed member fails page publication, the fail-stop state prevents
later members from being treated as normally published in the live table. Their
durable payload and grouped COMMITTED status remain available to recovery.

### Maintenance failure

Post-commit maintenance is not part of the commit decision. A maintenance
`RuntimeException`:

```text
does not change transaction success
does not append ABORT
does not poison the table
increments failure diagnostics and records the latest failure summary
```

### Coordinator processor failure

The coordinator captures a processor failure long enough to release every
leader/follower waiter with the same failure. Fatal JVM errors must still be
re-thrown by the executing thread after waiter release.

## Shutdown

Coordinator shutdown is ordered:

```text
stop accepting new submissions
allow already-enrolled work to finish
wait for the FIFO and in-flight group to become empty
mark the coordinator closed
reject later submissions
```

`MvccInheritedTable.close()` drains the coordinator before closing page,
sidecar, index, and maintenance resources. A close cannot race an enrolled
commit into an already-closed store.

## Backup interaction

Grouped publication uses the coordinator owned by the database store.

```text
backup(database A) active
    grouped commit(database A) waits before durable publication
    grouped commit(database B) may continue independently
```

Prepared commits may enroll while a backup owns the exclusive boundary, but
they cannot publish durable state until the boundary is released.

## Observability

Commit JFR events and benchmark output expose:

```text
groupCommitId
groupCommitSize
groupCommitLeader
groupCommitWaitNanos
groupCommitSharedForceCount
groupCommitLeaderFailure
groupCommitFollowerFailure
table-lock wait and hold time
durability execution concurrency
per-subsystem force counts
```

The expected two-transaction status-force comparison is:

```text
DIRECT
    two ACTIVE forces
    two COMMITTED forces

GROUP
    two ACTIVE forces
    one grouped COMMITTED force
```

Page, WAL, outcome, and checkpoint counts remain separately measured because
those boundaries are not yet fully shared.

## Proof obligations

Focused tests must prove:

```text
two non-conflicting transactions form one group
leader and follower receive ordered commit sequences
one transaction-status force is shared
the ordered-index rebuild is shared
single-member behavior remains correct
DIRECT and GROUP reopen to the same logical state
one pre-publication member failure does not abort valid members
status-force ambiguity never appends ABORT
post-COMMITTED failures enter fail-stop recovery-required state
maintenance failure does not falsify commit success
backup and shutdown preserve queue and resource ownership
```

Any extension of shared durability work requires crash proofs before an
individual force is removed.
