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

No build gate verifies the accepted files. Check them against their recorded hashes from that
directory:

```bash
shasum -a 256 -c checksums.sha256
```

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
page-io-representation/
rawstore-fault-injection/
rawstore-decision-wal-crash/
drda-stress/
modular-image-drda/
```

The operational lane measures the 1/2/4/8/16 writer matrix across same-table, different-table and
different-database topologies, the provider lifecycle, a backup stall and profiling overhead. The
accepted baseline also timed the commit decision and its publication to the external MVCC
participant; that participant was retired, so current captures have no such measurement. The three
Stage 8 storage lanes use the live page-representation decision, deterministic
fault-injection, and decision/WAL crash proofs. The modular-image lane builds a `jlink` runtime and
launches a real DRDA server and network client from its JPMS module path.

The manifest records source state, environment, runtime-artifact hashes, fixed matrix, lane
inventory, per-file hashes, and an aggregate semantic checksum. Its status remains
`CAPTURED_NOT_ACCEPTED` until a separate review.

Reviewing a capture and accepting it are manual decisions; no task reviews or accepts a bundle.

The existing `acceptance-candidate.json`, `promoteDelosV1Baseline` task, and `accepted/` directory
belong to the historical baseline. They must not be reused or overwritten for the production-
closeout capture. A new reviewed checksum requires a new candidate and immutable destination.

`provisional-thresholds.json` is a comparison policy, not a performance claim. Semantic mismatches
always block acceptance; material timing or resource changes require explanation rather than an
automatic noisy S0 failure.
