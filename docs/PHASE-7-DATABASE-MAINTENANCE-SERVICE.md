# Phase 7.6 — Database-Owned MVCC Maintenance Service

## Purpose

The inherited MVCC provider previously created one asynchronous purge executor
for every open table. That ownership model scaled thread count with table count,
provided no database-wide prioritization, and made database shutdown depend on
closing independent table executors in the correct order.

Phase 7.6 replaces those executors with one maintenance service owned by the
open `DelosStorageStore` for a database.

The purge algorithm itself is unchanged. Tables still own:

- visibility-debt calculation;
- oldest-reader and retained-snapshot checks;
- durable mutation and backup-boundary entry;
- page-volume vacuum execution;
- purge outcome and table diagnostics.

The database service owns only scheduling and lifecycle.

## Ownership

```text
one database
    one DelosStorageStore
        one MvccDatabaseMaintenanceService
            bounded worker pool
            one periodic scanner
            N registered tables
```

The Derby compatibility bridge now reuses one storage store for all MVCC
conglomerates in the same database. Database state cleanup closes the store,
which first drains maintenance and then closes its tables.

Standalone table tests still receive a private service because they do not have
a database-store owner. Production tables opened through `MvccInheritedStore`
share the store-owned service.

## Scheduling routes

### Commit-triggered wakeup

After a committed transaction creates eligible visibility debt, the table:

1. applies the existing changed-row threshold;
2. applies the existing visibility-debt policy;
3. records the table purge-daemon decision;
4. submits one deduplicated maintenance request to the database service.

### Periodic idle-table scan

The database service periodically asks every registered table for its current
visibility-debt priority. This allows cleanup to resume after a long reader
closes even when no later commit occurs on that table.

Periodic scheduling is active only when the existing purge daemon and async
properties are enabled.

## Prioritization

Queued work is ordered by:

1. total visibility-debt score;
2. obsolete-version growth;
3. pending purge entries;
4. FIFO sequence for equal priority.

This is a scheduling order only. It does not change vacuum eligibility or the
reader-horizon invariant.

## Concurrency

The worker count is bounded per database.

```text
delosdb.mvcc.maintenance.workerCount
```

The default is one worker and the accepted range is one through eight.

The periodic interval is configured by:

```text
delosdb.mvcc.maintenance.periodMillis
```

The default is 1000 milliseconds.

Each table has at most one queued or running maintenance task. A wakeup received
while that task is active requests one later re-evaluation rather than creating
an unbounded task stream.

## Safety boundaries

Maintenance still enters the table's durable-mutation boundary. Therefore it:

- serializes with same-table commit publication;
- waits behind an active backup freeze;
- cannot run after table storage has closed;
- rechecks active transactions and retained snapshots immediately before
  vacuuming;
- rechecks visibility debt immediately before vacuuming.

The database scheduler does not make the purge algorithm optimistic and does
not bypass checkpoint, backup, or reader-horizon ownership.

## Shutdown

Database-store shutdown performs:

```text
stop periodic scans
stop accepting new maintenance requests
drain queued and running maintenance tasks
close registered tables
close page volumes and sidecars
```

Closing one table unregisters it and waits for its queued or running maintenance
work to finish before closing that table's durable state. Other tables in the
database continue using the shared service.

## Diagnostics

The storage diagnostics surface now reports:

- configured database worker count;
- registered table count;
- queued task count;
- commit wakeup count;
- periodic scan count;
- executed task count;
- scheduler failure count;
- maximum active worker count;
- whether the service still accepts work.

Existing per-table purge-daemon metrics remain authoritative for scheduling,
skip, run, visibility-debt, and last-decision details.

## Proof obligations

`MvccDatabaseMaintenanceServiceTest` proves:

- multiple tables in one store share one service;
- worker concurrency stays within the configured bound;
- higher visibility/storage debt runs before lower debt;
- periodic scans wake an idle table;
- a retained reader prevents purge;
- purge resumes after that reader closes without another commit;
- store shutdown stops the service and closes tables cleanly.

## Deliberate limits

This slice does not:

- change the vacuum or purge algorithm;
- add a JVM-global maintenance service;
- allow maintenance to bypass backup coordination;
- change checkpoint format or frequency;
- add cross-database workers;
- tune worker count automatically;
- bypass the Phase 7.7 database-scoped backup coordinator.
