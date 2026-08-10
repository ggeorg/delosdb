# RawStore-backed MVCC multi-table transactions

Status: **IMPLEMENTED BEHIND THE EXISTING RAWSTORE OPT-IN**

## Scope

This slice removes the Stage 3 single-table transaction restriction for tables already using the
RawStore-backed `delos_mvcc` format. Multiple such tables can now participate in one SQL transaction
with:

```text
one RawStore transaction outcome
one MvccTransactionId
one MvccCommitSequence
one transaction-wide snapshot
one inherited RawStore commit record
```

The invariant is one `MvccTransactionId` and one `MvccCommitSequence` for all participating
RawStore-backed MVCC tables in that transaction.

It does not extend the capability to retained external-format MVCC tables, mixed heap/MVCC writes,
XA, nested update transactions, UPDATE, DELETE, indexes, or vacuum.

## Transaction-local state

`MvccRawStoreTransactionContext` is attached once to the inherited Derby access transaction. The
context owns one durable transaction identity and one pending-version list whose entries retain their
own table descriptor. It no longer binds the transaction to one table.

The first RawStore-backed MVCC write reserves one database-wide `MvccTransactionId`. Every inserted
version in every participating table receives that same identity. Precommit reserves one database-wide
`MvccCommitSequence` and stamps all surviving pending versions with that sequence before the parent
RawStore commit.

Version stamping is ordered by metadata-container segment, metadata-container ID, and logical version
ID. Physical `RecordHandle` values remain transaction-local hints and each pending entry retains the
stable table and version identity needed for logical fallback.

## Transaction-wide snapshot

The context captures the published committed high-water on first MVCC read and retains it until commit,
rollback, or transaction destruction. Reading another RawStore-backed MVCC table later in the same
transaction therefore uses the same snapshot even when another transaction commits between the two
reads.

Own uncommitted versions remain visible through their shared `MvccTransactionId`.

## Savepoints and rollback

RawStore remains the physical undo authority. After rollback to a savepoint, the lifecycle participant
checks each pending version against the RawStore table that owns that version and removes entries whose
physical rows were undone. Pending versions from other participating tables remain eligible for the
single eventual commit.

A full rollback removes all participating table mutations through the inherited RawStore transaction.
The reserved transaction identity may remain as a numeric gap; it is never reused.

## Commit and failure boundaries

For a multi-table write transaction:

```text
statement execution
    -> RawStore mutations in table A
    -> RawStore mutations in table B

precommit
    -> reserve one MvccCommitSequence
    -> stamp all pending versions in deterministic table order
    -> stage one database-wide committed high-water

commit
    -> one inherited RawStore commit record

postcommit
    -> publish the committed high-water in memory
```

The executable process-halt proof covers both boundaries:

```text
after-stamp-before-raw-commit
    -> recovery exposes neither table mutation

after-raw-commit-before-publication
    -> recovery exposes both table mutations
```

There is no per-table commit decision and no independent MVCC recovery pass.

## File and memory behavior

The exact same implementation is used by file databases and `jdbc:derby:memory:` databases. The
memory proof commits and rolls back mutations spanning two RawStore-backed MVCC tables and inspects the
same database-wide identity metadata.

## Permanent evidence

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreMultiTableTransactionTest
```

Permanent architecture gate:

```text
delosMvccRawStoreMultiTableTransactionStaticAnalysis
```

The proof covers:

```text
two-table commit with one transaction ID and one commit sequence
two-table rollback with no partial row survival
savepoint rollback of only the second-table mutation
stable transaction snapshot across two tables
halt before the RawStore commit record
halt after the RawStore commit record but before publication
clean reopen
jdbc:derby:memory:
```
