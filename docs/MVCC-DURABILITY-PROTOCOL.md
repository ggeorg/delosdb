# DelosDB MVCC durability protocol

## Purpose

This document describes the current RawStore-backed `delos_mvcc` durability boundary.
It is intentionally limited to executable v1 behavior.

## Single authority

`delos_mvcc` has no external transaction provider, sidecar journal, commit-decision
file, lifecycle marker, or post-commit publication coordinator.

Durable authority is the inherited Derby RawStore transaction:

```text
SQL statement
    -> TransactionManager / RawTransaction
    -> RawStore containers and log records
    -> inherited RawStore commit, rollback, savepoint, and recovery
```

Heap and MVCC changes made in one JDBC transaction share that same RawStore
transaction and therefore one commit or rollback outcome.

## DML

A RawStore-backed MVCC write:

1. enters `MvccRawStoreTransactionContext`;
2. allocates or reads MVCC metadata from RawStore containers;
3. writes versions and index state through the inherited raw transaction;
4. publishes nothing outside RawStore at commit;
5. relies on RawStore undo for rollback and rollback-to-savepoint.

The language connection commits and aborts through the ordinary
`TransactionController` path. There is no DelosDB participant registry around
that operation.

## DDL lifecycle

MVCC create, drop, and index lifecycle changes are transaction-local RawStore
container operations. Their outcome is governed by the same raw transaction as
catalog changes.

No filesystem lifecycle marker is written. Crash recovery replays or undoes the
RawStore operations using Derby's normal log and recovery machinery.

## Transaction identity and visibility

Database-wide transaction identifiers, commit sequences, snapshots, version
chains, and visibility metadata are stored in the RawStore-owned MVCC metadata
and table containers. They are not mirrored into an external decision journal.

## Savepoints

JDBC and SQL savepoints use the inherited transaction-controller savepoint path.
`MvccRawStoreTransactionContext` receives the access-method lifecycle callbacks
and restores its transaction-local MVCC state consistently with RawStore undo.

## XA boundary

Heap XA behavior remains inherited Derby behavior.

RawStore-backed MVCC writes and MVCC DDL in XA transactions fail before mutation
with SQLState `0A000`. Read-only access remains independent of the removed
external-participant architecture.

## Backup and restore

Backup and restore copy the ordinary Derby database image, including RawStore
containers and logs. Current MVCC databases create no `delos_mvcc` filesystem
sidecar.

Any artifact from the retired external format is rejected before boot, backup,
or restore. It is not copied, ignored, or migrated implicitly.

## Stored-format reservation

Two-byte format slots 480 through 483 belonged to the retired external commit
and lifecycle operations. They remain reserved and unregistered and must never
be reassigned.

## Executable proof

The focused authority lanes are:

```text
:delosdb-tests:runDelosMvccTransactionParticipationTest
:delosdb-tests:runDelosMvccRawStoreSqlTransactionCutoverTest
:delosdb-tests:runDelosMvccRawStoreMixedHeapTransactionTest
:delosdb-tests:runDelosMvccRawStoreMultiTableTransactionTest
:delosdb-tests:runDelosMvccTransactionalDdlTest
:delosdb-tests:runDelosMvccRawStoreDecisionWalCrashTest
```

The invariants are:

```text
one RawStore transaction authority
no external participant registry
no external decision or lifecycle files
no duplicate commit publication path
no MVCC XA mutation
```
