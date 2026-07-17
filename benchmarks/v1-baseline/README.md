# DelosDB v1 baseline evidence

This directory owns the tracked contract for the post-correction v1 performance and resource
baseline. Machine-specific results are not handwritten or inferred from developer logs.

Run the complete capture from a clean JDK 25 checkout:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The command writes one self-contained bundle to:

```text
build/reports/delosdb/v1-baseline/capture/
```

The bundle contains:

```text
manifest.json
checksums.sha256
provisional-thresholds.json
raw/operational/
raw/jdbc-lifecycle/
raw/jdbc-batch-scaling/
raw/jdbc-transactions/
raw/jdbc-row-scaling/
raw/mvcc-buffer-cache/
raw/mvcc-page-codec/
raw/drda-stress/
raw/recovery-differential/
raw/failure-replay/
raw/low-level-failure-replay/
```

`manifest.json` records the source revision, dirty state, environment, runtime-jar hashes, fixed
matrix, lane inventory, raw-file hashes, and one semantic checksum. A capture with a dirty source
tree is useful for local diagnosis but cannot become the accepted baseline.

The tracked `provisional-thresholds.json` is a comparison policy, not a performance claim. Timing
or resource changes do not fail S0 automatically. Semantic mismatches always block acceptance;
material timing or resource regressions require an explanation or correction before a later phase
can claim improvement against v1.

The reviewed capture is pinned by `acceptance-candidate.json`, including semantic checksum:

```text
e4103b829c3a4b9952507fa837b9187bc9deff872fe19d8daf0a76e99e2f6b17
```

Promote it once with:

```bash
./gradlew :delosdb-tests:promoteDelosV1Baseline --console=plain
```

The task validates the capture checksum inventory, clean-source marker, JDK 25 environment, source
ancestry, fixed lane contract, and reviewed semantic checksum before writing:

```text
benchmarks/v1-baseline/accepted/
```

The accepted directory is immutable. Promotion refuses to overwrite it. S0 runs
`delosV1AcceptedBaselineStaticAnalysis`, which verifies every accepted evidence checksum and the
reviewed manifest contract without rerunning machine-specific benchmarks.
