# V1 MVCC stable row and version identity

## Status

```text
ACCEPTED DESIGN PROOF
```

This proof freezes the logical identity and version-link rules for the first RawStore-backed MVCC
format. It does not freeze Java class names, the final encoded column layout, or the final optimized
row/version directory implementation.

## Purpose

Physical RawStore locations are useful accelerators, but they are not MVCC identity. The design must
remain correct when RawStore changes slot positions, maintenance moves a record, vacuum purges a
record, a page is deallocated and reused, or recovery reconstructs the committed page state.

The accepted authority is:

```text
MVCC table incarnation
    + MvccRowId       -> logical row identity
    + MvccVersionId   -> logical version identity
```

A physical record locator may be absent, stale, or replaced without changing either identity.

## Source constraints

The inherited RawStore `RecordHandle` represents one record by container, page, and page-local record
identifier. RawStore guarantees that the handle for the same physical record does not change while
that record exists, although its slot-number hint may change.

That guarantee does not make the handle a durable MVCC identity:

- a purged record no longer exists;
- a deallocated page can later be reused;
- an explicit access-method rewrite can copy one logical version into a new physical record;
- a serialized slot number is only a hint and may be stale;
- logical undo already allows a higher-level row operation to reset its physical record handle after
  movement.

Therefore, a physical locator must always be validated against the logical row/version header before
it is trusted.

## Identity scope

### Table incarnation

`MvccRowId` and `MvccVersionId` are scoped to one persisted MVCC conglomerate incarnation.

The complete database-level identities are conceptually:

```text
MvccRowIdentity     = table incarnation + MvccRowId
MvccVersionIdentity = table incarnation + MvccVersionId
```

The table incarnation is the persisted conglomerate/catalog identity, not a JVM object, path, page,
or process-local runtime key.

A dropped and recreated table has a new incarnation and may restart its logical counters. A rebuild
which preserves the same table incarnation must preserve the existing logical identities and
allocator high-water marks.

### Numeric domain

For both identifiers:

```text
0                 = NONE / absent
1..Long.MAX_VALUE = valid identity values
```

Allocation must fail before mutation when the positive signed-long domain is exhausted. It must not
wrap, become negative, or reuse a committed identity.

An identifier allocated only inside a transaction that fully rolls back never becomes a durable
identity and may be allocated again after RawStore restores the allocator row. Once the allocating
transaction commits, that identifier is never reused in the same table incarnation.

### Truncate and rebuild

An operation that keeps the same table incarnation must not reset row or version counters.

It must either:

```text
preserve the current allocator high-water
or
advance it beyond every retained/copied identity
```

`TRUNCATE` may remove every row and version, but new rows still receive identifiers above the prior
committed high-water. A provider-preserving rebuild copies the logical IDs and preserves or advances
the allocator high-water.

## Stable row identity

`MvccRowId` identifies one logical row throughout its history.

```text
INSERT       -> allocate one MvccRowId
UPDATE       -> keep the same MvccRowId
DELETE       -> write a tombstone for the same MvccRowId
VACUUM       -> may remove unreachable versions or the complete row history
new INSERT   -> never reuse a committed MvccRowId in the same incarnation
```

The inherited `MvccRowLocation` continues to compare and hash by logical row ID only. Any embedded
physical values remain hints and do not participate in equality or ordering.

The current Phase 8 page/slot hint encoding is not the accepted RawStore hint format. RawStore slot
numbers are unstable. If the converged format retains a physical hint, it must use a page and
page-local record identifier and be versioned separately from logical identity.

## Stable version identity

Every physical MVCC version record has one `MvccVersionId`.

A new committed or active version receives a new version ID. Updating, deleting, copying during a
maintenance rewrite, reopening, or recovery does not change that ID.

The following invariants are accepted:

```text
versionId != NONE
rowId != NONE
previousVersionId == NONE, or previousVersionId < versionId
predecessor and successor belong to the same MvccRowId
one retained version ID identifies at most one committed physical record
```

Strictly increasing predecessor links provide a simple cycle check. Gaps are legal.

A transaction may temporarily contain uncommitted competing successor versions, but write-conflict
validation permits at most one committed successor for a retained predecessor. After commit/recovery,
the retained committed chain cannot branch.

## Previous-version links

The durable version-chain edge is:

```text
previousVersionId: MvccVersionId
```

It is not a `RecordHandle`, page number, slot, or serialized Java object.

`MvccVersionId.NONE` marks the retained chain root.

A directory, cache, or locator index may accelerate:

```text
MvccVersionId -> physical record hint
```

but chain traversal remains correct when that accelerator is missing or stale.

## Row-directory authority

The row directory stores:

```text
MvccRowId -> head MvccVersionId
```

The head version ID is logical. The directory is transactionally maintained, but it is a rebuildable
access structure rather than the source of row/version identity.

For the first vertical slice, a linear scan of version records remains the correctness fallback.
Later versions may add a RawStore-backed index keyed by `MvccVersionId` without changing the identity
model.

## Optional physical hints

A persistent hint is permitted only as an optimization.

The conceptual shape is:

```text
expected container identity
page number
page-local record identifier
```

A slot number is not persisted as authority. A Java `RecordHandle` object is not serialized into the
MVCC format.

Before using a hint, the access method must verify all of the following:

```text
container matches the expected version container
page exists and can be latched
record still exists
record kind and format are valid
stored MvccRowId equals the expected row ID
stored MvccVersionId equals the expected version ID
```

If any check fails:

```text
ignore the hint
find the version by logical identity
optionally repair the hint transactionally or lazily
```

A stale hint must never resolve to a different row/version merely because a page number and record ID
were reused.

The first production vertical slice does not require persistent hints. It may use transaction-local
`RecordHandle`s for records inserted or updated by the current transaction.

## Page movement and compression

RawStore may reorder slots without changing the record handle for the same physical record. Such
slot changes are invisible to MVCC identity.

If DelosDB later performs an explicit record-moving rewrite, it must execute under one RawStore
transaction and an appropriate maintenance/concurrency boundary:

```text
insert replacement physical record with the same row/version IDs
validate the replacement
update any directory/locator hint
purge the old physical record
commit once
```

A crash yields either the old committed location or the new committed location through normal
RawStore undo/redo. It must not yield two committed authoritative records for one version ID.

Logical identity does not change during the rewrite.

## Vacuum and chain repair

Vacuum may reclaim a version only when the visibility/retention proof allows it.

Before purging a retained chain member, vacuum updates the logical link in the same RawStore
transaction:

```text
interior version removed:
    successor.previousVersionId = removed.previousVersionId

oldest retained prefix removed:
    oldest surviving version.previousVersionId = NONE

complete row history removed:
    remove the row-directory entry and every retained version
```

The link update is logged and committed with the purge. No committed state may contain a dangling
predecessor reference.

Removing a complete row history does not make its committed `MvccRowId` reusable in the same table
incarnation.

## Directory rebuild

For v1, rebuild may take an exclusive table maintenance boundary. Online rebuild is not required by
this proof.

The authoritative rebuild procedure is:

```text
1. scan all retained version records
2. reject duplicate MvccVersionId values
3. validate positive IDs, record formats, and row ownership
4. validate every predecessor edge:
       same row
       lower version ID
       existing retained record or NONE
5. require at most one retained successor per version after recovery
6. derive exactly one head version for every retained row
7. build a replacement row directory transactionally
8. populate optional hints from the current physical records
9. set allocator high-waters to max(old high-water, max retained ID + 1)
10. commit the replacement before retiring the old directory
```

The rebuild must fail closed on:

```text
duplicate version identity
cycle
cross-row predecessor
missing predecessor
multiple committed heads
invalid format
allocator overflow
```

It must not choose an arbitrary physical record as the winner.

## Recovery

RawStore recovery restores committed version records, logical-link updates, directory mutations, and
allocator rows.

After recovery:

- a valid logical chain remains authoritative even if every physical hint is absent;
- an invalid hint is ignored and may be repaired;
- duplicate or broken logical identity is reported as corruption;
- no MVCC-specific filesystem recovery pass is allowed.

A crash during directory rebuild or record movement leaves either the old committed structure or the
new committed structure according to the RawStore commit record.

## Memory databases

The identity model contains no filesystem or process-lifetime value. The same table incarnation,
logical IDs, chain links, hint validation, and rebuild rules apply through the inherited memory
storage lifecycle.

Process exit discards the memory database, but it does not change the semantics of an identity while
the database incarnation exists.

## Required proof tests

The Stage 3/4 implementation must add permanent tests for:

```text
row ID preserved across update, delete, reopen, and rebuild
version IDs increase and remain stable across reopen
previous-version traversal uses logical IDs only
slot reorder does not affect lookup
stale hint falls back to logical lookup
page/record reuse cannot alias another version
record-moving rewrite preserves identity across crash points
vacuum relinks an interior chain member atomically
vacuumed row IDs are not reused
truncate preserves allocator high-water
row-directory rebuild restores identical logical heads
rebuild works with all hints removed
rebuild rejects duplicate IDs, cycles, cross-row links, and missing predecessors
file and memory databases follow the same rules
```

## Accepted conclusion

```text
MvccRowId and MvccVersionId are the only durable row/version identities.
previousVersionId is the only durable chain edge.
physical RawStore locations are optional validated hints.
```

Page movement, purge, compression, recovery, and slot/page reuse may invalidate an optimization. They
cannot change or alias logical MVCC identity.

## Next proof

DP-5 defines the Lucene/RawStore watermark crash-state matrix. No production convergence code is
authorized yet.
