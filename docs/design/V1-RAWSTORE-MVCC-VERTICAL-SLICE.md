# V1 RawStore-backed MVCC vertical-slice design

## Status

```text
ACCEPTED DESIGN PROOF
```

This proof authorizes the first isolated RawStore-backed MVCC format slice. It does not authorize
production SQL routing, removal of the Phase 8 persistence system, or a final v1 binary format.

## Purpose

The first slice must prove that one MVCC table can use Derby RawStore as its only physical and
transactional authority for:

```text
CREATE
INSERT
point read
COMMIT
ROLLBACK
reopen
process-halt recovery
memory database
```

The proof must not create RawStore lifecycle shells around external MVCC files. The table data itself
must reside in normal RawStore containers and pages.

## Source constraints

The inherited RawStore already provides the required primitives:

- `Transaction.addContainer()` creates a transaction-owned container;
- `Transaction.dropContainer()` marks and removes a container through transaction commit;
- `ContainerHandle.addPage()` and page traversal own page allocation and latching;
- `Page.insertAtSlot()` performs logged record insertion;
- `Page.updateFieldAtSlot()` performs logged field updates;
- RawStore rollback and savepoint rollback undo logged page operations;
- RawStore recovery redoes committed operations and undoes incomplete transactions.

The heap access method demonstrates the correct ownership pattern: create a container through the raw
transaction, open its first page, and store a control row in slot zero.

## Accepted physical decision

### Existing RawStore slotted pages

The vertical slice uses the existing RawStore page and record format.

It does not add:

```text
a new Delos page format
a page-volume adapter
a private page cache
a custom WAL file
a custom recovery pass
a general storage backend abstraction
```

MVCC records are ordinary RawStore rows interpreted by the MVCC access method.

### Two transactional containers

The first slice uses two containers:

```text
MVCC table
    +-- metadata and row-directory container
    +-- version container
```

The metadata container is the primary conglomerate container. Its container identifier remains the
access-manager conglomerate identifier.

The persisted conglomerate descriptor contains only stable metadata:

```text
format version
metadata container identifier
version container identifier
column format metadata
```

It contains no path, database directory, channel, page-volume object, runtime registry key, or
process-local handle.

### Metadata and row-directory container

The first page contains:

```text
slot 0: immutable conglomerate control row
slot 1: allocator row
slot 2+: stable-row directory entries
```

The allocator row carries transactionally updated logical counters:

```text
next MvccRowId
next MvccVersionId
```

A directory entry maps:

```text
MvccRowId -> MvccVersionId
```

The accepted vertical slice deliberately performs a linear directory scan for point lookup. This is
not the final performance design. It proves ownership and correctness without prematurely choosing a
B-tree or persistent physical hint.

### Version container

The first page contains a format marker in slot zero. Remaining records are version rows.

The minimum semantic fields are:

```text
record kind
record format version
MvccRowId
MvccVersionId
creating MvccTransactionId
begin MvccCommitSequence
end MvccCommitSequence
previous MvccVersionId
flags
row payload
```

The exact stored-column layout and visibility sentinel values remain provisional. DP-4 freezes the
logical identity and chain-link semantics, not the final encoded field layout. The accepted
requirements are:

- row and version identity are logical and format-versioned;
- a physical `RecordHandle` is not the durable identity;
- the payload is stored through RawStore row encoding and overflow facilities;
- no external overflow sidecar is permitted.

## RecordHandle rule

A `RecordHandle` may be retained only as an ephemeral transaction-local locator while the physical
record is known not to have moved.

The vertical slice may retain the inserted version handle in the transaction context so precommit can
stamp its begin commit sequence efficiently.

It must not persist that handle as the authoritative row or version identity.

If an ephemeral handle is unavailable or invalid, lookup falls back to the logical `MvccVersionId`.
DP-4 accepts optional persistent hints only when they use a RawStore page/record identifier, are
validated against the stored logical IDs, and are never required for correctness.

## CREATE

`MvccConglomerate.create()` uses the caller's RawStore transaction.

The transaction performs:

```text
add metadata container
add version container
insert metadata control row
insert allocator row
insert version-container format marker
persist the conglomerate descriptor through the normal access-manager path
```

All containers are normal logged containers. The slice must not use `MODE_UNLOGGED` or
`MODE_CREATE_UNLOGGED`.

A rollback removes both containers and the catalog/conglomerate state through ordinary RawStore undo.
A successful commit makes both containers visible together.

## INSERT

Statement execution performs the physical mutations immediately.

```text
1. allocate MvccRowId and MvccVersionId from the allocator row
2. insert an uncommitted version row in the version container
3. insert the row-directory entry in the metadata container
4. retain the version RecordHandle only in transaction-local state
```

The version begins with:

```text
creating transaction = current MvccTransactionId
begin sequence       = uncommitted
end sequence         = current/infinite
previous version     = none
```

The page inserts use normal logged RawStore operations. The prototype should request
`INSERT_UNDO_WITH_PURGE` so rollback removes aborted inserted records rather than leaving physical
deleted-row residue. Payload insertion may also use RawStore overflow support.

No custom `Loggable` operation is required for this slice.

## Point read

A point read is deliberately simple:

```text
scan metadata rows for MvccRowId
obtain authoritative MvccVersionId
scan version rows for MvccVersionId
apply MVCC visibility
return decoded payload
```

Visibility rules are inherited from DP-1:

- the creating transaction sees its own uncommitted version;
- another transaction never sees a version whose begin sequence is uncommitted;
- a committed version is visible only when its begin sequence is within the snapshot;
- the vertical slice has no update/delete chain yet, so the end sequence remains current.

The linear scans are an intentional proof mechanism, not an accepted production cost model.

## COMMIT

The transaction-lifecycle seam defined later from DP-2 supplies the precommit phase.

Under the DP-1 commit-publication boundary:

```text
reserve commit sequence
stamp the inserted version rows through their ephemeral handles
update the RawStore-owned committed high-water in the user transaction
commit the RawStore transaction
publish the in-memory high-water
```

The stamp is a normal logged RawStore field update. If any stamp fails, the transaction aborts and
RawStore undo removes the inserted version and directory rows and restores allocator state.

No MVCC commit marker, outcome file, publication WAL, or second commit decision is written.

## ROLLBACK and savepoints

Normal abort and rollback-to-savepoint use RawStore first.

After RawStore successfully rolls back physical operations, the MVCC transaction context trims its
ephemeral handles and semantic lists according to DP-2.

The vertical slice does not implement a second physical undo mechanism.

## Reopen and crash recovery

Reopen loads the persisted conglomerate control row and opens its RawStore container identifiers.

There is no MVCC-specific recovery pass.

The required process-halt matrix is:

| Halt point | Required recovered state |
| --- | --- |
| after first container creation | table absent unless RawStore commit exists |
| after both containers, before control rows complete | table absent unless commit exists |
| after version insert, before directory insert | row absent |
| after directory insert, before commit-sequence stamp | row absent |
| after stamp, before RawStore commit record | row absent |
| after RawStore commit record, before in-memory publication | row present and visible after reopen |

The RawStore commit record is the only durable decision.

## Memory database

The slice uses only:

```text
Transaction
ContainerHandle
Page
RecordHandle
RawStore-owned storable values
```

It does not inspect a database path or select a separate MVCC backend.

Therefore the same implementation path runs over Derby's inherited memory database storage when the
provider receives database-owned RawStore context.

Required memory proof:

```text
create jdbc:derby:memory database
create one RawStore-backed MVCC table
insert and commit
point read from a second connection
rollback another insert
close/drop database according to inherited lifecycle
prove no external MVCC files were created
```

Process-exit recovery is not a memory-database promise.

## Locking and latching

RawStore page latches protect physical page mutation.

MVCC remains responsible for logical visibility and write-conflict semantics. The isolated vertical
slice may use conservative container/record locking while proving the physical path, but that lock
granularity is not a final v1 decision.

The proof must not infer that heap locking becomes MVCC visibility.

## Custom logging decision

For this slice:

```text
container create/drop: existing RawStore operations
record insert:         existing RawStore page action
allocator update:      existing RawStore field update
commit stamp:          existing RawStore field update
rollback/recovery:     existing RawStore undo/redo
custom Loggable:       none
```

A future custom operation requires a separate proof that the transition cannot be represented safely
through existing RawStore page/container operations.

## Explicitly out of scope

The accepted vertical slice does not yet implement:

```text
UPDATE
DELETE
historical version chains
secondary indexes
unique constraints
ordered-index page format
vacuum or purge
page relocation/compression
optimized row-directory lookup
XA
nested update transactions
final binary format
production SQL routing
removal of Phase 8 persistence
```

## Implementation gate

Stage 3 production code may implement only this complete slice after Stage 2 supplies the accepted
neutral boot and transaction lifecycle seams.

The implementation must be isolated behind a new format/test path and prove the complete capability
before any existing production table is migrated.

It may not dual-write in production. Differential comparison with the Phase 8 implementation belongs
only in tests.

## Exit decision

DP-3 is accepted because the existing RawStore slotted-page and container operations can provide the
required physical logging, undo, recovery, overflow, file storage, and memory storage without adding
another persistence abstraction.

DP-4 is accepted in `V1-MVCC-STABLE-ROW-AND-VERSION-IDENTITY.md`. DP-1 through DP-4 authorize
RawStore/MVCC implementation. Lucene-specific DP-5 through DP-8 remain mandatory before Lucene work.
