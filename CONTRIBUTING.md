# Contributing to DelosDB

DelosDB is in a proof-driven modernization phase. The A44--A52 MVCC semantic
correctness sprint is closed; choose the next post-A52 lane deliberately before
opening new work.

## Supported local workflow

Use the checked-in Gradle Wrapper from the repository root:

```bash
./gradlew build
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

For the broader gate:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

If a Derby test run was interrupted, run `./gradlew clean` before retrying.

## Current MVCC workflow

MVCC changes must move in small executable proofs. Current high-value gates:

```bash
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew mvccKernelReviewCloseoutProof
./gradlew mvccHistoryPrunedSafetyProof
./gradlew mvccVacuumWatermarkProof
./gradlew mvccCommandSequenceProof
./gradlew mvccStatementSnapshotVisibilityProof
./gradlew mvccSqlStatementBoundarySmoke
./gradlew mvccTransactionOutcomeLogProof
./gradlew mvccUnresolvedOutcomeRecoveryProof
./gradlew mvccCapturedVisibilitySnapshotProof
./gradlew mvccSqlCompatibilityCandidate
```

The post-A52 mission state and next-lane decision point are in
`docs/MVCC-MISSION.md`.

## Contribution rules

- Keep changes focused and source-backed.
- Add or update a smoke/proof when behavior changes.
- Preserve Derby-compatible heap behavior by default.
- Do not flip the global default store to `delos_mvcc`.
- Do not start a new provider family before the post-A52 lane is selected.
- Do not add a research platform subsystem without a separate post-A52 decision;
  proof-level observability remains allowed.
- Update documentation after the code proof or planning decision is real.
- Do not add stale checkpoint documents; update the existing roadmap/status docs
  or delete obsolete docs through an explicit cleanup script.
- Do not remove Apache license headers or attribution.
- Do not use Apache Derby branding for modified DelosDB distributions.

## Workspace metadata

Developer workspaces may contain `.git/`, `.gradle/`, and `.idea/`. Cleanup
scripts must not delete them. Overlay ZIPs must not include them.

## Style

Preserve inherited Derby style unless the cleanup is deliberate and
behavior-preserving. Prefer small verified changes over mechanical rewrites.
