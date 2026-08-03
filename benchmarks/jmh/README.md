# DelosDB standalone JMH build

This directory is an independent Gradle build. It is deliberately absent from
the repository root `settings.gradle`, normal `check`, S0, and runtime module
configuration.

The benchmark consumes assembled DelosDB runtime jars and exercises only public
JDBC behavior. It does not import package-private MVCC classes, Derby
implementation packages, root test source sets, or a benchmark-only production
API.

The benchmark matrix contains:

- prepared primary-key lookup and a key-only covered projection;
- prepared secondary-index equality lookup, a key-only covered projection,
  a covered `count(*)` shape, an `id + payload` projection, and a complete-row projection;
- prepared composite range scan and a projection covered by the composite key;
- prepared full scan;
- prepared aggregate;
- one-row read transaction followed by commit;
- one-row read transaction followed by rollback.

The transaction benchmarks execute `VALUES 1` before ending the transaction.
They therefore measure an honest public-JDBC read-transaction lifecycle rather
than repeated `commit()` or `rollback()` calls while no transaction is active.

Statement preparation, fixture construction, expected-result calculation, and
initial semantic verification occur outside measurement. Every measured read
also validates its deterministic result fingerprint. Read operations execute
repeatedly inside the iteration transaction; iteration rollback remains outside
the measured read method.

The `*Covered*` names describe the SQL shape: every projected and residual
qualifier column is present in the selected Derby index. They do not assert that
every provider already executes an index-only plan. Heap currently has an
inherited covering-index path; MVCC still resolves candidates through its
stable row directory and authoritative version chain. The paired covered and
non-covered methods establish the evidence needed before changing that MVCC
path.

Mutating SQL operations remain in the JUnit benchmark lane until restoration can
be represented without hiding work inside a JMH invocation boundary. Low-level
cache and codec measurements likewise remain in their deterministic module-local
lanes because no stable production benchmark SPI has been justified.

## Build the runtime artifacts

From the repository root:

```bash
./gradlew jars
```

## Compile and verify the standalone benchmark

```bash
./gradlew -p benchmarks/jmh clean check
```

## Smoke run

```bash
./gradlew -p benchmarks/jmh clean jmh \
  '-Pdelosdb.jmh.includes=.*primaryKeyLookup' \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=100 \
  -Pdelosdb.jmh.payloadSizes=128 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.warmupIterations=1 \
  -Pdelosdb.jmh.iterations=1 \
  -Pdelosdb.jmh.warmupTime=250ms \
  -Pdelosdb.jmh.iterationTime=250ms \
  -Pdelosdb.jmh.forks=1
```

## Focused covering-scan comparison

Run the covered shapes beside their non-covered controls:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  '-Pdelosdb.jmh.includes=.*(primaryKeyLookup|primaryKeyCoveredLookup|secondaryEqualityLookup|secondaryEqualityCoveredLookup|secondaryEqualityCoveredCount|compositeRangeScan|compositeRangeCoveredScan)' \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=1000 \
  -Pdelosdb.jmh.payloadSizes=128,4096 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.warmupIterations=5 \
  -Pdelosdb.jmh.iterations=10 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s \
  -Pdelosdb.jmh.forks=2
```

Interpret each pair rather than comparing unrelated methods. The primary and
secondary pairs isolate the cost removed when only the indexed key is needed.
The composite pair measures a projection and residual range predicate fully
represented by `(bucket, quantity)`. The covered count shape removes JDBC row
materialization while retaining candidate traversal and MVCC visibility work.

## Focused row-materialization allocation attribution

Run the secondary-index shapes with JMH's GC profiler:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  '-Pdelosdb.jmh.includes=.*(secondaryEqualityCoveredCount|secondaryEqualityCoveredLookup|secondaryEqualityLookup|secondaryEqualityPayloadLookup|secondaryEqualityFullRowLookup)' \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=1000 \
  -Pdelosdb.jmh.payloadSizes=128,4096 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.profilers=gc \
  -Pdelosdb.jmh.warmupIterations=5 \
  -Pdelosdb.jmh.iterations=10 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s \
  -Pdelosdb.jmh.forks=2
```

The five shapes form one attribution ladder:

```text
covered count
→ one covered integer per candidate
→ two projected integers through the ordinary row path
→ id plus payload
→ all five table columns
```

The JMH JSON remains authoritative. When the `gc` profiler is enabled, the
build also validates `gc.alloc.rate.norm` for every result and writes:

```text
benchmarks/jmh/build/reports/jmh/allocation-summary.txt
```

That report contains latency and normalized bytes allocated per benchmark
operation for every provider, payload size, and query shape. It is evidence for
choosing a safe shared row/descriptor reuse target; it is not an S0 threshold.

Before any row-buffer reuse change, run the heap/MVCC ownership gate:

```bash
./gradlew \
  :delosdb-tests:runDelosJdbcResultBufferOwnershipTest \
  --console=plain
```

The gate proves that values retained across `ResultSet.next()`, sort, distinct,
aggregate, join, scrollable cursor movement, and result-set closure remain
detached from mutable execution buffers.

## Focused JFR allocation-class attribution

The normalized GC-profiler totals identify whether allocation is material, but
not which classes or allocation sites own it. Run the focused JFR attribution
only after the GC matrix demonstrates a meaningful allocation delta:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  '-Pdelosdb.jmh.includes=.*(secondaryEqualityCoveredCount|secondaryEqualityCoveredLookup|secondaryEqualityLookup|secondaryEqualityPayloadLookup)' \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=1000 \
  -Pdelosdb.jmh.payloadSizes=128,4096 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.profilers=gc \
  -Pdelosdb.jmh.allocationJfr=true \
  -Pdelosdb.jmh.warmupIterations=3 \
  -Pdelosdb.jmh.iterations=5 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s \
  -Pdelosdb.jmh.forks=1 \
  --console=plain
```

The JFR profiler records measurement iterations only. The DelosDB
post-processor writes the following beside each `profile.jfr` recording:

```text
allocation-by-class.csv
allocation-by-site.csv
allocation-attribution.txt
```

JFR allocation weights are sampled estimates. Use them to locate classes and
sites; continue to use `gc.alloc.rate.norm` as the exact normalized total per
benchmark operation. The focused run deliberately uses one fork because each
recording is diagnostic attribution rather than a replacement timing baseline.


## Candidate-count scaling and CPU attribution

After allocation reductions stop producing proportional latency gains, measure
the marginal cost of each indexed candidate directly:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  '-Pdelosdb.jmh.includes=.*DelosJdbcCandidateScalingBenchmark.*' \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.rows=1000 \
  -Pdelosdb.jmh.payloadSizes=128 \
  -Pdelosdb.jmh.commitBatchSizes=100 \
  -Pdelosdb.jmh.executionJfr=true \
  -Pdelosdb.jmh.warmupIterations=5 \
  -Pdelosdb.jmh.iterations=10 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s \
  -Pdelosdb.jmh.forks=1 \
  --console=plain
```

The benchmark executes the same covered primary-index `count(*)` shape with:

```text
1, 4, 16, 64, 256 matching candidates
```

The timing slope estimates marginal candidate cost while the intercept estimates
fixed JDBC/query cost. Heap provides the non-MVCC reference. The MVCC scan also
publishes direct runtime-statistics counters for:

```text
ordered and covering candidates
successful covered candidates and fallbacks
directory-page acquisitions and logical fallbacks
version-page acquisitions and slot fetches
visibility checks and version-chain steps
version logical fallbacks
```

One unmeasured diagnostic query is executed during each trial setup. The build
validates those counters and writes:

```text
benchmarks/jmh/build/reports/jmh/candidate-diagnostics/
benchmarks/jmh/build/reports/jmh/candidate-diagnostics/candidate-scaling-diagnostics.csv
```

When `delosdb.jmh.executionJfr=true` is enabled, the JFR post-processor writes
beside each recording:

```text
execution-by-site.csv
execution-attribution.txt
```

Execution samples estimate CPU attribution. JMH remains authoritative for
elapsed time. Use this evidence to choose between current-head visibility
acceleration and page-at-a-time candidate processing; allocation JFR percentages
must not be interpreted as CPU percentages.

## Bounded standalone run

```bash
./gradlew -p benchmarks/jmh clean jmh
```

Defaults are intentionally bounded:

- providers: `heap,mvcc`
- rows: `100`
- payload size: `128`
- fixture commit batch: `100`
- warmup iterations: `2`
- measurement iterations: `3`
- warmup and measurement time: `500ms`
- forks: `1`
- threads: `1` (fixed; concurrency belongs to stress and jcstress lanes)

The default matrix is bounded, but explicit positive row and payload values are
accepted. Large JMH fixtures can take substantial time; use the adaptive JUnit
row-scaling task when a work-budgeted scaling experiment is more appropriate.

A deliberate comparison run can expand the bounded matrix:

```bash
./gradlew -p benchmarks/jmh clean jmh \
  -Pdelosdb.jmh.rows=100,1000 \
  -Pdelosdb.jmh.providers=heap,mvcc \
  -Pdelosdb.jmh.forks=2 \
  -Pdelosdb.jmh.warmupIterations=5 \
  -Pdelosdb.jmh.iterations=10 \
  -Pdelosdb.jmh.warmupTime=1s \
  -Pdelosdb.jmh.iterationTime=1s
```

The `jmh` task is always out of date by design. Repeating the command reruns the
benchmark and removes the previous JSON and human reports before execution; it
never treats an old timing observation as a reusable build output.

## Reports and reproducibility

- `benchmarks/jmh/build/reports/jmh/results.json`
- `benchmarks/jmh/build/reports/jmh/human.txt`
- `benchmarks/jmh/build/reports/jmh/run-manifest.txt`

JSON is the required machine-readable result. The human report is supplemental.
The run manifest records the selected matrix and SHA-256 fingerprints for every
DelosDB runtime jar and benchmark-build input.

After execution, the Gradle task verifies that every selected benchmark contains
one unique result for every requested provider/row/payload/commit-batch case,
that JDK 25 was used, and that every primary score is finite.

## Supported properties

- `delosdb.jmh.includes` — one regular expression; quote it in zsh when it contains `*`
- `delosdb.jmh.providers` — comma-separated `heap,mvcc`
- `delosdb.jmh.rows` — comma-separated positive values
- `delosdb.jmh.payloadSizes` — comma-separated positive values
- `delosdb.jmh.commitBatchSizes` — comma-separated positive values no greater than the smallest row count
- `delosdb.jmh.warmupIterations`
- `delosdb.jmh.iterations`
- `delosdb.jmh.warmupTime`
- `delosdb.jmh.iterationTime`
- `delosdb.jmh.forks`
- `delosdb.jmh.timeout`
- `delosdb.jmh.verbosity`
- `delosdb.jmh.profilers` — comma-separated profiler names
- `delosdb.jmh.allocationJfr` — enables the DelosDB allocation-class/site JFR post-processor; requires one fork
- `delosdb.jmh.executionJfr` — enables the DelosDB execution-site JFR post-processor; requires one fork
- `delosdb.jmh.jvmArgs` — separate multiple arguments with `;;`, preserving commas inside an argument

The build pins JMH `1.37` and the Gradle JMH plugin `0.7.3`. JMH 1.37
still uses terminally deprecated `sun.misc.Unsafe` field-offset access internally.
The standalone benchmark launcher therefore opts its JMH runner and forks into
JDK 25 compatibility mode with `--sun-misc-unsafe-memory-access=allow`; DelosDB
runtime processes outside this benchmark build are unaffected.

## Concurrent reader-writer benchmark

The standalone build also includes a coordinated public-JDBC concurrency runner.
The historical task name is retained so existing scripts remain valid:

```bash
./gradlew -p benchmarks/jmh runConcurrentCommitBenchmark
```

The runner compares heap and MVCC under one equivalent writer and reader matrix.
Every scenario now uses the same seeded fixture shape, including readerless
controls. This makes writer-only, primary-reader, and retained-snapshot results
directly comparable instead of changing table cardinality when readers are added.
For different-table and different-database topologies, one run also fixes the
resource capacity across all generated scenarios.

Workers join one start barrier. Writer transactions use fixed operation counts.
Measured readers run for a fixed wall-clock duration in every measurement round,
so primary and retained-snapshot workloads exert pressure for the same period
instead of completing different fixed read counts. Warmup readers still use a
bounded fixed count. Multiple measurement rounds are aggregated into one result,
providing enough writer and reader samples for useful tail percentiles.

Reports publish reader throughput and latency separately from full writer-
transaction and commit latency, alongside JDK Flight Recorder evidence for file
writes, contended monitor entry, thread parking, and GC pauses. Round, writer,
reader, overlap duration, and overlap ratios show how much of each worker group's
execution actually ran concurrently.

Reader workloads are deliberately stable while writers run:

- `primary` reads one seeded primary key;
- `secondary` reads one seeded category through `(owner_id, id)`;
- `range` reads a bounded seeded primary-key range;
- `retained-snapshot` establishes a repeatable-read snapshot before the common
  start barrier and verifies that the selected value remains unchanged for the
  configured reader measurement duration.

The retained-snapshot shape is most informative with `operation=update`: heap
may hold read locks while MVCC can preserve the old visible version. Insert
writers use keys and categories outside the reader fixture, so every reader has
a deterministic fingerprint. Update writers mutate only seeded `value` and
`payload` fields; reader primary, secondary, and range identities remain stable.

Every scenario verifies final row count, updated-row contents where applicable,
and a deterministic semantic checksum over all tables. The runner additionally
requires equivalent reader/provider controls to produce the same digest, requires
at least one read per reader in every measurement round, rejects reader scenarios
that do not sustain at least 80% of the configured duration, and rejects mixed
scenarios with zero measured overlap.

A focused same-table comparison is:

```bash
./gradlew -p benchmarks/jmh clean runConcurrentCommitBenchmark \
  -Pdelosdb.concurrentCommit.providers=heap,mvcc \
  -Pdelosdb.concurrentCommit.writers=1,4 \
  -Pdelosdb.concurrentCommit.readers=0,4 \
  -Pdelosdb.concurrentCommit.topologies=same-table \
  -Pdelosdb.concurrentCommit.operations=update \
  -Pdelosdb.concurrentCommit.readerWorkloads=primary,retained-snapshot \
  -Pdelosdb.concurrentCommit.rowsPerTransaction=1 \
  -Pdelosdb.concurrentCommit.transactionsPerWriter=20 \
  -Pdelosdb.concurrentCommit.warmupTransactionsPerWriter=20 \
  -Pdelosdb.concurrentCommit.measurementRounds=5 \
  -Pdelosdb.concurrentCommit.readerMeasurementMillis=250 \
  -Pdelosdb.concurrentCommit.readsPerReader=200 \
  -Pdelosdb.concurrentCommit.warmupReadsPerReader=200 \
  -Pdelosdb.concurrentCommit.keepJfr=false
```

That command produces 12 unique scenarios and aggregates five measurement
rounds per scenario. Readerless cases are deduplicated across configured reader
workloads. Writerless cases are likewise deduplicated across configured
operations and row-batch sizes.

Configuration uses Gradle properties:

```text
delosdb.concurrentCommit.providers
delosdb.concurrentCommit.writers
delosdb.concurrentCommit.readers
delosdb.concurrentCommit.topologies
delosdb.concurrentCommit.operations
delosdb.concurrentCommit.readerWorkloads
delosdb.concurrentCommit.rowsPerTransaction
delosdb.concurrentCommit.transactionsPerWriter
delosdb.concurrentCommit.warmupTransactionsPerWriter
delosdb.concurrentCommit.measurementRounds
delosdb.concurrentCommit.readerMeasurementMillis
delosdb.concurrentCommit.readsPerReader
delosdb.concurrentCommit.warmupReadsPerReader
delosdb.concurrentCommit.databaseRoot
delosdb.concurrentCommit.keepJfr
```

`readsPerReader` is the fixed warmup count and the initial capacity for measured
latency storage. Measured read count is determined by
`readerMeasurementMillis`. `writers` and `readers` accept zero, but each
generated scenario must contain at least one worker. The bounded defaults cover
heap and MVCC, writer-only controls, and mixed readers and writers. Use explicit
properties for larger matrices.

Reports are written under `build/reports/concurrent-commit`:

- `results.csv`
- `results.json`
- `human.txt`
- `run-manifest.txt`
- optional per-scenario `.jfr` recordings

The benchmark is external validation. It is not part of the root build, root
`check`, or S0. The standalone benchmark build's own `check` task compiles the
runner and verifies that it uses only public JDBC and JDK APIs.
