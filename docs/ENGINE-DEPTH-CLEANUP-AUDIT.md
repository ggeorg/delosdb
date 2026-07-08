# DelosDB engine-depth cleanup and consolidation audit

This audit follows the closed engine-depth roadmap and the post-closeout S0 cleanup.
It is intentionally not a feature plan and not a roadmap-prose gate. It records
where the implementation should be simplified next without changing storage
behavior.

## Source baseline

The audit was performed against the final engine-depth tree reconstructed from
`delosdb 25.zip` plus the green post-25 overlays through the obsolete-roadmap-gate
cleanup.

The final verification state reported by the local tree is:

- `s0CloseoutVerification` is green after removing the one-shot cleanup script.
- MVCC microbenchmark validation is green.
- MVCC concurrency validation is green.
- MVCC long-reader validation is green.
- MVCC SQL integration is green.
- `:delosdb-storage-derby:check` is green.
- `:delosdb-storage-mvcc:check` is green.

## Static-gate cleanup status

S0 is now back to stable engineering checks only. The obsolete roadmap/prose-only
Gradle tasks are no longer registered, and no `cleanup-overlay-*` scripts remain
in `scripts/`.

Current S0 dependency shape:

- `delosStorageStaticAnalysis`
- `delosServerStaticAnalysis`
- `delosHeapCompatibilityStaticAnalysis`
- `delosHeapObjectDeserializationFilterStaticAnalysis`
- `delosStaleGradleScriptCheck`
- `delosOverlayCleanupScriptStaticAnalysis`
- `delosRuntimeArtifactModelStaticAnalysis`
- `delosModuleDependencyBoundaryStaticAnalysis`
- `delosWorkspaceChurnStaticAnalysis`
- `delosDeadCodeCandidateReport`
- `delosHeapRawStoreDuplicateClassificationReport`
- `delosDerbyModuleParityStaticAnalysis`
- `delosDerbyForkDiffClassificationStaticAnalysis`

The optional implementation-phase gates may remain available for focused review,
but they must not become S0 dependencies unless they check stable code boundaries
rather than roadmap prose.

## Fix-now findings

None. The latest green closeout means there is no known failing cleanup blocker.
The next work should be consolidation, not emergency patching.

## Consolidation findings

### 1. `PageVolumeMvccStateStore` is now too broad

`PageVolumeMvccStateStore` is the strongest cleanup candidate. It currently owns
or coordinates:

- page-volume open/disable logic
- WAL sidecar open and batched WAL append
- checkpoint sidecar validation
- subsystem recovery-record replay plan handoff
- ordered-index sidecar open/fallback handling
- page-backed table access
- vacuum/purge entry points
- diagnostics/statistics exposure

This was acceptable while the roadmap was being built, but it is now a coupling
hotspot. The next cleanup should extract an internal sidecar/open context such as
`PageVolumeMvccSidecars` or `PageVolumeMvccOpenContext`.

Required constraints:

- no file-format changes
- no WAL format changes
- no changed table-open behavior
- no change to ordered-index fallback semantics
- no new S0 roadmap gate

Suggested first cleanup overlay:

`delosdb-mvcc-sidecar-open-context-cleanup-overlay.zip`

### 2. `MvccInheritedTable` mixes table state with purge scheduling

`MvccInheritedTable` now owns transaction/write paths, diagnostics counters,
active-handle state, and purge scheduling/executor ownership. The raw thread issue
is fixed, but the executor lifecycle still makes the table class broader than it
needs to be.

The purge scheduling policy should move behind a small internal coordinator that
owns:

- synchronous vs asynchronous scheduling mode
- threshold decisions
- executor submission
- retained-reader recheck
- run/skip diagnostics
- shutdown

Required constraints:

- keep asynchronous purge opt-in
- keep commit-boundary deterministic purge as default
- keep write-lock reentry before vacuum
- do not introduce raw threads

Suggested cleanup overlay:

`delosdb-mvcc-purge-scheduler-consolidation-overlay.zip`

### 3. `RawStore` DelosDB sidecar backup helper

Execution state: implementation slice delivered by `delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip`.

The backup/restore sidecar fix was necessary, but it increased inherited
`RawStore` fork surface. The DelosDB-specific helper logic is now isolated behind
`DelosMvccBackupSidecarSupport`, a package-private helper in the Derby storage
module. `RawStore` keeps only the inherited backup/restore control flow plus the
DelosDB hook calls.

Suggested shape:

- `DelosMvccBackupSidecarSupport`
- package-private or private static helper boundary
- RawStore keeps only the inherited backup/restore control flow plus calls into the helper

Required constraints:

- no Derby backup semantics change
- heap-only backup remains sidecar-free
- manifest verification behavior preserved
- fork-diff classification remains explicit

Delivered cleanup overlay:

`delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip`

### 4. Buffer/recovery diagnostics names should be normalized

The Phase L/M compromise fixes added useful counters, but the terminology is now
mixed:

- WAL batch vs group commit
- page-volume force batch vs transaction WAL batch
- replay plan vs subsystem completeness
- `ForTesting` suffixes in diagnostics-facing counters

A small naming cleanup would make reports clearer without behavior changes.

Required constraints:

- no public compatibility break unless the symbol is test-only/internal
- avoid renaming SQL-facing diagnostics without compatibility shim
- keep old test helper names if they are already part of test expectations

Suggested cleanup overlay:

`delosdb-mvcc-diagnostics-naming-cleanup-overlay.zip`

### 5. Phase-specific static-analysis manifests remain numerous

The obsolete prose gates are removed from S0 and several obsolete tasks were
removed from Gradle registration. However, many phase-specific manifests remain
under `gradle/static-analysis/` for optional review.

This is acceptable for now because they are not S0 blockers. After one more green
iteration, consider either:

- keeping only implementation-relevant manifests with live tasks; or
- moving historical phase manifests into docs/audit history.

Do not remove implementation evidence until the corresponding code has normal test
coverage and the manifest is no longer useful.

## Recommended cleanup execution order

1. `delosdb-mvcc-sidecar-open-context-cleanup-overlay.zip`
2. `delosdb-mvcc-purge-scheduler-consolidation-overlay.zip`
3. `delosdb-heap-rawstore-boundary-cleanup-phase3-overlay.zip` — delivered
4. `delosdb-mvcc-diagnostics-naming-cleanup-overlay.zip`
5. optional historical static-manifest archive/removal after a final green pass

## Verification for cleanup overlays

Each cleanup overlay should run:

```bash
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-derby:check :delosdb-storage-mvcc:check
```

If the cleanup touches MVCC recovery, WAL, buffer, or purge code, also run:

```bash
./gradlew :delosdb-storage-mvcc:runMvccRecoveryReplayEngineTest
./gradlew :delosdb-storage-mvcc:runPageVolumeMvccWriteAheadLogBatchTest
./gradlew :delosdb-storage-mvcc:runDelosMvccMicrobenchmarkValidation
./gradlew :delosdb-storage-mvcc:runDelosMvccConcurrencyValidation
./gradlew :delosdb-storage-mvcc:runDelosMvccLongReaderValidation
```

## Not allowed during this cleanup phase

- no new storage features
- no module merging
- no heap page-format change
- no raw-log format change
- no catalog or DRDA behavior change
- no roadmap/prose gate in S0
- no weakening of S0
- no baseline entry for a real new storage static-analysis finding
