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
scenario/run records successful RawStore page writes and groups them by `(segment, container, page)`.
The topology report separates total writes, distinct pages, repeated rewrites, and bytes by heap table/
B-tree or MVCC database metadata, metadata/directory, version storage, ordered-index directory/B-tree, and
other containers. Recorder overflow is a hard failure, so truncated topology evidence is never accepted.
Semantic verification and any restoration needed by the two-transaction rollback shape remain outside
the timed phases. The results are diagnostic evidence, not an S0 threshold.

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
DelosDB MVCC, upstream Derby, and H2 in isolated child JVMs. Benchmark order follows a four-run
orthogonal cycle across engine, row-count, and workload order (`NNN`, `NRR`, `RNR`, `RRN`), so every
pair of order dimensions sees all four combinations once per cycle. The benchmark therefore requires
a run count that is a multiple of four (default `4`). Semantic fingerprints must match across every engine and run before reports are accepted.

The raw `cross-engine-results.csv` remains authoritative evidence. `cross-engine-ratios.csv` reports median
latency ratios, while `cross-engine-dispersion.csv` reports median, quartiles, IQR, MAD, min/max, and
normalized robust spread for every engine/workload shape. Use the dispersion report before treating small
ratio differences as meaningful.
