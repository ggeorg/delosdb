# RawStore MVCC Vacuum and Purge

## Status

```text
Status: IMPLEMENTED / VERIFIED
```

This design adds transactional history reclamation to
the RawStore-backed `delos_mvcc` format without introducing another durability authority.
RawStore continues to own pages, logging, undo, commit, recovery, container lifecycle, and memory
storage. MVCC owns only the visibility horizon, logical version-chain rules, and derived-index rebuild.

## Contract

Vacuum may remove a version only when no active transaction or held cursor can still require it. The
reclamation horizon is therefore the oldest retained snapshot across the database, bounded by the
currently published committed high-water.

For each logical row, vacuum retains:

```text
all uncommitted versions, if any
all committed versions newer than the oldest retained snapshot
one non-tombstone version visible at the oldest retained snapshot
```

A tombstone visible at that horizon means the entire deleted row history can be removed. When no older
snapshot is retained, the horizon is the current committed high-water and only the current live version
survives.

Committed `MvccRowId` and `MvccVersionId` values are never reused. Vacuum does not lower allocator
high-water values.

## Snapshot leases

The database runtime registers a snapshot lease while holding the same publication boundary used to
read the committed high-water. This closes the race between snapshot capture and reclamation-horizon
selection.

A transaction owns its cached snapshot lease until transaction completion. A held cursor duplicates
that lease so commit may clear transaction state without exposing history still required by the cursor.
Closing the held cursor releases its lease.

The horizon is:

```text
minimum(published committed high-water, every retained snapshot sequence)
```

The registry is process-local semantic state. After a crash there are no surviving readers, so recovery
needs no lease journal.

## Physical maintenance boundary

Normal reads use inherited RawStore record locking and may physically observe uncommitted records.
Vacuum physically removes records, so one process-local fair read/write boundary exists per table:

```text
ordinary point read or scan materialization -> read side
vacuum transaction                           -> write side
```

The write side is held until transaction completion. Vacuum first takes the transaction-duration
exclusive logical table-schema lock and then the physical maintenance boundary. Writers already hold a
shared logical table-schema lock, so vacuum cannot overlap table mutation. Readers block only while the
physical relink/purge transaction is unresolved; they do not hold the maintenance read side for the
lifetime of their snapshot.

The retained-snapshot horizon, not the physical boundary, protects historical visibility.

## Transactional chain relinking

`MvccVersionId` and `previousVersionId` remain the durable chain authority. Record handles are validated
physical hints only.

Before purging any chain member, vacuum performs transactional chain relinking in the same parent
RawStore transaction:

```text
retained successor.previousVersionId = retained predecessor.versionId
oldest retained version.previousVersionId = NONE
```

Optional predecessor page/record hints are rewritten with the logical edge. Directory head identity and
optional head hints are refreshed to the retained head.

The mutation order is permanent:

```text
validate complete logical state
plan retained and removable records
rewrite retained predecessor links
rewrite retained directory heads
purge removable version records
purge removable directory records
rebuild a private ordered-index generation
publish that generation at precommit
commit once through RawStore
```

All physical removal uses logged `purgeAtSlot` operations. Targets are grouped per page, revalidated by
kind and stable identity, and purged in descending slot order so slot movement cannot retarget a later
operation.

## Fail-closed validation

Vacuum refuses to mutate when it detects:

```text
non-positive row, version, or creator-transaction identities
invalid visibility intervals or version flags
invalid predecessor ordering
duplicate MvccVersionId values
duplicate row-directory identities
missing predecessor versions
cross-row predecessor links
cycles
one version reachable from multiple directories
orphan version records
unsupported row shapes
physical target identity changes during maintenance
```

It does not guess, silently truncate a chain, or use a physical slot as identity.

## Ordered-index publication

Physical reclamation invalidates historical ordered-index entries, so a history-removing vacuum creates a
transaction-private ordered-index generation after base-chain purge. Hint-only repair commits the logged
link/head updates without replacing unchanged index state. Precommit rebuilds a replacement from the
authoritative retained versions, rewrites the table control row, and drops the replaced generation in
the same RawStore transaction.

A rollback or savepoint rollback restores base versions, links, directories, the control-row pointer,
and container lifecycle through normal RawStore undo. Transaction-local generation state is reconciled
against actual RawStore container existence.

## Commit and recovery boundaries

The vacuum-specific crash points are:

```text
after-vacuum-before-raw-commit
    exit 93
    recovery restores pre-vacuum chains, directory rows, and published index generation

after-vacuum-raw-commit-before-publication
    exit 94
    recovery exposes the vacuumed chains and newly published index generation
```

There is no second vacuum journal or post-recovery repair pass.

## SQL entry point

The supported user path is provider-preserving in-place compression with purge enabled:

```sql
CALL SYSCS_UTIL.SYSCS_INPLACE_COMPRESS_TABLE('APP', '<TABLE>', 1, 0, 0);
```

`MvccConglomerate.purgeConglomerate()` and `compressConglomerate()` dispatch to the RawStore vacuum for
the RawStore-backed format. Offline table rebuild/defragment and end-page relocation remain outside this design.

## Memory databases

`jdbc:derby:memory:` uses the same snapshot leases, logical validation, logged row updates, purge calls,
private ordered-index generation, savepoint/rollback behavior, and one RawStore transaction outcome.
There is no filesystem fallback.

## What this slice does not claim

Vacuum does not own automatic scheduling, page relocation, end truncation, overflow-page compaction,
configurable retention windows, MVCC XA writes, or nested update transactions. Database-owned automatic
scheduling and diagnostics are documented in [`../MVCC-MAINTENANCE.md`](../MVCC-MAINTENANCE.md).

## Executable proof

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStoreVacuumTest
```

Permanent architecture gate:

```text
delosMvccRawStoreVacuumStaticAnalysis
```

The focused proof covers the oldest retained transaction snapshot, a held cursor lease across commit,
exact chain relinking, stale-hint repair without index replacement, complete deleted-row reclamation,
non-reuse of logical identities, savepoint rollback, transaction rollback, both RawStore crash boundaries, reopen, ordered-index
generation replacement, and memory-database operation.
