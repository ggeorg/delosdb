# V1 RawStore MVCC transaction-duration logical locking

## Status

```text
IMPLEMENTED
```

RawStore-backed MVCC uses database lock-manager identities for semantic conflicts while retaining
Derby RawStore as the only physical and transactional authority.

## Lock authority

The implementation uses the inherited database `LockFactory` obtained from `RawStoreFactory`.
Locks belong to the same RawStore transaction compatibility space and transaction group that own the
page mutations. They are released by normal transaction commit, abort, or destruction. There is no
MVCC-owned lock manager, lock file, deadlock detector, timeout service, or recovery path.

Three immutable logical identities are used:

```text
TABLE_SCHEMA(table metadata-container identity)
ROW(table identity, stable MvccRowId)
UNIQUE_KEY(table identity, logical constraint ordinal, typed SQL key)
```

`MvccRowId`, not a page number, slot number, or `RecordHandle`, identifies a row lock. Unique-key
identity uses Derby typed SQL comparison and cloned key values. Locks for all old and new keys of one
statement are sorted before acquisition, giving a deterministic constraint/key order.

## DML protocol

Every RawStore-backed MVCC mutation first acquires a shared table-schema lock.

```text
INSERT
    shared table-schema lock
    deterministic exclusive unique-key locks
    physical RawStore mutation

UPDATE
    shared table-schema lock
    exclusive stable-row lock
    deterministic exclusive old/new unique-key locks
    physical RawStore mutation

DELETE
    shared table-schema lock
    exclusive stable-row lock
    deterministic exclusive old unique-key locks
    physical RawStore tombstone mutation
```

The locks have transaction duration. RawStore savepoint rollback undoes physical versions and index
entries, but it intentionally does not release logical row or key locks. This prevents a rolled-back
statement from exposing a key or row conflict before the surrounding transaction completes.

The inherited physical page/record `ConglomerateController.lockRow(long, int, ...)` callback remains
an explicit no-op for this access method. Derby's inherited backing B-tree invokes that callback while
maintaining constraint indexes, but a physical page/record pair is not a stable MVCC identity.
Semantic row locking occurs through stable `MvccRowLocation` and statement-time mutation paths only.

## Unique-key conflicts

Native unique checks acquire exclusive key locks before reading the latest committed conflict
horizon. A concurrent writer for the same typed SQL key waits for the earlier transaction outcome:

```text
earlier writer commits
    waiting writer rechecks authoritative MVCC state and receives 23505

earlier writer aborts
    waiting writer rechecks and may proceed
```

Nullable SQL `UNIQUE` definitions do not lock rows whose key contains `NULL`, matching duplicate-null
semantics. Primary-key and strict unique definitions lock all keys.

## Schema lifecycle conflicts

Normal DML takes a shared table-schema lock. Native unique metadata validation, ADD, and DROP take an
exclusive table-schema lock before inspecting or rewriting the control row.

This serializes:

```text
DML against ALTER TABLE ADD/DROP PRIMARY KEY or UNIQUE
DML against CREATE/DROP UNIQUE INDEX native metadata publication
concurrent native schema changes on one RawStore MVCC table
```

The SQL catalog, inherited backing index, native control-row metadata, and table data still commit or
roll back through one RawStore transaction.

## Diagnostics

Logical locks participate in `SYSCS_DIAG.LOCK_TABLE` as:

```text
DELOS_MVCC_SCHEMA[...]
DELOS_MVCC_ROW[...]
DELOS_MVCC_KEY[...]
```

The diagnostic identity exposes table/row/constraint scope without exposing user key values.

## Recovery and memory

Logical locks are process-local transaction state, not durable database state. RawStore recovery
removes uncommitted physical mutations after abrupt JVM exit; no lock replay is needed. Reopen can
immediately acquire the same logical identity in a new transaction.

The same inherited lock manager and RawStore protocol are used for `jdbc:derby:memory:` databases.
No filesystem fallback or memory-specific lock registry exists.

## Physical locking completion

The physical lock-granularity completion is now implemented. Normal table and ordered-index access
uses inherited RawStore record locking, active-writer filtering, and transactional mutation of the
published Derby B-tree generation. Logical schema, row, and typed key locks remain the semantic conflict
authority.

See `V1-RAWSTORE-MVCC-PHYSICAL-LOCKING.md` for the mutation, maintenance-publication, and recovery protocol. Short
serializable container ownership remains only around database-wide identity metadata, not normal row
mutation.

## Executable proof

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStoreLogicalLockingTest
```

Permanent architecture gate:

```text
delosMvccRawStoreLogicalLockingStaticAnalysis
```

The proof covers:

```text
stable-row writer blocking and stale-snapshot SQLState 40001
commit and abort release boundaries
unique-key commit and rollback outcomes
key locks retained after savepoint rollback
shared DML versus exclusive unique-DDL schema locks
lock visibility through SYSCS_DIAG.LOCK_TABLE
abrupt JVM exit and clean reopen
jdbc:derby:memory:
```

Savepoint rollback retains these logical locks until transaction completion. The complementary row-level physical protocol is recorded in `V1-RAWSTORE-MVCC-PHYSICAL-LOCKING.md`.

## Vacuum maintenance conflicts

Transactional RawStore MVCC vacuum uses the existing exclusive table-schema logical lock. DML holds the
shared form until transaction completion, so chain relinking and physical purge cannot overlap a table
writer. The vacuum then takes the table-scoped physical maintenance boundary before reading or removing
RawStore records. Snapshot readers remain semantically protected by retained-snapshot leases; they hold
the physical read side only while materializing records.

See `V1-RAWSTORE-MVCC-VACUUM.md`.
