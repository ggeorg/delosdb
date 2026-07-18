# DelosDB v1 baseline evidence

This directory owns reviewed baseline policy and immutable evidence. Machine-specific results are
created only by repository tasks; they are never handwritten from console logs.

## Accepted historical baseline

The tracked directory:

```text
benchmarks/v1-baseline/accepted/
```

contains the immutable `phase8-v1-post-correction` evidence with semantic checksum:

```text
e4103b829c3a4b9952507fa837b9187bc9deff872fe19d8daf0a76e99e2f6b17
```

S0 verifies all accepted checksums through `delosV1AcceptedBaselineStaticAnalysis`.

## Production-closeout capture

Run from a clean JDK 25 checkout:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The command writes a self-contained `phase8-v1-production-closeout` bundle to:

```text
build/reports/delosdb/v1-baseline/capture/
```

The raw lanes include:

```text
operational/
jdbc-lifecycle/
jdbc-batch-scaling/
jdbc-transactions/
jdbc-row-scaling/
mvcc-buffer-cache/
mvcc-page-codec/
drda-stress/
modular-image-drda/
recovery-differential/
failure-replay/
low-level-failure-replay/
```

The operational lane now reports raw-store decision-force and MVCC participant-publication timing
separately. The modular-image lane builds a `jlink` runtime and launches a real DRDA server and
network client from its JPMS module path.

The manifest records source state, environment, runtime-artifact hashes, fixed matrix, lane
inventory, per-file hashes, and an aggregate semantic checksum. Its status remains
`CAPTURED_NOT_ACCEPTED` until a separate review.

The existing `acceptance-candidate.json`, `promoteDelosV1Baseline` task, and `accepted/` directory
belong to the historical baseline. They must not be reused or overwritten for the production-
closeout capture. A new reviewed checksum requires a new candidate and immutable destination.

`provisional-thresholds.json` is a comparison policy, not a performance claim. Semantic mismatches
always block acceptance; material timing or resource changes require explanation rather than an
automatic noisy S0 failure.
