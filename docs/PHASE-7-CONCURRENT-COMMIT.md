# Phase 7 — Concurrent Commit Pipeline and Autonomous Maintenance

## Slice 7.1: concurrent commit durability instrumentation

This slice establishes measurement before changing transaction durability or
writer locking.

It does not move, remove, defer, or combine any force call. It does not narrow
the inherited MVCC table write lock. It records the current behavior so later
changes can be justified by evidence.

## Current commit order

For a writable inherited MVCC transaction, the current route is:

```text
begin transaction
    force ACTIVE transaction-status record

commit request
    wait for process-level backup mutation guard
    wait for inherited-table write lock
    validate changed rows
    force COMMITTED transaction-status record
    force one page-volume WAL transaction batch
    persist row, index, overflow, and recovery state
    rewrite checkpoint lifecycle state
    optionally run synchronous purge maintenance
    release table write lock
    release backup mutation guard
    return to caller
```

The instrumentation preserves this order exactly.

## Measurements

Each completed commit publishes one JFR event:

```text
org.apache.derby.delosdb.mvcc.Commit
```

The event records:

```text
storage and transaction identity
changed-row count
total commit latency
backup-coordinator wait time
table write-lock wait and hold time
post-commit maintenance time
per-table and process-wide commit-request concurrency
per-table and process-wide durability-queue concurrency
per-table and process-wide durability-execution concurrency
transaction-status force calls
transaction-outcome force calls
page-volume WAL force calls
other sidecar force calls
directory force calls
page-volume force calls
page-volume pages logically covered by force calls
sidecar and page-volume bytes logically covered by force calls
complete or partial durability measurement
whether the commit call returned successfully
```

The ACTIVE status force captured at transaction begin is carried by the
transaction handle and combined with commit-time force counts. Commit latency
starts when the JDBC/storage commit call begins, so it does not include the
earlier ACTIVE-status append. The event marks
the measurement complete only when recording was enabled for both begin and
commit. Synchronous purge duration is reported separately and its force calls
are not attributed to the transaction durability fence.

A "force call" means a successful return from the current force policy or page
volume `force()` method. The counters describe the current implementation; they
do not claim that an operating system or storage device completed a physical
media flush beyond the guarantees of the Java channel or volume implementation.

## Existing terminology correction

`MvccBufferFlushCoordinator` now calls its internal batches page-flush batches,
not group-commit batches. The existing mechanism groups dirty page writes before
one page-volume force. It is not cross-transaction group commit.

## External benchmark

The standalone benchmark build under `benchmarks/jmh` now also owns a public
JDBC concurrent-commit runner:

```bash
./gradlew -p benchmarks/jmh runConcurrentCommitBenchmark
```

Default matrix:

```text
writers:              1, 2, 4, 8, 16
topologies:           same table, different tables, different databases
operations:           insert, update
rows per transaction: 1, 8
measured transactions: 20 per writer
warmup transactions:   2 per writer
```

The runner uses only public JDBC and JDK JFR consumer APIs. It does not import
Derby or DelosDB implementation classes. It verifies committed row counts for
insert workloads and the final value and payload of every update fixture row.

Throughput covers the full transaction loop (batch execution plus commit);
commit percentiles time only the JDBC `commit()` call. The runner deliberately
enables the commit JFR event, so absolute throughput includes the diagnostic
recording cost; scenario comparisons use the same instrumentation.

Reports are written to:

```text
benchmarks/jmh/build/reports/concurrent-commit/results.csv
benchmarks/jmh/build/reports/concurrent-commit/results.json
benchmarks/jmh/build/reports/concurrent-commit/human.txt
benchmarks/jmh/build/reports/concurrent-commit/run-manifest.txt
```

Per-scenario JFR recordings are retained by default in the same directory.
The benchmark databases default to
`benchmarks/jmh/build/concurrent-commit-databases`; override that location with
`-Pdelosdb.concurrentCommit.databaseRoot=<path>` when the target filesystem is
part of the measurement.

Example focused run:

```bash
./gradlew -p benchmarks/jmh runConcurrentCommitBenchmark \
  -Pdelosdb.concurrentCommit.writers=1,2,4 \
  -Pdelosdb.concurrentCommit.topologies=same-table,different-tables \
  -Pdelosdb.concurrentCommit.operations=insert \
  -Pdelosdb.concurrentCommit.rowsPerTransaction=1,8 \
  -Pdelosdb.concurrentCommit.transactionsPerWriter=10 \
  -Pdelosdb.concurrentCommit.warmupTransactionsPerWriter=2
```

## Questions this slice answers

The reports establish:

1. whether multiple same-table commit requests overlap;
2. whether more than one same-table transaction executes inside the durability
   section concurrently;
3. how much commit time is spent waiting for the backup and table-lock
   boundaries;
4. how many independent transaction-status, transaction-outcome, WAL, sidecar,
   directory, and page force calls one committed transaction causes;
5. whether multi-row transactions amplify page forces per row;
6. whether different tables or databases execute durability work concurrently
   at process scope even though one table remains serialized.

No optimization should be selected until these measurements are available on
the target JDK 25 machine.
