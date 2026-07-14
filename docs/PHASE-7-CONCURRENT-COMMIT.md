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
changed-row validation time
transaction-status commit time
page/checkpoint persistence time
ordered-index rebuild time
transaction-state publication time
post-commit maintenance time
per-table and process-wide commit-request concurrency
per-table and process-wide durability-queue concurrency
per-table durability coordinator mode and enrollment depth
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

## Phase 7.1 result and transaction-complete fence

The completed JDK 25 matrix proved that same-table durability execution remains
serialized and that multi-row throughput is dominated by repeated outcome-log
and page-volume forcing.

Phase 7.2 documented the existing protocol. Phase 7.3 now records all payloads
in one prepared mutation batch and publishes one transaction-outcome commit as
the page-volume transaction-complete fence. The authoritative ordering, crash
invariant, compatibility rules, and remaining page-force work are documented in:

```text
docs/PHASE-7-DURABILITY-PROTOCOL.md
```

Phase 7.4 now stages every main-table page mutation from one transaction and
forces the table page volume once. The transaction-complete outcome fence remains
the recovery authority, and injected partial-write and force-failure proofs show
that recovery completes the full transaction without exposing a prefix.

The expected inline-row benchmark shape is now two page-volume forces for both
one-row and eight-row transactions: one main-table batch and one ordered-index
materialization. Table-lock scope remains unchanged until the target-machine
benchmark is rerun.

## Phase 7.5 same-table boundary audit

After the Phase 7.4 target-machine rerun, eight-row inline transactions use one
outcome force and two page-volume forces, but same-table execution remains one.
Phase 7.5 therefore measures the work still enclosed by the inherited-table
write lock before changing that lock.

The commit JFR event and standalone benchmark now separate:

```text
changed-row validation
transaction-status commit
page/checkpoint persistence
ordered-index rebuild
transaction-state publication
post-commit maintenance
```

The current ownership map, lower-level serialization points, and required first
lock split are authoritative in:

```text
docs/PHASE-7-SAME-TABLE-COMMIT-BOUNDARY.md
```

This slice changes instrumentation only. The next behavior target is concurrent
immutable preparation of non-conflicting same-table commits followed by an
explicit per-table durability queue. It does not yet claim concurrent physical
page publication or cross-transaction group commit.



## Phase 7.6 immutable prepared commits

Phase 7.6 implements the first same-table boundary split. A commit now captures
its surviving write intents and revision under a short table read lock, then
deep-copies and encodes row payloads outside the table write lock. The resulting
`MvccPreparedCommit` is queued at an explicit fair per-table durability
coordinator.

Immediately before publication, the table write lock revalidates transaction
activity, write-intent revision, and same-row writer ownership. Physical
page/checkpoint publication and the complete ordered-index rebuild remain
serialized.

The commit event and benchmark now report:

```text
immutable preparation time
per-table and process-wide preparation concurrency
durability-coordinator wait and hold time
table-lock wait and hold time
```

The focused proof requires two non-conflicting same-table transactions to reach
preparation concurrency two while durability execution remains one. The force
and recovery contract remains two status forces, one outcome force, one WAL
force, and two page-volume forces for ordinary inline-row transactions.

The current route and invariants are authoritative in:

```text
docs/PHASE-7-PREPARED-COMMIT-COORDINATOR.md
```

## Phase 7.7 transaction sidecar force batching

Phase 7.7 removes the remaining row-count-dependent sidecar force amplification
from ordinary inline-row commits. The free-space map is rewritten once after all
transaction page mutations are staged, and every row-directory head is appended
with one forced transaction batch.

The benchmark human output now includes `sidecar=` and `directory=` force counts.
The expected comparison is:

```text
one row:   sidecar = S
multiple rows: sidecar = S
```

where `S` is the constant per-commit sidecar work. Page-volume force counts remain
`page=2`.


## Bounded enrollment before transaction group commit

After immutable preparation and transaction sidecar batching, the per-table
durability boundary now uses a bounded FIFO enrollment queue instead of only a
fair lock. The queue preserves one-at-a-time physical publication and all
existing force counts. It exists to prove that multiple prepared same-table
commits can be enrolled before any cross-transaction force sharing is added.

The former direct fair-lock mode is retained only as a package-private
differential proof path. Direct and queued modes execute the same publication
code and must produce the same durable state, force counts, recovery result, and
pre-publication conflict result.

The authoritative design and removal criteria for the temporary comparison mode
are documented in:

```text
docs/PHASE-7-COMMIT-ENROLLMENT-QUEUE.md
```

Required evidence before real group commit:

```text
tablePreparationConcurrency > 1
durabilityEnrollmentDepth   > 1
tableDurabilityExecutionConcurrency = 1
```
