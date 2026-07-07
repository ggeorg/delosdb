# DelosDB Performance and Concurrency Validation

Phase P turns the engine-depth roadmap into measurable validation without adding
slow or nondeterministic work to `s0CloseoutVerification`.

No Phase P benchmark task is an S0 dependency. S0 only verifies that the harness,
task slots, and documentation remain present.

## Built-in no-dependency validation harnesses

The first Phase P slice adds deterministic JVM/JUnit harnesses in
`delosdb-storage-mvcc`:

* `MvccPerformanceValidationTest` records operation counters for a two-sided
  MVCC workload: dirty page writes, WAL-before-flush checks, grouped force
  batches, and warm read-path cache hits.
* `MvccConcurrencyValidationTest` stress-validates pin/unpin balancing and
  dirty-page flush discipline under concurrent readers/writers.
* `MvccLongReaderBufferPressureValidationTest` holds a long-reader pin while
  the cache experiences buffer pressure, proving the low-level pin invariant
  behind long-reader-vs-vacuum validation.

These harnesses intentionally avoid wall-clock assertions. They check counters
and invariants so results stay deterministic across developer machines.

## Root validation task slots

The following root tasks are available but are not wired into S0:

```text
./gradlew delosJmhMicrobenchmarks
./gradlew delosJcstressConcurrencyValidation
./gradlew delosTwoSidedMvccWorkloadBenchmark
./gradlew delosLongReaderVacuumSoak
./gradlew delosSqlancerMvccValidation
```

Current mapping:

* `delosJmhMicrobenchmarks` runs the built-in deterministic microbenchmark
  harness. A later slice may replace or wrap it with real JMH once the
  dependency policy is accepted.
* `delosJcstressConcurrencyValidation` runs the built-in concurrency harness.
  A later slice may add real jcstress once the dependency policy is accepted.
* `delosTwoSidedMvccWorkloadBenchmark` runs the same two-sided workload harness
  as the current no-dependency benchmark baseline.
* `delosLongReaderVacuumSoak` runs the built-in long-reader buffer-pressure
  harness. Full SQL long-reader-vs-vacuum soak remains a future external task.
* `delosSqlancerMvccValidation` is an explicit external-validation slot. It is
  not a fake SQLancer run and it is not a success marker by itself.

## External validation policy

Real JMH, jcstress, and SQLancer integration should be added only when their
version/dependency policy is accepted and the tasks can be kept outside S0.
Until then, the built-in harnesses provide deterministic signal for MVCC
buffer/cache behavior and operation counts.

## What this phase does not claim

This phase does not claim published performance numbers. It creates repeatable
validation hooks and no-dependency baseline harnesses so later benchmark work can
produce comparable reports without changing runtime behavior.
