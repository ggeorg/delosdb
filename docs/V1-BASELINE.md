# DelosDB v1 baseline evidence

## Purpose

DelosDB keeps accepted machine-specific evidence separate from deterministic correctness gates.
The tracked accepted bundle remains the reviewed historical Phase 8.6 reference. New production-
closeout captures use only live RawStore-backed Stage 8 lanes; they do not execute the deleted Phase 8
oracle.

Benchmark evidence does not replace correctness tests. Semantic checksums must remain stable, and
material timing or resource changes require explanation against a comparable environment.

## Historical accepted bundle

The immutable accepted baseline is:

```text
baseline ID        phase8-v1-post-correction
status             ACCEPTED_V1
semantic checksum  e4103b829c3a4b9952507fa837b9187bc9deff872fe19d8daf0a76e99e2f6b17
location           benchmarks/v1-baseline/accepted/
```

`delosV1AcceptedBaselineStaticAnalysis` verifies its manifest, policy, lane inventory, runtime
fingerprints, and every tracked evidence-file checksum during S0. It is never overwritten.

## Production-closeout capture

From a clean checkout on JDK 25, run:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The new capture uses the distinct identity:

```text
phase8-v1-production-closeout
```

It writes:

```text
build/reports/delosdb/v1-baseline/capture/manifest.json
build/reports/delosdb/v1-baseline/capture/checksums.sha256
build/reports/delosdb/v1-baseline/capture/raw/
```

Its initial status is `CAPTURED_NOT_ACCEPTED`. The capture task does not replace or promote the
historical accepted bundle. A reviewed production-closeout result requires a separate acceptance
candidate and a new immutable destination.

## One-command Phase 8 closeout

After committing the intended source changes, run from a clean JDK 25 checkout:

```bash
./gradlew :delosdb-tests:verifyDelosV1ProductionCloseout --console=plain
```

This task runs the normal module gates, S0, the authoritative production-closeout capture, and an
objective review of the generated evidence. The review verifies:

```text
clean source revision and JDK 25 environment
complete checksums and exact manifest file inventory
current runtime-artifact fingerprints
fixed writer matrix and complete lane inventory
32 raw decision-force and 32 participant-publication samples
real jlink/JPMS DRDA server and client execution
successful DRDA stress, RawStore fault-injection, and decision/WAL crash XML evidence
immutability and checksums of the historical accepted baseline
```

Review output is written to:

```text
build/reports/delosdb/v1-baseline/production-closeout-review/review.json
build/reports/delosdb/v1-baseline/production-closeout-review/review.txt
```

A successful review reports `REVIEWED_NOT_ACCEPTED`. It does not mutate the capture manifest,
promote the candidate, or overwrite the historical accepted baseline. Promotion still requires
three comparable captures and a separately pinned candidate under the tracked policy.

## Fixed operational matrix

Both heap and `delos_mvcc` are measured across:

```text
writers       1, 2, 4, 8, 16
topologies    same table, different tables, different databases
workloads     single-row insert, multi-row insert, mixed insert/update
```

Each matrix cell records:

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

## Split commit-decision evidence

The production-closeout operational lane also runs a fixed mixed heap/MVCC transaction set and
records two independent intervals:

```text
raw decision force
    only the synchronous Derby log force that makes the transaction decision durable

participant publication
    only MVCC participant publication after the durable raw-store decision
```

For each interval the capture records sample count, average nanoseconds, and maximum nanoseconds.
The semantic checksum includes the resulting rows, but not machine-specific timing values.

## Modular-image DRDA lane

The capture builds a custom JDK runtime with `jlink`, copies the assembled DelosDB runtime modules
into its `app-modules` directory, and launches both of these from that image through the JPMS module
path:

```text
org.apache.derby.server/org.apache.derby.drda.NetworkServerControl
org.apache.derby.tools/org.apache.derby.tools.ij
```

The lane starts a real DRDA server, creates heap and MVCC tables through the network client, checks
the joined result and semantic marker, and records:

```text
runtime-image kind and size
application-jar count
resolved-module count
server-start latency
network-client round-trip latency
semantic digest
server and client raw output
```

This is a real `jlink` JDK runtime plus modular DelosDB application launch. DelosDB application
modules remain external to the JDK image because the optional MVCC provider is an automatic module;
the lane does not falsely claim that automatic modules were linked into the JDK image itself.

## Complete evidence lanes

The production-closeout bundle includes:

```text
operational writer/lifecycle/backup/default-overhead evidence
split raw decision-force and participant-publication evidence
JDBC lifecycle, batch, transaction, and row scaling
page-I/O representation decision evidence
shared RawStore deterministic fault-injection evidence
RawStore-backed MVCC decision/WAL crash evidence
DRDA concurrent-client stress
jlink modular-image DRDA execution
runtime artifact SHA-256 fingerprints
```

## Manifest and checksums

The capture manifest records:

```text
baseline identifier and CAPTURED_NOT_ACCEPTED status
Git revision and dirty state
JDK, VM, OS, architecture, processor count, heap limit, and Gradle version
fixed matrix and lane inventory
runtime-jar names, sizes, and SHA-256 fingerprints
raw evidence file sizes and SHA-256 fingerprints
semantic token inventory and aggregate semantic checksum
normal profiling and fault-control defaults
```

`checksums.sha256` covers every raw evidence file and policy copy in the capture bundle.

## Acceptance rules

The existing task:

```bash
./gradlew :delosdb-tests:promoteDelosV1Baseline --console=plain
```

belongs only to the already reviewed `phase8-v1-post-correction` candidate and refuses to overwrite
its accepted directory. Do not use it to promote `phase8-v1-production-closeout`.

A future production-closeout promotion must:

```text
pin the reviewed new semantic checksum
require a clean JDK 25 capture
validate source ancestry and the complete checksum inventory
require the modular-image and split-timing lanes
write a new immutable accepted directory
leave benchmarks/v1-baseline/accepted/ unchanged
```

## Provisional thresholds

The comparison policy is tracked at:

```text
benchmarks/v1-baseline/provisional-thresholds.json
```

Thresholds classify warning and material changes; noisy wall-clock values do not become automatic
S0 failures. Semantic mismatches always block acceptance.

## Interpretation

Comparisons are valid only when source, JDK major, OS architecture, runtime artifacts, and matrix
are compatible. Raw results must be retained. A successful modular-image lane proves executable
JPMS deployment for that image; it does not by itself establish all operating-system distribution
or packaging claims.
