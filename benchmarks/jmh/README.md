# DelosDB standalone JMH build

This directory is an independent Gradle build. It is deliberately absent from
the repository root `settings.gradle`, normal `check`, S0, and runtime module
configuration.

The benchmark consumes assembled DelosDB runtime jars and exercises only public
JDBC behavior. It does not import package-private MVCC classes, Derby
implementation packages, root test source sets, or a benchmark-only production
API.

The benchmark matrix contains:

- prepared primary-key lookup;
- prepared secondary-index equality lookup;
- prepared composite range scan;
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
- `delosdb.jmh.jvmArgs` — separate multiple arguments with `;;`, preserving commas inside an argument

The build pins JMH `1.37` and the Gradle JMH plugin `0.7.3`. JMH 1.37
still uses terminally deprecated `sun.misc.Unsafe` field-offset access internally.
The standalone benchmark launcher therefore opts its JMH runner and forks into
JDK 25 compatibility mode with `--sun-misc-unsafe-memory-access=allow`; DelosDB
runtime processes outside this benchmark build are unaffected.

## Concurrent commit benchmark

The standalone build also includes a public-JDBC concurrency runner:

```bash
./gradlew -p benchmarks/jmh runConcurrentCommitBenchmark
```

It measures commit throughput and p50/p95/p99 latency for same-table,
different-table, and different-database writers. The runner records current
JDK Flight Recorder events for file writes, contended monitor entry, thread
parking, and GC pauses without importing DelosDB implementation APIs. It also
verifies insert row counts and the final contents of every updated fixture row.
Reported throughput includes the cost of this bounded JFR recording.

Configuration uses Gradle properties:

```text
delosdb.concurrentCommit.writers
delosdb.concurrentCommit.topologies
delosdb.concurrentCommit.operations
delosdb.concurrentCommit.rowsPerTransaction
delosdb.concurrentCommit.transactionsPerWriter
delosdb.concurrentCommit.warmupTransactionsPerWriter
delosdb.concurrentCommit.databaseRoot
delosdb.concurrentCommit.keepJfr
```

Reports are written under `build/reports/concurrent-commit`. The benchmark is
external validation: it is not part of the root build, the root `check`, or S0.
The standalone benchmark build's own `check` task compiles this runner and
verifies that it uses only the public JDBC boundary.
