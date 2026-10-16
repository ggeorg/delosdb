# RawStore MVCC Database Maintenance

## Purpose

`MvccRawStoreMaintenanceService` is the database-owned scheduler for
RawStore-backed MVCC reclamation. It owns scheduling and autonomous RawStore
transactions only. Visibility rules, reader horizons, logical and physical
locking, vacuum planning, logging, undo, commit, and recovery remain owned by
the established RawStore MVCC components.

## Ownership

```text
one booted database
    -> one MvccRawStoreRuntime
        -> one MvccRawStoreMaintenanceService
            -> one bounded virtual worker
            -> one periodic scanner
            -> registered RawStore MVCC table descriptors
```

The service is disabled by default and is not created as one executor per table.
Read-only databases do not accept maintenance work.

## Configuration

```text
delosdb.mvcc.rawStoreMaintenance.enabled
delosdb.mvcc.rawStoreMaintenance.periodMillis
delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold
```

Defaults are disabled, a 1,000 ms periodic interval, and an eight-row commit
threshold. Invalid values fall back to those defaults.

## Scheduling

Maintenance may be requested when a table is registered, after a commit crosses
the configured changed-row threshold, or by the periodic scanner. A table has at
most one queued or running task; another signal requests reevaluation rather
than creating an unbounded queue.

## Safety boundaries

Every run opens an autonomous RawStore transaction and rechecks eligibility
before mutation. Maintenance remains subordinate to:

- active reader and retained-snapshot horizons;
- the table's physical maintenance boundary;
- RawStore logging, undo, commit, and recovery;
- database read-only and shutdown state;
- backup and table-lifecycle ownership.

The scheduler cannot decide visibility, bypass transaction logging, or mutate a
closed table.

Vacuum keeps every version that may still be required by an active or retained snapshot, preserves the
necessary visible predecessor at the oldest retained horizon, and removes obsolete history only inside
a logged RawStore transaction. Tombstone reclamation and derived-index rebuild follow the same horizon
and transaction authority.

## In-place compress

`SYSCS_UTIL.SYSCS_INPLACE_COMPRESS_TABLE` accepts `delos_mvcc` tables with Derby's three options:

- **purge** vacuums the table's versions to the horizon, then purges the deleted index entries that no
  snapshot needs (see `MVCC-SECONDARY-INDEX-VISIBILITY.md`, stage 2);
- **defragment** is skipped: it moves heap rows to earlier pages and rewrites their index entries,
  while an MVCC row keeps its logical row id wherever its versions are stored;
- **truncate** vacuums again and returns the free pages at the end of each index.

Full compress (`SYSCS_COMPRESS_TABLE`) and `TRUNCATE TABLE` are not supported for `delos_mvcc` tables.

## Shutdown and diagnostics

Shutdown stops periodic scans, rejects new work, interrupts and joins the worker,
and waits for the scanner to terminate. Immutable diagnostics report scheduling,
completion, skip, failure, mutation, and removed-version counters together with
registered-table and active-worker state.

## Verification

```text
:delosdb-tests:delosFunctionalTests --tests '*MvccRawStoreMaintenanceDiagnosticsTest'
:delosdb-tests:delosFunctionalTests --tests '*MvccRawStoreVacuumTest'
:delosdb-tests:delosFunctionalTests --tests '*MvccInplaceCompressTest'
:delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests
```
