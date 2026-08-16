# RawStore MVCC Maintenance and Diagnostics

## Scope

This design adds database-owned automatic reclamation and immutable operational evidence to the
RawStore-backed `delos_mvcc` format. It schedules the already proven transactional vacuum; it does
not create another reclamation algorithm, WAL, checkpoint, recovery pass, or storage authority.

Automatic maintenance is deliberately opt-in:

```text
delosdb.mvcc.rawStoreMaintenance.enabled=true
```

The RawStore-backed format itself is the production authority. Manual `SYSCS_INPLACE_COMPRESS_TABLE`
vacuum remains available when automatic maintenance is disabled.

## Database ownership and bounds

Each booted RawStore-backed MVCC database owns exactly:

```text
one FIFO maintenance worker
one lightweight periodic scanner
one coalesced table target per registered RawStore MVCC table
```

A table can be idle, queued, or running. While queued or running, additional commit/periodic requests
set one rerun flag instead of adding duplicate work. The logical queue is therefore bounded by the
number of registered tables. FIFO dispatch prevents a continuously hot table from starving older table
requests.

The worker uses one database-owned `ContextManager` and starts one autonomous inherited RawStore transaction per table attempt. It never opens a database by URL and never owns a second database
lifecycle.

Configuration is database-service scoped with a system-property fallback:

```text
delosdb.mvcc.rawStoreMaintenance.enabled
delosdb.mvcc.rawStoreMaintenance.periodMillis
delosdb.mvcc.rawStoreMaintenance.changedRowsThreshold
```

The current convergence defaults are disabled, 1000 milliseconds, and eight committed row changes.
Only the Boolean value `true` enables the worker; absent or malformed enable values remain disabled.
The period is clamped to at least 10 milliseconds and the threshold to at least one. Read-only databases
never start the worker.

## Scheduling

A table becomes known through committed create, existing-table activation, or a committed mutation.
The first registration requests one inspection. Later user commits accumulate changed-row evidence.
A commit queues work after the configured threshold. The periodic scanner queues only targets that:

```text
still have history protected by an older retained snapshot
were skipped because the schema lock was busy
failed and require retry
or accumulated enough committed changes
```

A periodic scan is not a vacuum transaction. It only coalesces eligible table identities into the FIFO
queue.

## One RawStore outcome

For one table attempt the worker orders authority as follows:

```text
start autonomous RawStore transaction
    -> try transaction-duration exclusive logical table-schema lock without waiting
    -> enter the table physical-maintenance write boundary
    -> capture the database vacuum horizon
    -> run MvccRawStoreVacuum
    -> rebuild/publish a private ordered-index generation when history changed
    -> drop the replaced generation transactionally
    -> commit the inherited RawStore transaction
    -> publish the committed generation locator in the runtime descriptor
```

A busy table is skipped without blocking application work and is marked for periodic retry. Any
failure aborts the RawStore transaction and retains retry evidence. The worker does not suppress or
repair corruption: fail-closed vacuum validation remains authoritative.

## Reader-horizon safety

Automatic maintenance uses the same database-wide horizon as manual vacuum:

```text
minimum(
    published MvccCommitSequence high-water,
    every active transaction snapshot lease,
    every held-cursor snapshot lease
)
```

When retained history remains, the table target records `retryRequired=true`. Releasing the final old
reader lease makes the target periodic-eligible; a later attempt can reclaim the protected history.
No timeout invalidates a supported reader snapshot.

## Immutable diagnostics

`DelosStorageDiagnostics.databaseMaintenanceSnapshot()` exposes a versioned immutable
`DelosStorageMaintenanceSnapshot`. The public registry provides the database-scoped convenience entry:

```java
DelosStorageDiagnosticsRegistry.mvccDatabaseMaintenanceSnapshot(databaseDirectory)
```

The snapshot includes:

```text
provider and database identity
storage mode and collection semantics
monotonic diagnostic capture sequence
runtime/enable/read-only/accepting state
worker, registered-table, queued-table, and active-worker counts
oldest queued timestamp and current queue age
commit wakeup, notification-failure, periodic scan, schedule, completion, skip, failure, and mutation counters
removed version and logical-row totals
published commit high-water
vacuum horizon and oldest retained snapshot
retained snapshot count and active writer transaction count
bounded per-table observations and dropped-observation count
```

Each table observation includes container identities, idle/queued/running state, retry requirement,
committed changes since the last run, queued timestamp, schedule/run/skip/failure counters, last trigger,
last decision, last horizon, start/completion timestamps, reclaimed counts, and remaining logical/physical
history.

The database horizon fields are captured atomically under the commit-publication boundary. Worker
counters and bounded table observations are immutable point-in-time evidence collected immediately
afterward; they are diagnostic, not execution inputs.

The table list is capped at 128 observations and reports how many were omitted. Diagnostics never
acquire, reference-count, or close a runtime. File databases use a weak path lookup; memory databases
require the unambiguous single-runtime lookup.

## Lifecycle

Committed table creation registers a target. Transactional table drop unregisters only after RawStore
commit. Savepoint rollback reconciles create/drop registration intent against actual RawStore container
existence.

Database shutdown first removes the weak diagnostics entry, then stops the scanner and worker, waits
for bounded termination, and closes the runtime. No maintenance thread survives database shutdown.

## Failure and starvation evidence

The diagnostic contract makes operational debt visible without becoming a control plane:

```text
oldest queued age exposes scheduling starvation
committed changes expose deferred threshold work
retryRequired exposes retained-reader or failed/busy work
lastDecision distinguishes no work, retained history, table busy, and failure class
published high-water minus vacuum horizon exposes reader-retention debt
remaining versions minus logical rows exposes retained version-chain debt
failure counters expose repeated autonomous transaction failures
```

Failure text contains only the exception class, not SQL values, paths, or row payloads.

## What this slice does not claim

It does not enable automatic maintenance by default. It does not add retention windows, forced reader expiry, page relocation,
defragmentation, end truncation, incremental ordered-index splits/merges, overflow-page compaction, XA
MVCC writes, nested update transactions, a SQL diagnostic virtual table, JMX, external metrics export, or
cross-process scheduling. Retirement of the retained pre-convergence durability components and storage
modules was handled separately.

## Executable proof

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreMaintenanceDiagnosticsTest
```

Permanent architecture gate:

```text
delosMvccRawStoreMaintenanceDiagnosticsStaticAnalysis
```

The focused proof covers explicit-disable behavior, commit-driven autonomous reclamation, one bounded
worker, immutable snapshots, retained-reader horizon/retry, release-driven periodic reclamation,
per-database isolation, shutdown removal, and the same worker path for `jdbc:derby:memory:`.

## RawStore table page diagnostics

A diagnostics request names the public MVCC base conglomerate. The active
database runtime resolves that metadata container to its registered table
descriptor and inspects the table's metadata, version, and current ordered-index
containers in one autonomous read-only RawStore transaction.

`ContainerHandle.getSpaceInfo()` supplies the inherited allocation map's
allocated and free user-page totals. The allocated count includes overflow
pages. Iteration through `getFirstPage()` and `getNextPage()` counts the valid
non-overflow user pages, so the difference is the allocated overflow-page
count. The free-page total is reported as reusable capacity. Diagnostics do not
open files directly, retain container handles, or create a second storage
authority.

