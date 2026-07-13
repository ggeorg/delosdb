# Comparative Engine Architecture Audit

Status: audit only
Scope: DelosDB current green tree after engine-depth cleanup, H2 MVStore, PostgreSQL backend storage, and MariaDB/InnoDB storage engine source/reference model.
Behavior change: none.
S0 gate change: none.
Module change: none.

## Purpose

DelosDB has just completed the balanced storage-modernization and engine-depth plans. The right next step is not another feature sprint. The right next step is a comparative engine audit against mature or adjacent systems so the next implementation plan is driven by evidence rather than DelosDB-only abstractions.

This document compares DelosDB against:

* Apache Derby, as the compatibility anchor.
* H2, primarily through `MVStore` and transaction-store code.
* PostgreSQL, through backend storage, WAL, buffer, vacuum, and optimizer subsystems.
* MariaDB/InnoDB, through `storage/innobase` source layout and current InnoDB documentation.

The goal is not to copy these systems. The goal is to identify where DelosDB is still structurally incomplete, where its architecture is already strong, and where copying a mature engine would be the wrong move because Derby compatibility changes the problem.

## Source basis

### DelosDB current tree

Important DelosDB implementation markers now present:

* Derby compatibility/module/static gates remain green.
* MVCC ordered index authority is present and candidate-index authority is removed from normal read paths.
* MVCC storage statistics feed the Derby `StoreCostController` path through the `MvccStoreCostController` seam.
* `MvccRecoveryReplayEngine` exists and is used by strict open/replay paths.
* `MvccBufferFlushCoordinator` enforces WAL-before-page-flush discipline for pageLSN-bearing dirty pages.
* MVCC sidecar backup/restore includes a `delos_mvcc.BACKUP-MANIFEST`.
* `MvccPurgeDaemon` exists as a deterministic commit-boundary scheduler, with async mode explicitly opt-in.
* Performance/concurrency validation tasks exist, while external JMH/jcstress/SQLancer slots remain outside S0.
* Roadmap/prose gates have been removed from S0.

### H2

The uploaded H2 source has a compact Java storage core centered around:

* `org.h2.mvstore.MVStore`
* `org.h2.mvstore.MVMap`
* `org.h2.mvstore.Page`
* `org.h2.mvstore.Chunk`
* `org.h2.mvstore.tx.TransactionStore`
* `org.h2.mvstore.cache.CacheLongKeyLIRS`
* MVStore compaction, chunk metadata, background-save, and transaction-map machinery

H2 is useful as a comparison for a compact embeddable Java storage system. It is not a compatibility model for DelosDB because DelosDB must preserve Derby SQL/JDBC/DRDA/catalog behavior and inherited database compatibility boundaries.

### PostgreSQL

The uploaded PostgreSQL source has mature separations for:

* WAL and recovery: `src/backend/access/transam/xlog*.c`
* checkpoints and WAL writer: `src/backend/postmaster/checkpointer.c`, `walwriter.c`
* buffer manager: `src/backend/storage/buffer/*`
* heap access and vacuum: `src/backend/access/heap/*`, `src/backend/commands/vacuum*.c`, `src/backend/postmaster/autovacuum.c`
* statistics and optimizer integration: `src/backend/statistics`, `src/backend/optimizer`, `src/backend/utils/adt/selfuncs.c`

PostgreSQL is the best comparison for long-running production-grade MVCC, vacuum, WAL/checkpoint discipline, and cost-model maturity.

### MariaDB/InnoDB

The uploaded MariaDB source contains InnoDB under `storage/innobase`, including:

* buffer pool and flushing: `buf/`, `buf0buf.cc`, `buf0flu.cc`, `buf0lru.cc`
* redo log: `log/`
* transactions and undo/MVCC: `trx/`, `read/`, `row/`
* B-tree/index implementation: `btr/`
* free-space/file/tablespace handling: `fsp/`, `fil/`
* mini-transaction style internal boundaries: `mtr/`
* purge/background services: `srv/`, purge-related InnoDB subsystems

InnoDB is the best comparison for the combination of buffer pool, redo/undo, purge, clustered indexing, page flushing, and production operational depth.

## Executive verdict

DelosDB is no longer a thin Derby rearrangement. It now has a serious modern-storage direction with explicit compatibility gates, page-backed MVCC authority, ordered index authority, sidecar durability, backup awareness, recovery replay foundations, buffer flush coordination, purge scheduling, and performance/concurrency validation slots.

But compared with PostgreSQL and InnoDB, DelosDB is still early in production-grade storage depth. The biggest remaining gaps are not “more classes” or “more gates.” They are lifecycle depth:

1. full crash/recovery/checkpoint lifecycle,
2. mature buffer replacement and dirty-page scheduling,
3. automatic purge/vacuum policy,
4. statistics lifecycle and optimizer feedback,
5. external fuzz/stress/benchmark discipline,
6. operational tooling for inspection, repair decisions, backup, restore, and corruption triage.

## Maturity matrix

Score meaning:

* 0 = absent or only conceptual
* 1 = skeleton / diagnostic / manually triggered
* 2 = implemented first slice, limited policy/depth
* 3 = production-shaped subsystem but still missing mature automation or tooling
* 4 = mature engine-grade subsystem
* 5 = mature, broad, battle-tested production subsystem

| Area | DelosDB | H2 | PostgreSQL | MariaDB/InnoDB | DelosDB status |
|---|---:|---:|---:|---:|---|
| Compatibility preservation | 4 | 2 | 2 | 3 | Strong Derby compatibility discipline; not a greenfield engine. |
| MVCC storage authority | 3 | 3 | 5 | 5 | Page-backed authority exists; still maturing lifecycle. |
| Index authority | 3 | 3 | 5 | 5 | Candidate authority removed; ordered index exists; deeper cost/stress needed. |
| WAL/recovery replay | 3 | 3 | 5 | 5 | Real replay engine exists; still needs richer crash matrix and checkpoint lifecycle. |
| Checkpoint lifecycle | 2 | 3 | 5 | 5 | DelosDB has records/boundaries, not full production checkpoint policy. |
| Buffer manager | 3 | 3 | 5 | 5 | WAL-before-flush and batching exist; replacement/writeback still shallow. |
| Purge/vacuum | 2 | 2 | 5 | 5 | Commit-boundary purge scheduling exists; no mature adaptive background policy. |
| Backup/restore | 3 | 3 | 5 | 5 | MVCC sidecars included with manifest; needs live backup consistency proof. |
| Optimizer statistics | 2 | 3 | 5 | 4 | MVCC stats feed Derby cost seam opt-in; statistics lifecycle still shallow. |
| Large values/overflow | 3 | 3 | 5 | 5 | Attribute overflow exists; needs compaction/reuse/stress maturity. |
| Consistency checking | 3 | 2 | 4 | 4 | Shared diagnostics exist; repair/triage policy still immature. |
| External validation | 2 | 3 | 5 | 5 | Built-in validation harnesses exist; real JMH/jcstress/SQLancer still pending. |
| Operational tooling | 2 | 3 | 5 | 5 | Inspector/diagnostics exist; DBA tooling is early. |

## What DelosDB should copy

### From H2

Copy the discipline of a small, inspectable embedded Java storage core:

* simple storage data structures,
* testable MVCC transaction-store units,
* compaction concepts,
* compact diagnostic tooling around the storage file.

Do not copy H2’s whole MVStore model as DelosDB’s primary architecture. DelosDB must preserve Derby compatibility and cannot simply become a generic embedded key-value store behind SQL.

### From PostgreSQL

Copy the subsystem discipline:

* WAL and recovery are explicit subsystems, not incidental helpers.
* buffer manager, checkpointer, WAL writer, vacuum, and stats are first-class services.
* autovacuum and statistics are part of the engine lifecycle, not external documentation.
* optimizer costing consumes statistics through established paths.
* fault injection and long-running regression are normal expectations for storage work.

Do not copy PostgreSQL’s process model or heap tuple format. DelosDB has different constraints: JVM runtime, Derby compatibility, and a storage-provider split.

### From MariaDB/InnoDB

Copy the internal contract style:

* buffer pool and flushing are separate from logical table APIs,
* redo/undo/purge are coherent as a lifecycle,
* mini-transaction-like atomicity boundaries are explicit,
* page checksum/torn-write handling is not just diagnostics,
* background maintenance is tunable and measurable.

Do not copy InnoDB’s clustered-index architecture wholesale unless DelosDB deliberately chooses a future modern table format. The current MVCC path should first mature its own row identity, ordered index, page, WAL, recovery, and purge lifecycle.

## What DelosDB should not copy

### Do not copy H2 simplicity where DelosDB needs compatibility boundaries

H2 is useful because it is compact. But DelosDB cannot remove inherited Derby seams just to become compact. Heap mode remains the compatibility anchor.

### Do not copy PostgreSQL scope all at once

PostgreSQL’s architecture is mature because it grew around decades of production use. DelosDB should not introduce a process zoo or a large subsystem matrix prematurely. The next slices should remain small and testable.

### Do not copy InnoDB clustered-index assumptions prematurely

DelosDB’s current MVCC architecture uses logical row identity and ordered MVCC index pages. A clustered-table redesign is a separate future format decision and should not be smuggled into cleanup work.

## Gap analysis by subsystem

### 1. Recovery and checkpointing

Current DelosDB state:

* Recovery records exist.
* Strict replay engine exists.
* Cross-subsystem completeness exists.
* WAL-before-page-flush checks exist.
* Backup sidecars have a manifest.

Remaining gap:

* No mature checkpoint lifecycle that controls WAL retention, redo start, dirty-page draining, sidecar snapshot boundaries, and recovery truncation in one policy.

Priority: MUST_FIX.

Next slice:

`delosdb-mvcc-checkpoint-lifecycle-overlay.zip`

Required behavior:

* checkpoint record identifies consistent replay start,
* WAL retention follows checkpoint safety,
* dirty pages are drained only after WAL coverage is forced,
* sidecar backup uses the checkpoint boundary,
* recovery ignores safely checkpointed older log ranges,
* corrupted/truncated post-checkpoint tail is handled deterministically.

### 2. Buffer replacement and dirty-page writeback

Current DelosDB state:

* Pin/unpin discipline exists.
* Dirty page tracking exists.
* Coordinated flush exists.
* WAL-before-flush exists.
* Page-volume force batching exists.

Remaining gap:

* Replacement policy and dirty writeback are still shallow compared with PostgreSQL buffer replacement and InnoDB buffer pool/flushing.

Priority: MUST_FIX.

Next slice:

`delosdb-mvcc-buffer-replacement-policy-overlay.zip`

Required behavior:

* bounded cache pressure chooses victims deterministically,
* pinned pages are never victims,
* dirty pages move through a flush queue,
* clean pages can be evicted without force,
* hot pages survive simple pressure tests,
* diagnostics expose hits, misses, evictions, dirty evictions, and skipped pinned pages.

### 3. Purge/vacuum lifecycle

Current DelosDB state:

* Cooperative purge scheduler exists.
* Async mode is opt-in and uses managed executor.
* Retained readers are checked before purge.

Remaining gap:

* No mature adaptive purge policy like PostgreSQL autovacuum or InnoDB purge. No visibility-debt thresholds, no sustained soak policy, no per-table tuning.

Priority: MUST_FIX.

Next slice:

`delosdb-mvcc-visibility-debt-purge-policy-overlay.zip`

Required behavior:

* visibility debt is measured per table,
* purge trigger uses dead-version/chain-depth/page-debt metrics,
* long reader prevents unsafe purge,
* after reader release purge catches up,
* diagnostics show debt and purge decisions.

### 4. Statistics lifecycle and optimizer integration

Current DelosDB state:

* MVCC statistics feed `MvccStoreCostController` through explicit opt-in.
* No parallel optimizer channel was created.

Remaining gap:

* Statistics are not yet part of a mature analyze/update-statistics lifecycle comparable to PostgreSQL analyze/autovacuum or InnoDB persistent stats.

Priority: SHOULD_FIX.

Next slice:

`delosdb-mvcc-analyze-statistics-lifecycle-overlay.zip`

Required behavior:

* MVCC table stats can be refreshed through Derby-compatible statistics path,
* row count, live/dead version count, page count, chain depth, index pages, and overflow pages are sampled or computed consistently,
* optimizer cost uses stable stats after analyze,
* default Derby behavior remains compatible.

### 5. Backup/restore and live consistency

Current DelosDB state:

* Sidecars are backed up and restored.
* Backup manifest exists.

Remaining gap:

* Need proof that online backup captures a consistent MVCC sidecar state under concurrent mutation, or else the system must explicitly quiesce/checkpoint the sidecars during backup.

Priority: MUST_FIX.

Next slice:

`delosdb-mvcc-online-backup-consistency-overlay.zip`

Required behavior:

* backup establishes sidecar checkpoint boundary,
* concurrent writer either appears before the boundary or after it, never half-copied,
* restore validates the manifest and checkpoint identity,
* heap-only backup remains unchanged.

### 6. External validation

Current DelosDB state:

* Built-in validation harnesses exist.
* External task slots exist.
* S0 stays fast and deterministic.

Current closeout:

* A real standalone JMH build exists for public JDBC heap/MVCC comparisons.
* Deterministic buffer/cache and page/codec measurement lanes exist.
* jcstress and SQLancer remain explicit external-validation lanes.
* No benchmark or external tool is wired into S0.
* No performance threshold is treated as a correctness gate.

Remaining work belongs to release/CI policy rather than engine implementation:
repeatable environment control, longer multi-fork comparison runs, and approved
external jcstress/SQLancer runners.

### 7. Heap compatibility and inherited cleanup

Current DelosDB state:

* Heap compatibility gate exists.
* Heap diagnostics exist.
* Fork-diff classification exists.
* RawStore backup sidecar work classified.
* Demo VTI cleanup done.

Remaining gap:

* Real heap/raw-store internal cleanup remains limited. OpenHeap/BasePage/StoredPage/FileContainer remain mostly inherited and opaque.

Priority: SHOULD_FIX.

Next slice:

`delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip`

Required behavior:

* helper extraction only,
* no page format change,
* no raw-log change,
* no catalog/DRDA change,
* diagnostics remain read-only,
* Derby compatibility tests stay green.

## Prioritized next implementation roadmap

The comparison says the next work should be lifecycle depth, not more broad gates.

### R1 — MVCC checkpoint lifecycle

Overlay:

`delosdb-mvcc-checkpoint-lifecycle-overlay.zip`

Commit message:

`Add MVCC checkpoint lifecycle boundary`

Why first:

Recovery, buffer flushing, backup, WAL retention, and sidecar consistency all converge here.

### R2 — MVCC online backup consistency

Overlay:

`delosdb-mvcc-online-backup-consistency-overlay.zip`

Commit message:

`Prove MVCC sidecar online backup consistency`

Why second:

Backup/restore is now sidecar-aware, but live backup consistency must be proven before deeper operational claims.

### R3 — MVCC buffer replacement policy

Overlay:

`delosdb-mvcc-buffer-replacement-policy-overlay.zip`

Commit message:

`Add MVCC buffer replacement policy`

Why third:

PostgreSQL and InnoDB show that the buffer manager is a core production subsystem, not just a page cache.

### R4 — MVCC visibility-debt purge policy

Overlay:

`delosdb-mvcc-visibility-debt-purge-policy-overlay.zip`

Commit message:

`Add MVCC visibility-debt purge policy`

Why fourth:

Purge should be driven by measured debt and reader safety, not only commit-count thresholds.

### R5 — MVCC analyze/statistics lifecycle

Execution state: implementation slice delivered by `delosdb-mvcc-analyze-statistics-lifecycle-overlay.zip`.

Overlay:

`delosdb-mvcc-analyze-statistics-lifecycle-overlay.zip`

Commit message:

`Integrate MVCC statistics lifecycle with analyze`

Why fifth:

Cost integration exists, but mature engines connect statistics refresh, table changes, and optimizer decisions.

### R6 — External validation tooling

Execution state: implementation slice delivered by `delosdb-external-validation-tooling-overlay.zip`; Gradle 9 command execution compatibility fixed by `delosdb-external-validation-gradle9-exec-fix-overlay.zip`.

Overlay:

`delosdb-external-validation-tooling-overlay.zip`

Commit message:

`Add external MVCC validation tooling`

Why sixth:

Once the lifecycle subsystems exist, measure them with real external tools.

### R7 — Heap raw-store cleanup phase 3

Execution state: implementation slice delivered by `delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip`.

Overlay:

`delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip`

Commit message:

`Clean heap raw-store boundary helpers`

Why seventh:

Return to inherited Derby code after the MVCC lifecycle work, but do it behind compatibility gates. This slice extracts the DelosDB MVCC sidecar backup/restore manifest mechanics from inherited `RawStore` into `DelosMvccBackupSidecarSupport`, preserving RawStore backup/restore hooks and avoiding any MVCC-module dependency in the Derby raw-store package.

## Current DelosDB position against mature systems

### Already strong

* Derby compatibility discipline.
* Module parity and boundary checks.
* Read-only heap diagnostics.
* MVCC page-backed storage direction.
* Ordered-index authority over candidate/shadow indexes.
* Sidecar backup awareness.
* Recovery replay foundation.
* WAL-before-flush coordination.
* Purge scheduling foundation.
* Built-in performance/concurrency validation harnesses.
* S0 cleaned of roadmap/prose gates.

### Still early

* checkpoint lifecycle,
* WAL retention and replay start policy,
* online backup consistency under concurrent writes,
* buffer replacement and dirty writeback policy,
* automatic purge/vacuum policy,
* statistics refresh lifecycle,
* external validation tooling,
* operational triage tooling,
* long-running stress culture.

## Decision rules after this audit

1. Prefer lifecycle work over new abstraction work.
2. Do not add a shared service unless both heap and MVCC have concrete proof.
3. Do not add roadmap/prose gates to S0.
4. Do not copy PostgreSQL or InnoDB architecture wholesale.
5. Do not simplify away Derby compatibility for H2-like compactness.
6. Any new MVCC durability claim must include fault injection or restore/replay proof.
7. Any new performance claim must include a measurable task outside S0.

## Closeout recommendation

This audit should not be wired into S0. It is a planning artifact. The next implementation should be R1: MVCC checkpoint lifecycle.

Verification for this overlay is only the normal clean closeout:

```bash
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-derby:check :delosdb-storage-mvcc:check
```
