# DelosDB v1 baseline evidence

## Purpose

Machine-specific performance and resource measurements are evidence, not correctness gates. DelosDB
keeps deterministic semantic checks separate from captured timing and environment data.

## Historical accepted bundle

The tracked accepted bundle remains under:

```text
benchmarks/v1-baseline/accepted/
```

It is immutable historical evidence. Normal permanent verification does not rerun the measurements or parse documentation
about them.

## Capture

Create a new self-contained capture from a clean JDK 25 checkout:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The task runs the configured operational, JDBC, DRDA, modular-image, RawStore fault, decision/WAL,
and page-I/O evidence lanes. It writes:

```text
build/reports/delosdb/v1-baseline/capture/manifest.json
build/reports/delosdb/v1-baseline/capture/checksums.sha256
build/reports/delosdb/v1-baseline/capture/raw/
```

Every new capture starts with:

```text
CAPTURED_NOT_ACCEPTED
```

The manifest records source revision and cleanliness, JDK/Gradle/OS environment, semantic checksum,
runtime artifact fingerprints, lane inventory, and individual evidence-file checksums.

## Promotion

Promotion is explicit and reviewed:

```bash
./gradlew :delosdb-tests:promoteDelosV1Baseline --console=plain
```

Promotion requires:

- a complete capture bundle;
- a checked acceptance candidate;
- the expected baseline ID and semantic checksum;
- a clean source tree;
- a valid source revision that is an ancestor of the current checkout;
- JDK 25;
- runtime artifact and semantic evidence;
- an empty accepted destination.

The task will not overwrite an existing accepted baseline.

## Interpretation

Timing changes are meaningful only against a comparable environment and workload. A faster result
does not prove correctness, and a slower result does not automatically indicate a regression.
Semantic checksums, SQL behavior, recovery outcomes, and resource invariants remain the primary
acceptance criteria.
