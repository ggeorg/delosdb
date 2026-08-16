# RawStore-backed MVCC UPDATE and DELETE

Status: **IMPLEMENTED**

## Scope

This design adds UPDATE and DELETE to tables using the RawStore-backed
`delos_mvcc` format.

The implementation preserves one stable logical row identity and appends a new logical version for
every mutation:

```text
UPDATE
    -> append replacement version
    -> previousVersionId = prior directory head
    -> move stable-row directory head to replacement

DELETE
    -> append tombstone version
    -> previousVersionId = prior directory head
    -> move stable-row directory head to tombstone
```

All rows, directory updates, version links, tombstones, allocator changes, begin commit sequences, and
end commit sequences are ordinary logged RawStore mutations. There is no separate update log, delete
log, WAL, checkpoint, recovery pass, or commit decision.

## Physical mutation timing

UPDATE and DELETE execute while the SQL statement runs:

1. resolve the row visible to the transaction snapshot;
2. verify that the visible version is still the directory head;
3. reserve a new table-scoped `MvccVersionId`;
4. insert the replacement version or tombstone into the RawStore version container;
5. update the stable-row directory head transactionally;
6. retain only logical version identity plus a validated physical hint in the transaction context.

If a newer committed or foreign uncommitted head exists above the transaction-visible version, the
write fails with Derby serialization SQLState `40001`. A stale snapshot can never overwrite a newer
version.

## Visibility and version-chain traversal

Readers begin at the stable-row directory head and follow durable `previousVersionId` links until they
find a version visible to their transaction-wide snapshot.

This gives the required behavior:

```text
uncommitted replacement by another transaction
    -> reader follows previousVersionId and sees the older committed version

replacement committed after snapshot S
    -> snapshot S follows the chain and sees the predecessor

replacement visible to snapshot
    -> reader returns the replacement payload

tombstone visible to snapshot
    -> row is absent

tombstone committed after snapshot S
    -> snapshot S follows the chain and sees the predecessor
```

Chain traversal validates row identity, rejects missing links, and rejects cycles. Physical
`RecordHandle` values remain hints only.

## Commit protocol

The transaction context already owns one database-wide `MvccTransactionId`, one snapshot, and one
pending-version list across all participating RawStore-backed tables.

At precommit, one `MvccCommitSequence` is reserved. For every surviving pending mutation:

1. stamp the new replacement or tombstone begin sequence;
2. stamp its predecessor end commit sequence with the same value;
3. stage the database-wide committed high-water;
4. return to the one inherited RawStore commit.

Repeated updates in one transaction form a chain whose intermediate versions receive equal begin and
end sequences. They are therefore never visible as a committed state. The final replacement or
tombstone is the only version visible at the commit sequence.

## Rollback and savepoints

RawStore undo remains the only physical undo authority.

A full rollback restores:

```text
version-container inserts
directory-head changes
version-ID allocator changes
```

Rollback to savepoint first lets RawStore restore physical state. The access-method lifecycle
participant then removes pending entries whose inserted version rows no longer exist. Mutations made
before the savepoint remain eligible for the eventual single commit.

No semantic undo file or access-method-specific rollback log exists.

## Crash recovery

The existing process-halt points now cover UPDATE and DELETE:

```text
after-stamp-before-raw-commit
    -> recovery restores predecessor end sequences
    -> recovery removes replacement/tombstone and directory-head change
    -> old rows remain visible

after-raw-commit-before-publication
    -> recovery retains replacement/tombstone and predecessor end sequences
    -> database metadata reconstructs committed high-water
    -> updated rows and committed deletions are visible
```

RawStore recovery is the only recovery pass.

## File, memory, and multi-table behavior

The same implementation runs for file databases and `jdbc:derby:memory:` databases.

One transaction may UPDATE a row in one RawStore-backed MVCC table and DELETE a row in another. Both
mutations share one transaction ID, one commit sequence, one committed high-water update, and one
RawStore outcome.

## Current boundaries

UPDATE and DELETE participate in the same ordered-index, uniqueness, vacuum, logical-locking, physical
locking, and accepted mixed heap/MVCC transaction mechanisms as other RawStore-backed MVCC mutations.
MVCC XA writes and nested update transactions remain fail-closed boundaries. UPDATE and DELETE use
only the RawStore-backed MVCC path.

## Permanent evidence

Focused runtime task:

```text
:delosdb-tests:delosFunctionalTests --tests '*MvccRawStoreUpdateDeleteTest'
```

Permanent architecture gate:

```text
delosRepositoryIntegrityStaticAnalysis
```

The proof covers committed and rolled-back replacement versions, committed and rolled-back
tombstones, repeated same-transaction updates, savepoint rollback, historical snapshot traversal,
stale-writer rejection, clean reopen, both RawStore crash boundaries, multi-table atomicity, and
`jdbc:derby:memory:`.
