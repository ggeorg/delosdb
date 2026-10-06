# Gen2 transaction-status visibility

## Purpose

Fresh Gen2 CURRENT rows are inserted with `UNCOMMITTED_SEQUENCE`. The transaction-status
visibility path replaces commit-time restamping of every freshly inserted row with durable
transaction-level commit state. For new status rows the same commit mapping is represented once
per Gen2 table whose untouched CURRENT rows may still depend on it; those dependency rows are the
reachability proof used for reclamation.

The feature remains experimental. This document records the invariants required before it can be
considered for promotion.

## Durability and publication invariants

- RawStore remains the only WAL, undo, crash-recovery, and physical persistence authority.
- A transaction-status row is inserted by the same parent RawStore transaction as the user rows it
  makes visible. A status must never commit independently of those rows.
- The in-memory status map is published only after RawStore commit returns successfully.
- Recovery reloads only status rows which survived RawStore recovery.
- A snapshot may treat `UNCOMMITTED_SEQUENCE` as committed only when the creator transaction has a
  positive durable status sequence at or below that snapshot.

## Physical locking

Transaction-status dependency rows are append-only during user commit and transaction IDs are
database-wide unique. Their physical insert therefore uses the normal MVCC record-level RawStore
locking policy rather than a transaction-duration exclusive lock on the entire database-metadata
container. Page latches still serialize mutation of one physical page, while independent
transactions may retain locks on their own inserted status records concurrently. Maintenance may
later purge committed dependency rows after the owning table has been made status-independent.
Reclamation transactions take one database-scoped MVCC logical reclamation lock until RawStore
commit so two independent table reclaimers cannot both observe an uncommitted "last dependency"
deletion. The metadata rows themselves continue to use record-level RawStore physical locking.
Normal status staging does not take the reclamation lock, so maintenance does not restore the old
metadata-container commit serialization.

Database identity allocation and metadata-control-row updates keep their existing container-level
serialization. This change does not weaken those authorities.

## Concurrent-commit qualification

The focused qualification diagnostic begins only after a transaction has successfully staged its
status row and ends when RawStore reports the commit complete. A maximum concurrent value greater
than one therefore proves that multiple status-backed transactions passed status staging and
remained in the RawStore commit pipeline at the same time. It does not measure throughput and it is
not a replacement for the later F08 performance matrix.

The diagnostic is disabled by default and exists only to prevent another expensive benchmark from
running when its concurrency prerequisite has not actually been demonstrated.

## Transition into history

A fresh CURRENT row may remain physically stamped with `UNCOMMITTED_SEQUENCE` after its creating
transaction commits. When a later Gen2-C1/C3 mutation archives that row, the writer resolves the
creator's committed sequence from transaction-status state and writes that positive sequence into
the history row. The new CURRENT mutation then follows the existing commit-stamping path.

This makes the archived predecessor self-contained. After such a mutation and reopen, visibility of
that predecessor/current chain does not depend on enabling transaction-status visibility.

## Lifecycle materialization

RawStore MVCC maintenance now owns the first monotonic lifecycle transition for Gen2 inline
CURRENT rows. While holding the existing table logical exclusion and maintenance boundary, it scans
CURRENT records whose begin sequence is still `UNCOMMITTED_SEQUENCE`, resolves the creator through
the durable transaction-status state, and writes the positive commit sequence directly into the
CURRENT header in the same RawStore maintenance transaction.

The ordering is deliberately one-way:

1. durable transaction status already exists;
2. maintenance materializes the commit sequence into CURRENT;
3. RawStore commits that CURRENT rewrite;
4. only a later reclamation phase may consider retiring the durable status.

A crash or abort before step 3 leaves the original status-backed CURRENT row intact. A committed
materialization makes that row self-contained and therefore readable after reopen even when
transaction-status visibility is disabled. Gen1 directory/version vacuum remains unchanged; Gen2
inline CURRENT materialization uses a separate narrow path rather than forcing inline rows through
the legacy chain planner.

## Dependency-based reclamation

The committing transaction already owns an exact, restart-safe description of status reachability:
the set of Gen2 tables represented by its fresh inline pending versions. New transaction-status
records persist one dependency row per distinct table, carrying the same transaction ID and commit
sequence plus that table's RawStore container identity. No database-wide table catalog, TTL,
reference counter, or second persistence authority is required.

Reclamation is table-local but globally sound:

1. maintenance obtains the existing exclusive logical table boundary;
2. every status-backed CURRENT in that table is materialized to a positive commit sequence;
3. if no unresolved CURRENT remains, the same RawStore transaction purges status dependency rows
   for that table;
4. if another table still has a dependency row for the transaction, the durable status remains;
5. only when the last durable dependency row disappears is the transaction status globally
   unreachable;
6. after RawStore commit succeeds, the corresponding in-memory cache entry may be evicted.

Explicit purge/compress uses the same ordering. Table DROP may remove that table's dependency rows
in the same RawStore transaction as the physical drop because a committed drop leaves no persistent
CURRENT state in that table. Abort/undo restores both sides together.

The pre-dependency three-field status format remains readable but is conservatively retained: it
does not identify its dependent tables, so maintenance never guesses that such a row is reclaimable.
This is finite upgrade residue; all newly staged status state uses dependency rows and is
reclaimable by the lifecycle above.

The cache remains an acceleration structure rather than a persistence authority. This increment
evicts a cached status only after the final dependency deletion is durably committed. A later cache
policy may additionally bound entries and perform durable lookup on cache miss; it must still
distinguish an uncached committed status from an absent/uncommitted transaction rather than treating
a cache miss as a visibility result.

A dedicated status container is also deferred. If the remaining periodic interaction with the
shared database-metadata allocator proves material, status storage can be separated without
changing the transaction-level visibility contract above.
