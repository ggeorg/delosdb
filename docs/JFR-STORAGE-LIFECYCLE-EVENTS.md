# DelosDB JFR Storage Lifecycle Events

This document records the JDK 25 JFR storage lifecycle event surface for
DelosDB.

This is an observability artifact, not a storage algorithm rewrite.

## Scope

The first JFR slice adds reusable event classes under the storage API and wires
only already-proven lifecycle points:

* MVCC analyze/statistics lifecycle
* MVCC checkpoint rewrite lifecycle

The remaining event classes are intentionally available for later narrow wiring
slices:

* MVCC purge lifecycle
* MVCC recovery replay lifecycle
* MVCC backup sidecar copy/restore lifecycle
* MVCC buffer eviction lifecycle
* Derby heap sanity-check lifecycle
* DelosDB storage path decision diagnostics

## Event surface

The event carrier is:

```text
org.apache.derby.iapi.store.types.DelosStorageLifecycleJfr
```

Initial event names:

```text
org.apache.derby.delosdb.mvcc.AnalyzeStatistics
org.apache.derby.delosdb.mvcc.Checkpoint
org.apache.derby.delosdb.mvcc.Purge
org.apache.derby.delosdb.mvcc.RecoveryReplay
org.apache.derby.delosdb.mvcc.BackupSidecar
org.apache.derby.delosdb.mvcc.BufferEviction
org.apache.derby.delosdb.heap.SanityCheck
org.apache.derby.delosdb.storage.PathDecision
```

## Compatibility rules

JFR events are observability only.

They must not change:

* Derby optimizer authority
* Derby heap page format
* Derby raw log format
* Derby catalog behavior
* DRDA/JDBC behavior
* MVCC visibility rules
* MVCC recovery ordering
* MVCC backup/restore semantics
* MVCC storage format
* candidate-index quarantine/removal state

Events must be inert unless JFR is recording.

## Why JFR

JFR is provided by the JDK, so this slice adds no third-party runtime dependency.
It gives DelosDB a low-overhead runtime visibility lane for algorithms that were
classified in the algorithm inventory:

* analyze/statistics lifecycle
* checkpoint/recovery lifecycle
* purge/vacuum lifecycle
* buffer/cache lifecycle
* backup/restore lifecycle
* heap diagnostics lifecycle
* storage-path decision lifecycle

## Current wiring

The first runtime wiring points are deliberately narrow:

1. `DelosMvccAnalyzeStatisticsLifecycleDiagnostics` records
   `org.apache.derby.delosdb.mvcc.AnalyzeStatistics` after Derby's inherited
   explicit update-statistics path reaches a `delos_mvcc` table.
2. `PageVolumeMvccStateStore.rewriteCheckpoint()` records
   `org.apache.derby.delosdb.mvcc.Checkpoint` after a successful checkpoint
   rewrite and subsystem checkpoint recovery record append.

No JFR event currently decides whether a storage path is legal, chosen, rejected,
or fallback-only. `DelosStoragePathDiagnostic` remains the diagnostic authority
for storage-path vocabulary, and `DelosStorageLifecycleJfr.recordStoragePathDecision`
is only an adapter for future reporting.


## Durable MVCC boundary

MVCC checkpoint JFR wiring uses `MvccStorageLifecycleJfr` inside `delosdb-storage-mvcc` rather than importing the Derby API event facade into `PageVolumeMvccStateStore`. This preserves the existing storage static-analysis rule that durable MVCC store classes must not import `org.apache.derby.*` APIs.

## Verification

Focused gate:

```bash
./gradlew delosJfrStorageLifecycleEventsStaticAnalysis
```

Normal closeout:

```bash
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-api:check :delosdb-storage-derby:check :delosdb-storage-bridge:check :delosdb-storage-mvcc:check
```

## Not S0 yet

The JFR gate is intentionally not wired into S0 yet.

This keeps the first JFR lane opt-in while the event vocabulary and first runtime
hooks are proven.
