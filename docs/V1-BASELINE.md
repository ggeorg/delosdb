# DelosDB v1 frozen baseline

## Purpose

The v1 baseline is the post-correction reference point for later storage, concurrency, optimizer,
resource, and research work. It is captured only after the Phase 8 ownership, transaction,
failure-replay, isolation, type, and security gates are green.

The baseline is evidence, not a benchmark contest. Later phases must preserve semantic checksums
and explain material changes against the same environment and matrix.

## Authoritative command

From a clean checkout on JDK 25:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The command is opt-in and intentionally outside `check` and `s0CloseoutVerification`. Wall-clock
and resource measurements are machine-specific; deterministic correctness remains owned by normal
verification gates.

## Fixed matrix

The operational capture uses both heap and `delos_mvcc` across:

```text
writers       1, 2, 4, 8, 16
topologies    same table, different tables, different databases
workloads     single-row insert, multi-row insert, mixed insert/update
```

Each cell records:

```text
elapsed time
commit p50, p95, average, and maximum latency
complete transaction p50, p95, and average latency
logical row count
physical database bytes
heap-memory high-water mark
thread-count high-water mark
semantic digest
```

## Additional lanes

The same capture bundle includes:

```text
prepared-statement lifecycle measurements
execution-batch scaling
complete transaction-cycle measurements
row-count scaling
MVCC buffer/cache measurements and durability-force counts
MVCC page-codec measurements
clean startup, query, and unclean-recovery latency
mixed backup duration and writer commit stall
DRDA concurrent-client evidence
heap/MVCC recovery differential evidence
transaction and low-level deterministic failure replay
runtime artifact SHA-256 fingerprints
profiling-disabled versus runtime-statistics-enabled query timing
proof that destructive failure controls are unreachable to normal applications
```

The DRDA and runtime-provider lanes use the assembled modular runtime jars. A later distribution
image may package those same modules, but Phase 8.6 does not introduce a second runtime or a
benchmark-only product image.

## Manifest and checksums

The capture writes:

```text
build/reports/delosdb/v1-baseline/capture/manifest.json
build/reports/delosdb/v1-baseline/capture/checksums.sha256
```

The manifest schema records:

```text
baseline identifier and status
Git source revision and dirty state
JDK, VM, OS, architecture, processor count, heap limit, and Gradle version
fixed workload matrix
captured lane inventory
runtime-jar hashes
raw evidence file sizes and hashes
semantic token count and aggregate semantic checksum
normal profiling and fault-control defaults
```

The first capture status is `CAPTURED_NOT_ACCEPTED`. Results become the accepted frozen baseline
only after:

```text
the working tree is clean
the complete command is green
all raw files and checksums are present
semantic checksums are reviewed
no material regression is unexplained
the reviewed capture bundle is promoted into tracked baseline evidence
```

## Provisional thresholds

The comparison policy is tracked at:

```text
benchmarks/v1-baseline/provisional-thresholds.json
```

Thresholds are provisional until at least three comparable captures exist. They classify warning
and material changes; they do not silently convert noisy wall-clock evidence into an S0 correctness
failure. A semantic mismatch always blocks acceptance.

## Interpretation

Comparisons are valid only when source, JDK major, OS architecture, runtime-jar hashes, and matrix
are compatible. A smoke run proves operability, not confidence. Published analysis must retain raw
results and state any nondeterministic envelope rather than selecting favorable values.
