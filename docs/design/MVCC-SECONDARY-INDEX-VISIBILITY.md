# MVCC secondary-index visibility

Status: design accepted 2026-10-14. Stage 1 implemented 2026-10-14; stage 2 is next.

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
4. **A key-moving update leaves two entries for one row.** MVCC row locations are logical row ids
   that survive updates, so the old entry (marked deleted) and the new entry point at the same row.
   Whether the row is visible does not tell which entry the snapshot sees.

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

- `AccessMethodSnapshotIndexEntries` (derby-store-api) is implemented by the MVCC base controller
  that a B2I scan already opens for locking. Heap controllers do not implement it, so heap scans are
  unchanged.
- An entry is visible when the base row's version visible to the snapshot holds the entry's key
  (failure 4). The scan fetches that version's indexed columns through the base controller's
  ordinary snapshot `fetch` and compares them with the entry, so there is no second visibility
  path. Which base column each index column holds is recorded only in the data dictionary: the
  engine describes it through `IndexDefinitionSupport`, and `B2I` caches the answer, since a
  conglomerate's columns never change. Nothing is added to any stored format. An index the
  dictionary does not describe is not checked.
- Snapshot reads on an index of an MVCC table evaluate every entry, deleted-marked or not. The SQL
  layer marks a scan as a snapshot read with `TransactionController.OPENMODE_SNAPSHOT_READ`, set only
  by query result sets that do not scan for update (`NoPutResultSetImpl.scanOpenMode`). Constraint
  checks (`GenericRIChecker`, `ReferencedKeyRIChecker`) open their index scans at isolation levels
  that queries also use, so isolation cannot tell them apart. They must
  see the latest committed state, as must DML, which acts on the locked current row
  (DEL-TRAVERSAL-001). Without the flag a scan therefore keeps today's latest-committed behaviour,
  and a missing flag can never weaken a constraint check.
- Most entries need no base fetch. The MVCC runtime keeps per-table write state: active writers,
  a write epoch that moves whenever a writer begins or ends, and the last commit sequence that wrote
  the table. A transaction registers before it opens the table, or an index through its base table,
  for update, which precedes every index change, and ends after its commit is published or its
  rollback completes. When the scan opens with no writer active and no write committed after the
  snapshot, and the epoch has not moved since, an entry's deleted mark decides it. The scan reads the
  epoch while it holds the leaf latch: a writer moves the epoch before it changes an entry, so an
  unchanged epoch means the entry has not changed since the snapshot. This is a table-level form of
  PostgreSQL's visibility map; it reads no pages.
- Otherwise the scan copies the entry while the leaf is latched and fetches the base row after the
  B-tree has released the latch, so no base-table page is latched while a leaf latch is held.
- `BTreeMaxScan` applies the same check while it walks back from the right edge, so `max()` stays
  an edge lookup.
- Opening such a scan fixes the transaction snapshot, so an index read that finds no entry still
  fixes the REPEATABLE READ snapshot.

This fixes failures 1 and 4 and keeps covering plans. Failure 2 is fixed for entries that have not
been purged yet.

Stage 1 measurements (matched probe as above, three alternating JVMs per side): `count(*)` 0.81–1.04
ms against 0.76–1.00 ms before, the range count 188–209 µs against 211–225 µs, the indexed-column
select 45–48 µs against 40–45 µs, and `max(id)` 6 µs against 5–6 µs. A checked entry costs about
0.7 µs, one base fetch, so a table with a writer active or committed after the snapshot pays that
for every entry it reads. Narrowing the write state from tables to base pages, as PostgreSQL's
visibility map does, is the follow-up if that shows in workloads.

Remaining limits: a held cursor reopened after a commit reads at the new transaction's snapshot,
and an index whose definition the dictionary does not return (such as one on a declared temporary
table) is read as before stage 1.

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
  key, and an empty first read. Stage 1 adds DEL-ISO-005 (inserts and deletes behind the
  snapshot), DEL-ISO-006 (key-moving updates, from an older snapshot and from the writing
  transaction) and DEL-ISO-007 (an empty first read).
- The heap/MVCC differential fuzz corpus and `delosConcurrencyTests`.
- Recovery, for stages 2 and 3: retained entries and their vacuum must survive a crash.
- The matched covering probe and the insert probe. Stage 1 adds one directory check for each
  index entry; it must stay far below the costs in the table above.
