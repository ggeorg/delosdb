# DelosDB performance and concurrency validation

Performance evidence is split into independent lanes. No wall-clock benchmark, external stress tool,
or long-running workload is an S0 dependency.

## Current deterministic baselines

The retained Phase 8 implementation-local harnesses were deleted after RawStore convergence. Stable
root adapters now run live production-path proofs before any optional external command:

```text
delosJmhMicrobenchmarks
    -> runDelosSharedRawStorePageIoRepresentationDecisionTest

delosJcstressConcurrencyValidation
    -> delosSystemTests --tests '*MvccDrdaConcurrentNetworkClientTest'

delosTwoSidedMvccWorkloadBenchmark
    -> delosFunctionalTests --tests '*HeapMvccDifferentialSqlHarnessTest'

delosLongReaderVacuumSoak
    -> delosStressTests --tests '*MvccSqlLongReaderPurgeStressTest'
```

These built-in tests verify deterministic state, live SQL/network behavior, concurrency, purge
horizons, and heap/MVCC equivalence. Timing evidence remains diagnostic and is never an S0 threshold.

## JDBC/JUnit benchmark lanes

The test module owns report-producing JDBC workloads:

```text
runDelosJdbcBenchmarkBaseline
runDelosJdbcBenchmarkBatchScaling
runDelosJdbcBenchmarkTransactions
runDelosJdbcDeleteReinsertAttribution
runDelosJdbcBenchmarkRowScaling
runDelosSharedRawStorePageIoRepresentationDecisionTest
```

The focused delete/reinsert attribution lane compares heap and MVCC across:

```text
same primary key vs different primary key
one transaction vs two transactions
commit vs rollback
```

It reports public-JDBC source-read, delete, insert, and transaction-end phase times together with
shared RawStore page-I/O and force deltas. After the timed samples, one untimed topology cycle per
scenario/run attributes page dirtiness by synchronously cleaning the RawStore page cache around each
phase while the existing RawStore I/O fault seam records successful physical page writes. The pre-phase
clean establishes a clean cache; the post-phase clean turns pages dirtied by that phase into deterministic
write evidence without contaminating the timed samples. The topology report separates total writes,
distinct pages, repeated rewrites, and bytes by heap table/B-tree or MVCC database metadata,
metadata/directory, version storage, ordered-index directory/B-tree, and other containers. RawStore counter
deltas must exactly match the recorded write count/bytes for every phase, recorder overflow is fatal, and
an all-zero topology report is rejected. Semantic verification and any restoration needed by the
two-transaction rollback shape remain outside the timed phases. The results are diagnostic evidence, not
an S0 threshold.

```bash
./gradlew :delosdb-tests:runDelosJdbcDeleteReinsertAttribution \
  -Pdelosdb.benchmark.deleteReinsert.rows=1000 \
  -Pdelosdb.benchmark.deleteReinsert.cycles=3 \
  -Pdelosdb.benchmark.deleteReinsert.warmups=1 \
  -Pdelosdb.benchmark.deleteReinsert.iterations=3 \
  -Pdelosdb.benchmark.deleteReinsert.runs=2 \
  --console=plain
```

Reports are written under:

```text
build/reports/delosdb/benchmarks/delete-reinsert
```

## Standalone JMH lane

Executable JMH sources live in the independent `benchmarks/jmh` build and consume assembled runtime
jars through public JDBC APIs only.

```bash
./gradlew jars
./gradlew -p benchmarks/jmh clean check
./gradlew -p benchmarks/jmh clean jmh
```

## Stable root task slots

```text
./gradlew delosJmhMicrobenchmarks
./gradlew delosJcstressConcurrencyValidation
./gradlew delosTwoSidedMvccWorkloadBenchmark
./gradlew delosLongReaderVacuumSoak
./gradlew delosSqlancerMvccValidation
```

Caller-owned external commands remain opt-in through the documented Gradle properties.

## V1 baseline capture

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

New captures use live Stage 8 page-I/O, fault-injection, and decision/WAL crash evidence instead of
archived-oracle tests. The accepted historical baseline remains immutable evidence.

## Interpretation rules

- Benchmark values are evidence, not correctness assertions.
- No performance threshold belongs in S0.
- Compare identical JDK, JVM arguments, runtime artifacts, and parameters.
- A behavior change requires a separate correctness and compatibility proof.

## Cross-engine JDBC comparison quality controls

`./gradlew :delosdb-tests:runDelosJdbcCrossEngineComparison -Pdelosdb.sane=false` runs DelosDB heap,
DelosDB MVCC, upstream Derby, H2, and SQLite through Xerial SQLite JDBC in isolated child JVMs. Benchmark
order retains the four-run orthogonal cycle across engine, row-count, and workload order (`NNN`, `NRR`,
`RNR`, `RRN`), so every pair of order dimensions sees all four combinations once per cycle. The benchmark
therefore requires a run count that is a multiple of four (default `4`). Semantic fingerprints must match
across every engine and run before reports are accepted.

SQLite uses a persistent file with WAL, `synchronous=FULL`, a 3000 ms busy timeout, and a JDBC
`READ_COMMITTED` isolation request. It is reported as
`native SQLite through JDBC`; DelosDB/SQLite ratios compare products under the same logical workload and
are not JVM architectural-equivalence thresholds.

The raw `cross-engine-results.csv` remains authoritative evidence. `cross-engine-ratios.csv` reports median
latency ratios against Derby, H2, and SQLite, while `cross-engine-dispersion.csv` reports median, quartiles,
IQR, MAD, min/max, sample count, and normalized robust spread for every engine/workload shape. Use the
dispersion report before treating small ratio differences as meaningful.

`runDelosJdbcCrossEngineConcurrencyComparison` uses the same five embedded targets. Primary-key reads are
split explicitly into `PRIMARY_KEY_READ_HOT`, `PRIMARY_KEY_READ_DISJOINT`, and
`PRIMARY_KEY_READ_RANDOM`: the hot shape sends every client to id=1, the disjoint shape assigns one evenly
spaced private id per client, and the random shape replays a deterministic precomputed key stream across
the fixture. Key generation occurs outside the timed interval. The current write workloads remain
`DISJOINT_INDEXED_UPDATE` and `CONTENDED_INDEXED_UPDATE`; broader read/write mixes are roadmap work, not
silently claimed as already implemented.

Concurrency results report both transactions/second and operations/second. The aggregate value
`elapsedNanos / measuredTransactions` is reported as `inverseThroughputNanosPerTransaction`; it is the
reciprocal of aggregate throughput and is not an observed client transaction latency. p50/p95/p99 remain
reserved for later per-client latency sampling.
`cross-engine-concurrency-capabilities.csv` records the execution model for every configured target/workload.
SQLite BUSY/LOCKED retries are counted with retryable conflict retries, so its single-writer architecture
remains visible in the result rather than being hidden by the harness.
