# Phase 7 database-scoped backup coordination

## Purpose

Phase 7.7 keeps the existing freeze-based online-backup algorithm and hardens
its ownership, mutation coverage, and evidence.

The previous boundary was one fair read/write lock for the entire JVM. That
made the copied MVCC sidecar image coherent, but a backup of one database could
stall durable mutations in every other DelosDB database in the process.

The current boundary is database-scoped:

```text
normalized database identity
    one backup coordinator
        shared guard for durable MVCC mutations
        exclusive guard for sidecar backup copy
```

A small process registry resolves leases for the same normalized database
identity. The registry is not the execution boundary: each database has its own
lock, counters, and lifecycle. The database storage store owns the long-lived
lease; the inherited RawStore backup helper obtains a short-lived lease while
copying that database's sidecars.

## Preserved backup algorithm

Online backup still performs:

```text
create BACKUP-IN-PROGRESS marker
acquire the database exclusive backup guard
copy stable MVCC sidecar files
write and force the backup manifest
delete and force removal of the in-progress marker
release the exclusive guard
```

No fuzzy prefix or WAL-last copy algorithm is introduced. The backup contains
the durable image that existed when the exclusive guard was acquired.

## Durable mutation coverage

Every provider operation that may create, modify, force, rewrite, delete, or
close MVCC durable state now enters an explicitly named shared guard:

| Mutation | Covered durable work |
|---|---|
| `TRANSACTION_BEGIN` | forced `ACTIVE` transaction-status record |
| `COMMIT_PUBLICATION` | grouped `COMMITTED` statuses, page/outcome/WAL publication, index rebuild, commit maintenance |
| `TRANSACTION_ABORT` | forced `ABORTED` transaction-status record |
| `PREPARATION_FAILURE_CLEANUP` | terminal abort after failed immutable preparation |
| `VACUUM` | explicit safe vacuum |
| `ASYNCHRONOUS_MAINTENANCE` | database maintenance-service vacuum |
| `DROP_DURABLE_STATE` | sidecar and page-state deletion |
| `TABLE_CLOSE` | final page-volume close/flush boundary |

Transaction-local intent creation, reads, scans, savepoints, and row-id
reservation do not mutate durable files and remain outside this boundary.

## ACTIVE and ABORT safety

`MvccTransactionManager.begin()` forces an `ACTIVE` status record. Abort forces
an `ABORTED` record. Both operations now use the same database boundary as
commit.

Therefore an online sidecar copy cannot sample:

- the transaction-status log before `ACTIVE` while another sidecar already
  reflects that transaction;
- the status log between an in-memory abort and its durable `ABORTED` record;
- a partial terminal status transition that crosses the backup copy.

## Commit counters at the snapshot boundary

Each coordinator records the number of transactions whose grouped
`COMMITTED` status publication completed while the shared mutation guard was
held.

When backup acquires the exclusive guard it records:

```text
lastBackupStartCommittedTransactionCount
```

Immediately before releasing the guard it records:

```text
lastBackupEndCommittedTransactionCount
```

These values must be equal for a completed backup snapshot. Transactions may
commit before the exclusive guard is acquired or after it is released, but not
while the copied sidecar image is being sampled.

The counter describes the durable COMMITTED-status boundary. It is not an
application acknowledgement counter.

## Writer-stall evidence

The coordinator exposes diagnostic snapshots containing:

- current durable-mutation waiters;
- active durable mutations;
- maximum active durable mutations;
- total and maximum durable-mutation wait time;
- backup start and completion counts;
- total and maximum backup acquisition wait time;
- per-mutation entry counts;
- committed-transaction count;
- last backup start/end committed-transaction counts.

The focused proof holds the exclusive guard and waits until a writer is visible
in `waitingDurableMutationCount`. This proves that the tested writer reached
the actual database backup boundary rather than merely being delayed elsewhere.

Commit JFR continues to report `backupWaitNanos` for per-commit operational
measurement.

## Different-database isolation

The focused test opens two database stores in one JVM and proves:

```text
backup(database A): active
commit(database A): blocked at A's boundary
commit(database B): completes
```

Two tables opened through one database store also prove that they share the
same coordinator instance.

## Lifecycle

The database storage store owns one coordinator lease for its full lifetime.
All tables opened by that store receive the same coordinator. Store shutdown:

```text
stops database maintenance
closes all registered tables under the database boundary
releases the coordinator lease
```

The RawStore sidecar helper resolves the same canonical database identity and
holds a temporary lease for the duration of backup copying.

## Explicit non-goals

This phase does not:

- change Derby RawStore backup control flow;
- replace coordinated freezing with a checkpoint-generation/WAL-tail design;
- permit commits to continue inside one database while its sidecars are copied;
- share a backup lock across databases;
- change durable formats or the backup manifest format;
- change group-commit ordering or acknowledgement rules;
- change recovery replay;
- change transaction isolation;
- add a JVM-global backup executor or service.
