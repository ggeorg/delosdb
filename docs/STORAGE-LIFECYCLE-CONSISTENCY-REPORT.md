# DelosDB storage lifecycle consistency report

DelosDB now exposes a shared, read-only lifecycle report for heap and MVCC storage targets.

The report is intentionally diagnostic-only. It does not create a new storage authority, does not change Derby optimizer behavior, does not change backup/restore behavior, and does not mutate heap or MVCC formats.

## Scope

The report aggregates already-existing lifecycle signals into one shape:

* checkpoint status
* purge/vacuum state
* Derby analyze/update-statistics observation for MVCC tables
* backup status marker
* consistency summary

## API

The storage API now includes:

* `DelosStorageLifecycleConsistencySnapshot`
* `DelosStorageLifecycleConsistencyReport`
* `DelosStorageDiagnosticsRegistry.lifecycleConsistencySnapshot(...)`
* `DelosStorageDiagnosticsRegistry.lifecycleConsistencyReport(...)`

The report accepts the existing `DelosStorageConsistencyTarget` shape, so heap targets can carry a database directory while MVCC targets stay provider-owned.

## Compatibility rules

The report must remain read-only.

It must not:

* change heap page format
* change raw log format
* change MVCC page or RawStore format
* replace Derby optimizer/statistics authority
* make heap depend on MVCC implementation
* make MVCC durable storage depend on Derby heap internals
* treat backup status as storage authority

## Current proof

`StorageLifecycleConsistencyReportTest` creates a mixed heap + `delos_mvcc` database, drives MVCC checkpoint, purge/vacuum, and analyze/update-statistics lifecycle points, then verifies that one shared report can summarize both engines without changing SQL results or reopen behavior.
