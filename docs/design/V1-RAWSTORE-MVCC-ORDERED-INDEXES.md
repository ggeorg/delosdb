# V1 RawStore-backed MVCC ordered indexes

## Status

```text
IMPLEMENTED BEHIND THE RAWSTORE MVCC OPT-IN
```

The opt-in RawStore-backed `delos_mvcc` format now persists its version-aware ordered candidate
index in a third ordinary RawStore container. The index has no path, page volume, independent WAL,
checkpoint, recovery pass, cache, or commit decision.

## Ownership and authority

The table owns:

```text
metadata and stable-row directory container
version container
ordered-index container
```

All three containers are created, mutated, rolled back, committed, recovered, dropped, and backed by
file or memory storage through the same inherited RawStore transaction.

The ordered index is not row authority. Its result is only a set of stable `MvccRowId` candidates.
Every candidate is reread through the authoritative MVCC version chain, and the complete SQL
qualifier set is applied again before a row is returned.

## Physical ordered layout

Each non-tombstone version contributes one entry per table column:

```text
column identifier
typed Derby key
MvccRowId
MvccVersionId
creating MvccTransactionId
begin MvccCommitSequence
end MvccCommitSequence
```

Entries are physically sorted in the RawStore container by:

```text
column identifier
SQL typed key order
MvccRowId
MvccVersionId
```

The first page retains a control row. Remaining RawStore records are rewritten in sorted order inside
the user transaction. Purge and insert operations are normal logged RawStore page mutations, so a
statement failure, savepoint rollback, transaction rollback, or crash before commit restores the
previous index state.

This first RawStore format deliberately ports the existing immutable sorted-rewrite behavior rather
than introducing another storage abstraction. Incremental page splits and a final cost model remain
later performance work; the persisted state is already a real physically ordered RawStore index, not
an append journal sorted only at query time.

## Mutation and visibility

INSERT and UPDATE create index entries during statement execution. DELETE appends a tombstone base
version and creates no new key entries.

Before the one RawStore commit record, the access-method participant stamps:

```text
new base-version begin sequence
new ordered-index entry begin sequences
predecessor base-version end sequence
predecessor ordered-index entry end sequences
database committed high-water
```

The index uses the same visibility rule as the base version:

```text
uncommitted entry:
    visible only to its creating MvccTransactionId

committed entry:
    beginSequence <= snapshotSequence < endSequence
```

Historical entries remain present so an older snapshot can find the old key while a newer snapshot
finds the replacement key or observes a committed delete.

## Equality and range lookup

Only safe single-column equality or range qualifier shapes use this index. Physical traversal skips
entries below the requested column or lower bound and stops after the target column or upper bound.
SQL typed comparison uses Derby `StoreDataValue` and `StoreTypeUtil.compare`; numeric order is not
lexical string order.

Candidate row IDs are de-duplicated in physical key order. The scan then performs:

```text
ordered-index candidate MvccRowId
    -> authoritative MVCC version-chain lookup
    -> snapshot visibility
    -> full RowUtil qualifier evaluation
    -> returned row
```

Unsupported qualifier shapes fall back to the full stable-row directory scan.

## Compatibility and lazy rebuild

Control rows created before this milestone do not contain the optional ordered-index container field.
They remain readable and use the full scan path.

The first later INSERT, UPDATE, or DELETE transactionally:

```text
creates the ordered-index RawStore container
scans all retained non-tombstone versions
builds physically sorted version-aware entries
rewrites the table control row with the new container ID
performs the requested mutation
```

Rollback removes the new container and restores the old control row. No boot-time filesystem
migration and no dual-write path exist.

## Recovery and memory proof

The executable proof covers:

```text
SQL typed physical ordering across multiple RawStore pages
equality and bounded range lookup
transaction-local INSERT/UPDATE visibility
committed UPDATE and DELETE visibility
historical snapshots
savepoint rollback
clean reopen
halt after stamping before RawStore commit
halt after RawStore commit before in-memory publication
pre-index shorter control-row compatibility
transactional lazy rebuild
jdbc:derby:memory:
```

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStoreOrderedIndexTest
```

Permanent architecture gate:

```text
delosMvccRawStoreOrderedIndexStaticAnalysis
```

## Current limits

This milestone does not yet provide:

```text
SQL CREATE INDEX / DROP INDEX lifecycle for the RawStore format
unique constraints
incremental ordered-page split/merge optimization
vacuum or physical removal of obsolete historical entries
final optimizer costing
final fine-grained locking
```

Those capabilities must build on this version-aware RawStore format. They must not restore an
external index file or independent durability authority.
