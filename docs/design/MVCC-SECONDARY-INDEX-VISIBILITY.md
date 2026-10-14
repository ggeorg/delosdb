# MVCC secondary-index visibility

Status: design, accepted 2026-10-14. Stage 1 is the next implementation slice.

## Problem

SQL indexes on `delos_mvcc` tables (primary keys, unique constraints, foreign keys and `CREATE INDEX`)
are inherited Derby B-trees (conglomerate factory 1). Their entries carry a row location but no
version. Only the base-row fetch (`MvccRawStoreConglomerateController.fetch`) applies snapshot
visibility. A JDBC reproduction with a REPEATABLE READ reader shows three failures:

1. **Index-only plans see later inserts.** `count(*)`, `max(id)`, and selects of indexed columns
   count B-tree entries that were committed after the reader's snapshot. DEL-ISO-001 and
   DEL-FK-004 failed for this reason.
2. **Index paths lose later deletes.** A DELETE marks the B-tree entry deleted (`BTreeScan.delete`
   → `deleteAtSlot`), and every scan skips marked entries unconditionally (`BTreeForwardScan`,
   `BTreeScan`, `BTreeMaxScan`). A reader whose snapshot still contains the row never reaches the
   base row, even when the plan fetches base rows.
3. **Unique re-insert rewrites a retained entry.** `BTreeController` reuses a marked-deleted entry
   with the same unique key in place and rewrites its row location. An older snapshot would lose
   the deleted row again.

## Rejected: forbid covering plans on MVCC tables

Treating no MVCC index as covering (`FromBaseTable.isCoveringIndex`) fixes failure 1 and passes all
isolation specifications. It does not fix failures 2 or 3, and it removes index-only plans. The
matched probe (20,000 rows; median of 101 runs; three alternating JVMs per side) measured:

| Query | Before | Covering forbidden |
|---|---|---|
| `count(*)` | 0.7–1.0 ms | 33–37 ms |
| `count(*)` over a 10% index range | 0.2 ms | 1.4–1.7 ms |
| indexed-column select, 10 keys | 41–48 µs | 155–162 µs |
| `max(id)` | 5 µs | 17.6–19.5 ms (full scan) |

That contradicts the MVCC performance goal. It would also become dead code once index entries
are checked for visibility, which failures 2 and 3 need anyway.

## Design: snapshot-checked index entries, retained until vacuum

PostgreSQL does the same thing: index entries are unversioned, a reader checks each entry against
the heap, and vacuum removes an entry only after the last version it points to is reclaimed.

### Stage 1: visibility-checked B-tree scans

- A store contract in derby-store-api lets a B-tree scan ask the base conglomerate whether the row
  behind an entry is visible at the reader's snapshot. Heap tables do not provide it, so their
  scans are unchanged.
- Snapshot reads on an index of an MVCC table evaluate every entry, deleted-marked or not, through
  that check, reusing the base controller's snapshot fetch. The SQL layer marks a scan as a
  snapshot read with an explicit open-mode flag, set only by query result sets that do not scan
  for update. Constraint checks (`GenericRIChecker`, `ReferencedKeyRIChecker`) open their index
  scans at isolation levels that queries also use, so isolation cannot tell them apart. They must
  see the latest committed state, as must DML, which acts on the locked current row
  (DEL-TRAVERSAL-001). Without the flag a scan therefore keeps today's latest-committed behaviour,
  and a missing flag can never weaken a constraint check.
- The check runs after the B-tree has released its leaf latch, on the rows a fetch returns, so no
  base-table page is latched while a leaf latch is held. With the flag set, the B-tree returns
  deleted-marked entries and always includes the row location.
- `BTreeMaxScan` applies the same check while it walks back from the right edge, so `max()` stays
  an edge lookup.
- Opening such a scan fixes the transaction snapshot, so an index read that finds no entry still
  fixes the REPEATABLE READ snapshot.

This fixes failure 1 and keeps covering plans. Failure 2 is fixed for entries that have not been
purged yet.

### Stage 2: retain deleted entries until the vacuum horizon

- Post-commit reclaim (`BTreePostCommit`) and purge on page split must not physically remove a
  deleted-marked entry of an MVCC-table index while a snapshot older than the delete is still
  active (`MvccRawStoreRuntime.vacuumHorizon`).
- MVCC vacuum removes the entry when it reclaims the last version the entry points to.

### Stage 3: unique indexes keep both entries

- A unique insert must not reuse a deleted-marked entry whose row is still visible to an active
  snapshot. Both entries have to coexist, so the physical comparison of MVCC unique indexes
  includes the row location. The duplicate check then ignores entries whose row is dead at the
  latest committed state.
- Derby's deferrable unique constraints already keep physical duplicates and check them
  separately. Stage 3 reuses that mechanism and does not add a second uniqueness path.

## Evidence for each stage

- Isolation specifications for the reproduced cases: index-only count and max after a commit
  behind the snapshot, delete behind the snapshot through an index, re-insert of a deleted unique
  key, and an empty first read.
- The heap/MVCC differential fuzz corpus and `delosConcurrencyTests`.
- Recovery, for stages 2 and 3: retained entries and their vacuum must survive a crash.
- The matched covering probe and the insert probe. Stage 1 adds one directory check for each
  index entry; it must stay far below the costs in the table above.
