# JFR storage lifecycle event

DelosDB retains **one live production event** for the RawStore-backed MVCC analyze/statistics lifecycle:

```text
org.apache.derby.delosdb.mvcc.AnalyzeStatistics
```

The event is emitted by `DelosMvccAnalyzeStatisticsLifecycleDiagnostics` through
`DelosStorageLifecycleJfr.recordMvccAnalyzeStatistics(...)`.

## Contract

JFR is **observability only**. Event creation and recording must not change Derby optimizer authority,
MVCC visibility, page format, locking, transaction behavior, durability, recovery ordering, or the
selected storage path. `Event.isEnabled()` keeps the disabled path bounded.

No unwired event method remains. The earlier purge, checkpoint, recovery, backup-sidecar,
buffer-eviction, heap-sanity, and path-decision event sketches belonged to the retired Phase 8 oracle
or never acquired a live producer. Stage 8.7.3 deletes those dead production surfaces rather than
keeping speculative APIs.

## Verification

```bash
./gradlew \
  delosJfrStorageLifecycleEventsStaticAnalysis \
  :delosdb-derby-store-api:check \
  --console=plain
```
