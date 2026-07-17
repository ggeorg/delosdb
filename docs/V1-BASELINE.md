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

The first capture status is `CAPTURED_NOT_ACCEPTED`. The reviewed Phase 8.6 capture has semantic
checksum:

```text
e4103b829c3a4b9952507fa837b9187bc9deff872fe19d8daf0a76e99e2f6b17
```

Promotion is explicit and one-way:

```bash
./gradlew :delosdb-tests:promoteDelosV1Baseline --console=plain
```

The promotion task requires:

```text
the capture manifest reports a clean source tree
the capture source revision is an ancestor of the promotion checkout
the complete checksum inventory is valid
the capture used JDK 25
the fixed matrix and lane inventory are complete
the semantic checksum matches benchmarks/v1-baseline/acceptance-candidate.json
no accepted bundle already exists
```

It writes the tracked immutable bundle under:

```text
benchmarks/v1-baseline/accepted/
```

The accepted manifest status is `ACCEPTED_V1`. `delosV1AcceptedBaselineStaticAnalysis` verifies the
review policy, fixed matrix, lane inventory, source cleanliness, semantic checksum, runtime
fingerprints, and every evidence-file checksum. That verification is an S0 dependency; the
machine-specific capture and promotion tasks remain opt-in.

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


## Immutability

The promotion task refuses to overwrite `benchmarks/v1-baseline/accepted/`. A future baseline must
use a new baseline identifier and an explicit review policy rather than replacing v1 evidence in
place. Later comparisons may add derived reports, but they must not modify accepted raw files,
manifest metadata, or checksums.
