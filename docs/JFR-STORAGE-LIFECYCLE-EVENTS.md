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

No unwired event method remains. Purge, checkpoint, recovery, backup, buffer-eviction, heap-sanity,
and path-decision events are not part of the production JFR surface; only events with a live producer
and stable observability contract are retained.

## Verification

```bash
./gradlew \
  delosJfrStorageLifecycleEventsStaticAnalysis \
  :delosdb-derby-store-api:check \
  --console=plain
```
