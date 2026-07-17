# DelosDB performance and concurrency validation

Performance evidence is split into independent lanes. No wall-clock benchmark,
JMH task, external stress tool, or long-running workload is an S0 dependency.

## Deterministic invariant proofs

The MVCC module keeps fast, no-dependency correctness proofs for structures that
performance work depends on:

- `MvccBufferWorkloadInvariantTest` verifies dirty-page writes,
  WAL-before-flush checks, grouped force accounting, and warm-cache hits;
- `MvccConcurrencyValidationTest` stress-validates pin/unpin balancing and
  dirty-page flush discipline;
- `MvccLongReaderBufferPressureValidationTest` proves that a long-reader pin
  survives cache pressure until it is released.

These are not wall-clock benchmarks. They assert counters and invariants so the
results remain deterministic across machines.

Run the buffer-workload proof directly:

```bash
./gradlew :delosdb-storage-mvcc:runDelosMvccBufferWorkloadInvariantTest
```

The historical task name remains as a compatibility alias:

```bash
./gradlew :delosdb-storage-mvcc:runDelosMvccMicrobenchmarkValidation
```

## JDBC/JUnit benchmark lanes

The root test module owns deterministic report-producing JDBC workloads:

```text
runDelosJdbcBenchmarkBaseline
runDelosJdbcBenchmarkBatchScaling
runDelosJdbcBenchmarkTransactions
runDelosJdbcBenchmarkRowScaling
```

They provide phase isolation, explicit transaction shapes, adaptive row-scaling
budgets, semantic fingerprints, CSV/JSON summaries, and heap/MVCC comparison.
They are opt-in and are not correctness gates.

The MVCC module also owns implementation-local deterministic measurement lanes:

```text
runDelosMvccBufferCacheBenchmark
runDelosMvccPageCodecBenchmark
```

Those lanes may access package-local cache and codec implementation types. They
remain outside the standalone JMH build because exporting a benchmark-only
production SPI would weaken module boundaries without runtime value.

## Standalone JMH lane

Executable JMH sources live in the independent build under `benchmarks/jmh`.
The build consumes assembled runtime jars and uses only public JDBC, JDK, and
JMH APIs. The repository root does not include or evaluate this build.

```bash
./gradlew jars
./gradlew -p benchmarks/jmh clean check
./gradlew -p benchmarks/jmh clean jmh
```

The JMH matrix covers prepared point/index/range/full-scan/aggregate reads and
honest one-row read transactions ending in commit or rollback. Every measured
read checks a deterministic semantic fingerprint. Reports include JSON, human
output, and a run manifest containing SHA-256 fingerprints of runtime jars and
benchmark inputs.

The standalone lane is fixed to one thread. Concurrency claims belong to the
concurrency and jcstress lanes rather than multiple unrelated embedded database
fixtures.

## Stable root task slots

The following root tasks remain stable opt-in adapters:

```text
./gradlew delosJmhMicrobenchmarks
./gradlew delosJcstressConcurrencyValidation
./gradlew delosTwoSidedMvccWorkloadBenchmark
./gradlew delosLongReaderVacuumSoak
./gradlew delosSqlancerMvccValidation
```

`delosJmhMicrobenchmarks` runs the deterministic MVCC buffer-workload invariant
proof. A CI or release job may additionally supply a caller-owned command through
`-Pdelosdb.jmh.command`. The executable repository JMH build is normally invoked
directly with `./gradlew -p benchmarks/jmh jmh`; it is intentionally not wired
into the root build.

## Phase 8.6 accepted-baseline capture

The complete post-correction capture is owned by one opt-in task:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

It runs the fixed 1/2/4/8/16 writer matrix, existing JDBC and low-level benchmark lanes,
DRDA stress, recovery differential, and deterministic failure-replay evidence. It then copies
all raw output into one bundle and writes a versioned manifest, runtime-jar fingerprints, per-file
SHA-256 values, and an aggregate semantic checksum.

The task is deliberately not an S0 dependency. A capture with a dirty Git tree is marked as such
and cannot be promoted as the accepted baseline. See [`V1-BASELINE.md`](V1-BASELINE.md) and
`benchmarks/v1-baseline/README.md`.

## Interpretation rules

- Benchmark values are evidence, not correctness assertions.
- No performance threshold belongs in S0.
- A behavior change requires a separate correctness and compatibility proof.
- Compare identical JDK, JVM arguments, runtime-jar fingerprints, parameters,
  and benchmark source fingerprints.
- Smoke runs establish operability, not publishable confidence intervals.
- Large row-count experiments must use explicit bounded/adaptive lanes rather
  than accidental unbounded fixtures.
