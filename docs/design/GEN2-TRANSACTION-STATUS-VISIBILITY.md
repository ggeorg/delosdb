# Gen2 transaction-status visibility

## Purpose

Fresh Gen2 CURRENT rows are inserted with `UNCOMMITTED_SEQUENCE`. The transaction-status
visibility path replaces commit-time restamping of every freshly inserted row with one durable
transaction-level mapping from creator transaction ID to commit sequence.

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

Transaction-status rows are append-only and transaction IDs are database-wide unique. Their
physical insert therefore uses the normal MVCC record-level RawStore locking policy rather than a
transaction-duration exclusive lock on the entire database-metadata container. Page latches still
serialize mutation of one physical page, while independent transactions may retain locks on their
own inserted status records concurrently.

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

## Deferred lifecycle work

This increment does not yet retire durable status rows or bound the in-memory committed-status map.
A status may still be needed by any untouched CURRENT row created by that transaction. Promotion
therefore still requires a lifecycle design which can prove when no persistent row references a
transaction status, then compact or retire that status without violating old snapshots, recovery,
or table maintenance.

A dedicated status container is also deferred. If the remaining periodic interaction with the
shared database-metadata allocator proves material, status storage can be separated without
changing the transaction-level visibility contract above.
