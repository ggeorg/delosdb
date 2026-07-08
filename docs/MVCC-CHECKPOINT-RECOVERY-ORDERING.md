# MVCC checkpoint/recovery ordering audit

This is an audit artifact, not a behavior change.

This document records the first explicit DelosDB ordering map for MVCC checkpoint, recovery, purge, backup, and dirty-page lifecycle behavior. It is deliberately diagnostic: it does not introduce a new recovery algorithm, does not replace Derby raw-store backup/restore flow, and does not make PostgreSQL, InnoDB, HerdDB, H2, or Derby implementation formats into DelosDB dependencies.

## Non-goals

* No Java runtime behavior change.
* No storage format change.
* No recovery replay behavior change.
* No backup/restore behavior change.
* No external framework dependency.
* No new shared recovery service until both heap and MVCC have concrete proof points.
* No change to Derby heap page format, Derby raw log format, catalog behavior, JDBC behavior, or DRDA behavior.

Derby RawStore remains compatibility authority for inherited backup/restore control flow. MVCC recovery replay remains provider-owned. PostgreSQL, InnoDB, HerdDB, H2, and Derby are reference models only.

## Ordering surface

The current DelosDB MVCC lifecycle has several independently proven pieces. This audit names the ordering questions that must stay explicit before the next implementation phase changes recovery, checkpoint, purge, backup, or buffer flushing.

| Area | Current owner | Ordering question | Current status |
| --- | --- | --- | --- |
| Checkpoint lifecycle | `PageVolumeMvccCheckpointStore` | checkpoint marker order: prepare marker before checkpoint publication, completion marker after publication | First-generation lifecycle markers exist; checkpoint file is metadata, not storage authority. |
| Dirty-page flushing | `MvccBufferFlushCoordinator` | dirty-page flush order: a page with pageLSN must not flush before the corresponding WAL/sidecar evidence is durable | WAL-before-flush guard exists; policy tuning remains later. |
| Transaction outcomes | `MvccTransactionOutcomeLog` | transaction outcome replay: replayed versions require committed/aborted outcome authority | Strict recovery fails loudly on unresolved outcomes. |
| Subsystem recovery records | `MvccSubsystemRecoveryRecordStore` and `MvccRecoveryReplayEngine` | subsystem recovery record order: cross-subsystem completeness is validated before page mutation replay | Phase L replay engine validates the plan before replay. |
| Checkpoint compaction | `PageBackedMvccTable` | checkpoint image rewrite must keep mutation log and transaction outcome log consistent | Both mutation log and outcome log are rewritten from the retained checkpoint image. |
| Purge scheduling | `MvccPurgeDaemon` and visibility-debt policy | purge/checkpoint interaction: purge must not remove history still needed by open snapshots or checkpoint/recovery evidence | Visibility debt controls scheduling; long-reader stress remains a future proof. |
| Backup sidecars | `DelosMvccBackupSidecarSupport` through `RawStore` hooks | backup snapshot boundary: heap backup control flow must include MVCC sidecar state and manifest verification | Sidecar copy/restore is isolated in a Derby-storage helper without MVCC-module imports. |

## Reference model influence

* PostgreSQL is a reference for WAL/resource-manager ordering discipline, not for copying WAL format.
* InnoDB is a reference for mini-transaction and dirty-buffer ordering discipline, not for copying page or redo formats.
* HerdDB is a reference for small Java-engine checkpoint/log sequence visibility, not for importing its engine.
* H2 is a reference for compact Java store inspection and copy-friendly storage tooling, not for replacing Derby compatibility storage.
* Derby is the compatibility anchor for RawStore backup/restore control flow and inherited heap/raw-log boundaries.

## Known next-proof gaps

This audit should be followed by proof slices, not immediate algorithm rewrites.

1. Expand crash/reopen matrices around checkpoint prepare/publish/complete interruption points.
2. Add a mixed heap + delos_mvcc backup/restore matrix that verifies manifest, stale sidecar cleanup, and reopen behavior.
3. Add a lifecycle consistency report that shows checkpoint, recovery, purge, analyze, and backup state together.
4. Stress purge/checkpoint interaction with long readers and visibility debt.
5. Benchmark flush ordering and recovery replay costs through external validation lanes, not S0.

## Safety rule

Any future implementation that changes checkpoint marker order, dirty-page flush order, subsystem recovery record order, transaction outcome replay, purge/checkpoint interaction, or backup snapshot boundary must introduce a focused compatibility/proof gate first. This audit is the map; it is not permission to rewrite the algorithms.
