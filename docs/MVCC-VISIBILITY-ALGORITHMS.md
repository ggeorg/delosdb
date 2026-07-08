# MVCC Visibility Algorithms Audit

This is an audit artifact, not a behavior change.

## Scope

This audit makes DelosDB's MVCC visibility algorithms explicit before any deeper
concurrency, purge, or storage-path changes. It records the current authority
points for snapshot visibility, statement visibility, read-your-own-writes,
writer-borrowed reads, rollback/savepoint visibility, purge horizon protection,
history-pruned failure behavior, and transaction outcome publication.

## Guardrails

* No Java runtime behavior change.
* No MVCC visibility rule change.
* No snapshot isolation change.
* No purge horizon change.
* No transaction outcome authority change.
* No candidate-index authority change.
* No Derby heap behavior change.
* No optimizer behavior change.
* No storage format change.
* No external dependency is introduced.
* This audit does not wire any new gate into S0.

## Current algorithm inventory

### Snapshot visibility

Current authority: `MvccSnapshot` and `MvccVisibility`.

The current algorithm says that a snapshot sees transactions committed at or
before `visibleThrough`, never sees transactions active at capture, and treats
the owning transaction specially through `visibleThroughCommand`.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Reference models:

* PostgreSQL snapshot visibility and command-id style self-visibility.
* InnoDB read view / history retention as a reference model only.

Risk to track:

* Any later shortcut must prove it respects `activeAtCapture`,
  `visibleThrough`, and `visibleThroughCommand`.

### Statement snapshot and read-your-own-writes

Current authority: `MvccStatementSnapshot`, `MvccTable`, and
`MvccInheritedTable`.

A statement snapshot is captured before a statement writes. Versions written by
the current statement are stamped with that statement's command sequence, and a
statement reads only commands before `visibleThroughCommand`. The next statement
can then see the prior statement's writes.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Risk to track:

* Access-path shortcuts must not turn statement-level self-writes into
  premature visibility.

### Writer-borrowed reads and write-intent reads

Current authority: `MvccInheritedTable`.

The inherited SQL bridge can read local write intents for the owning transaction
when the command sequence is visible to the current snapshot. Current-committed
shortcuts must remain off when a statement/snapshot requires write-intent or
historical visibility.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Risk to track:

* Ordered-index, row-id, or committed-image shortcuts must record why they are
  safe or rejected for writer-borrowed reads.

### Version-chain visibility and write conflicts

Current authority: `MvccVersionChain` and `MvccVisibility`.

Version chains are newest-first. Reads search for the first visible version.
Updates and deletes find a visible current version and then mark/append through
transaction and command-sequence metadata. Write conflict detection is still a
DelosDB-owned algorithm and should become jcstress-backed before broad runtime
concurrency expansion.

Classification: `MVCC_AUTHORITY_ALGORITHM` and `VALIDATION_ALGORITHM`.

Risk to track:

* Deterministic tests prove intended behavior, but publication/interleaving
  proofs still belong in a future jcstress visibility-probe slice.

### Rollback and savepoint visibility

Current authority: `MvccVersionChain.rollbackTransactionChangesAfter` and
`MvccTable.rollbackTransactionChangesAfter`.

Savepoint rollback removes versions created after the savepoint boundary and
clears deletions after the boundary for the same transaction.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Risk to track:

* New durable write paths must continue to separate rollback-local state from
  already-committed page-backed state.

### Purge horizon and snapshot leases

Current authority: `MvccTransactionManager`, `MvccSnapshotLease`, `MvccTable`,
`MvccPurgeDaemon`, and `MvccVisibilityDebtPolicy`.

Snapshot leases retain watermarks so vacuum/purge can keep history needed by
open snapshots. The purge daemon rechecks retained inherited MVCC transactions
or scans before vacuuming. Visibility debt is a scheduling heuristic only; it is
not the final authority for whether a version may be pruned.

Classification: `MVCC_AUTHORITY_ALGORITHM` and `DIAGNOSTIC_ONLY_ALGORITHM` for
visibility-debt reporting.

Reference models:

* PostgreSQL pruning/vacuum horizons.
* InnoDB purge/history list.
* HerdDB checkpoint/pin discipline as a lifecycle reference only.

Risk to track:

* A later long-reader purge stress slice must prove that retained snapshots keep
  required history and that unprotected stale snapshots fail loudly.

### History-pruned failure behavior

Current authority: `MvccHistoryPrunedException`, `MvccPrunedVersionMarker`,
`MvccTable`, and `MvccVersionChain`.

If a snapshot would have needed pruned history, DelosDB fails loudly instead of
silently returning row-not-found or a newer version.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Risk to track:

* Any future page-local pruning, purge queue drain, or visibility-map shortcut
  must preserve the fail-loudly boundary.

### Transaction outcome publication and recovery visibility

Current authority: `MvccTransactionStatusStore`, `MvccTransactionOutcomeLog`,
`MvccRecoveryReplayEngine`, and the page-backed state store.

Durable transaction outcome records decide whether recovered versions are
visible. Unknown outcomes fail loudly on strict paths, and active records can be
recovered as `RECOVERY_PENDING` so uncommitted versions are not exposed by
default.

Classification: `MVCC_AUTHORITY_ALGORITHM`.

Risk to track:

* Recovery replay and checkpoint compaction must preserve the visibility rule
  that committed versions need a committed outcome and aborted/unknown creators
  are not silently exposed.

### Visibility map and purge queue metadata

Current authority: `MvccVisibilityMapStore`, `MvccPurgeQueueStore`, and
`PageBackedMvccTable`.

Visibility-map and purge-queue metadata are lifecycle hints and reports. They
must never override the transaction/snapshot visibility algorithm.

Classification: `DIAGNOSTIC_ONLY_ALGORITHM` unless a later named gate promotes a
specific metadata decision to authority.

Risk to track:

* Hints must be rebuildable from page-backed rows and transaction outcomes.

## Reference-model influence

* PostgreSQL is the primary reference model for snapshot visibility, HOT-safe
  version-chain reasoning, pruning horizon discipline, and fail-safe vacuum
  behavior.
* InnoDB is the primary reference model for purge/history-list lifecycle and
  durable outcome thinking.
* HerdDB is a reference model for small-Java-engine lifecycle pinning and
  checkpoint/purge coordination.
* Calcite is a reference model only for future access-path diagnostics; it is
  not a replacement optimizer.
* JDK 25 is relevant for future jcstress/JFR/JMH proof lanes, not for changing
  the visibility algorithm in this audit.

## Future implementation candidates

* `delosdb-jcstress-mvcc-visibility-probes-overlay.zip` for publication and
  interleaving proofs.
* `delosdb-mvcc-long-reader-purge-stress-overlay.zip` for retained-snapshot and
  visibility-debt stress.
* `delosdb-storage-path-diagnostics-runtime-overlay.zip` for explaining why a
  visibility-sensitive shortcut was chosen or rejected.
* `delosdb-jfr-storage-lifecycle-events-overlay.zip` for visibility/purge/checkpoint
  lifecycle observability.

## Status

Default behavior remains unchanged. This audit records current authority and
risk boundaries only.
