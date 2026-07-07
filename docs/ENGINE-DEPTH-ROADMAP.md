# DelosDB Engine-Depth Roadmap

This roadmap opens the next storage-engine depth pass after the Phase A-J balanced
storage modernization roadmap and the two post-closeout tradeoff hardening audits.

Execution state: backup/restore, DERBY-7107, Phase K, Phase L, Phase M, purge scheduling, Phase N, and Phase O audit slices delivered.

## North star

```text
Preserve Derby compatibility.
Do not preserve Derby internals for their own sake.
```

DelosDB remains a modern Derby-compatible engine. Old-compatible SQL, JDBC, DRDA,
catalog, heap, and durable-format behavior must keep running. Internals may evolve
only behind explicit gates.

## Standing rules

```text
Do not build a parallel optimizer statistics truth source.
Do not treat recovery-record metadata as recovery replay.
Do not treat a pinned/dirty cache boundary as a complete buffer manager.
Do not extract shared services until heap and MVCC both have concrete proof points.
Do not add benchmarks to S0 when they are nondeterministic or long-running.
Do not change Derby heap page format, raw log format, catalog behavior, DRDA behavior, or module parity without a specific compatibility gate.
```

## Execution order

```text
0. Backup/restore sidecar verification micro-slice
1. DERBY-7107 review/apply-if-still-valid micro-slice
2. Phase K — MVCC statistics and Derby optimizer/cost integration
3. Phase L — MVCC recovery replay engine
4. Phase M — MVCC buffer manager phase 2
5. Purge daemon scheduling micro-slice
6. Phase N — Heap cleanup phase 2 and fork-diff expansion
7. Phase O — Shared-service extraction audit
8. Phase P — Performance, concurrency, and external validation closeout
```

The first implementation slice after this contract should be backup/restore sidecar
verification. This comes before recovery replay because unrestorable sidecar state
would make recovery reasoning misleading.

## Micro-slice 0 — Backup/restore sidecar verification

Execution state: implementation slice delivered by delosdb-backup-restore-sidecar-verification-overlay.zip.

Overlay:

```text
delosdb-backup-restore-sidecar-verification-overlay.zip
```

Goal:
Verify that DelosDB backup/restore flows include, restore, and validate MVCC sidecar
state introduced by the page-volume and durable MVCC storage work.

Required behavior:

```text
MVCC sidecar files are included in backup manifests or explicitly rejected as unsupported
restore recreates the sidecar layout required by MVCC reopen
backup/restore verification detects missing sidecar state
backup/restore verification detects stale or mismatched sidecar state
heap-only backup behavior remains Derby-compatible
mixed heap + MVCC backup behavior is explicit and tested
```

Not allowed:

```text
no page-format changes
no raw-log format changes
no catalog behavior changes
no DRDA behavior changes
no repair commands disguised as backup
no silent omission of provider-owned sidecar state
```

Gate:

```text
./gradlew delosBackupRestoreSidecarVerificationStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-derby:check :delosdb-storage-mvcc:check
```

Commit message:

```text
Verify MVCC sidecar backup and restore coverage
```

Implementation note:

```text
Derby's inherited backup path now copies the DelosDB provider-owned delos_mvcc sidecar directory into the backup image when it exists.
Restore from backup replaces stale target delos_mvcc sidecar state with the backup image, or removes stale target sidecars when restoring a heap-only/older backup.
SQL integration verifies both a delos_mvcc backup/createFrom/reopen round trip and a heap-only backup/restore compatibility path.
```

## Micro-slice 1 — DERBY-7107 review/apply-if-still-valid

Execution state: implementation slice delivered by delosdb-derby-7107-inaddr-any-overlay.zip.

Overlay:

```text
delosdb-derby-7107-review-overlay.zip
```

Goal:
Review the DERBY-7107 patch against DelosDB's current inherited Derby file shape and
apply it only if still valid, compatibility-preserving, and covered by focused tests.

Required behavior:

```text
patch provenance is documented
modified inherited file receives or updates fork-diff classification
focused regression test covers the DERBY-7107 behavior
default Derby-compatible behavior remains unchanged except for the validated bug fix
```

Not allowed:

```text
no blind patch import
no broad inherited-code cleanup piggybacking on the patch
no catalog behavior change unless the Derby issue explicitly requires and tests it
no DRDA/JDBC behavior change outside the issue scope
```

Gate:

```text
./gradlew delosDerby7107ReviewStaticAnalysis
./gradlew delosDerbyForkDiffClassificationStaticAnalysis
./gradlew s0CloseoutVerification
```

Commit message:

```text
Review DERBY-7107 against DelosDB fork
```

Implementation note:

```text
DERBY-7107 is an upstream Apache Derby Network Server bug: NetworkServerControl can bind the server to INADDR_ANY, but then command methods such as ping try to connect to the wildcard address itself. The upstream JIRA issue is open, unresolved, marked patch-available, and describes the safe fix as redirecting command sockets from INADDR_ANY to localhost.
DelosDB applies the fix narrowly in NetworkServerControlImpl.setUpSocket(): the server bind/listen address remains unchanged, while command sockets use 127.0.0.1 for IPv4 wildcard and ::1 for IPv6 wildcard.
Regression coverage verifies the command-target resolver for IPv4 wildcard, IPv6 wildcard, and normal non-wildcard addresses, and wires the test into the derbynet suite.
```

## Phase K — MVCC statistics and Derby optimizer/cost integration

Execution state: closed green.

Overlay:

```text
delosdb-mvcc-statistics-optimizer-cost-integration-overlay.zip
```

Goal:
Feed MVCC storage statistics into Derby's existing optimizer/cost path through the
StoreCostController seam without creating a second optimizer truth source.

Required behavior:

```text
MVCC physical statistics are exposed through the DelosDB storage statistics boundary
MvccStoreCostController maps MVCC statistics into Derby StoreCostController estimates
SYSSTATISTICS and existing Derby update-statistics semantics remain the optimizer-facing truth path
logical-row-identity invariant check proves ordered entries reference rowKeys
row-count, version-count, chain-depth, and index-entry stats are available for later benchmarks
provider-cost override remains explicit, opt-in, and non-default until validated
```

Not allowed:

```text
no parallel optimizer statistics channel
no default Derby optimizer behavior change without a compatibility gate
no catalog fork that bypasses SYSSTATISTICS semantics
no MVCC-only optimizer shortcut invisible to Derby costing hooks
no candidate-index authority revival
```

Tests:

```text
MVCC stats report row count, version count, chain depth, and index entry count
Derby optimizer consumes MVCC estimates only through the cost-controller seam
logical-row-identity invariant fails on ordered entry / rowKey mismatch
candidate-index fallback remains non-authoritative
heap optimizer behavior remains unchanged
```

Gate:

```text
./gradlew delosMvccStatisticsOptimizerCostIntegrationStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-bridge:check :delosdb-storage-mvcc:check
```

Commit message:

```text
Integrate MVCC statistics with Derby optimizer costing
```

Implementation note:

```text
MVCC statistics now feed Derby's inherited StoreCostController path through MvccStoreCostController when delosdb.mvcc.optimizer.storageStatistics.enabled=true.
Default behavior remains unchanged: the statistics bridge is disabled unless explicitly opted in.
The bridge derives row/page/version/index facts from the open provider diagnostics surface and records DelosMvccOptimizerCostDiagnostics proof counters.
CostModelRequest now requires a strictly positive Derby baseline before provider replacement can be considered safe.
This is not a parallel optimizer statistics channel and does not bypass SYSSTATISTICS semantics.
```

## Phase L — MVCC recovery replay engine

Execution state: implementation slice delivered by delosdb-mvcc-recovery-replay-engine-overlay.zip.

Overlay:

```text
delosdb-mvcc-recovery-replay-engine-overlay.zip
```

Goal:
Turn subsystem recovery records into an actual replay engine that converges after
adversarial crash and reopen scenarios.

Required behavior:

```text
row-page redo replay is idempotent
index-page redo replay is idempotent
overflow-page redo replay is idempotent
free-space-map redo replay is idempotent
transaction-outcome replay is idempotent
checkpoint restore chooses a consistent replay start boundary
cross-subsystem atomicity converges when crash occurs between subsystem redo applications
fault injection supports arbitrary WAL offsets, truncated records, torn sidecar rewrites, and duplicate replay
```

Not allowed:

```text
no repair-by-scanning as substitute for replay correctness
no replay path that depends on candidate-index authority
no silent ignore of torn/truncated records
no heap raw-log behavior changes
no Derby catalog or DRDA changes
```

Tests:

```text
insert/update/delete crash and reopen
index redo replay after partial page mutation
overflow redo replay after partial large-value update
free-space map replay after allocation and reuse
transaction outcome replay after commit/abort boundary interruption
cross-subsystem atomicity crash between row/index/overflow/FSM replay steps
arbitrary WAL-offset fault injection: truncate, duplicate, and torn rewrite cases
```

Gate:

```text
./gradlew delosMvccRecoveryReplayEngineStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-mvcc:check
```

Commit message:

```text
Add MVCC recovery replay engine
```

Implementation note:

```text
MvccRecoveryReplayEngine now coordinates strict page-mutation replay through the transaction-outcome authority.
Subsystem recovery metadata exposes a replay plan and cross-subsystem completeness validation so crash tests can reject row-page redo that lacks matching transaction-outcome, index-page, overflow-page, or free-space-map redo proof.
The replay path is idempotent across duplicate records and repeated boots, tolerates only torn final log tails, and fails loudly on complete corrupt records.
PageBackedMvccTable.openStrict now enters strict recovery through the replay engine.
This is still not a full ARIES-style redo/undo system and does not change heap raw-log, catalog, or DRDA behavior.
```

## Phase M — MVCC buffer manager phase 2

Execution state: implementation slice delivered by delosdb-mvcc-buffer-manager-phase2-overlay.zip.

Overlay:

```text
delosdb-mvcc-buffer-manager-phase2-overlay.zip
```

Goal:
Move from a pinned/dirty cache boundary to a real bounded buffer manager with WAL-aware
flush discipline, checkpoint interaction, and fsync batching.

Required behavior:

```text
pin/unpin remains JMM-safe under contention
bounded eviction chooses only evictable pages
WAL-before-flush invariant prevents dirty page publication before covering log records are forced
dirty-page flush scheduling integrates with checkpoints
group commit batches compatible fsync work
flush ordering is deterministic enough for tests and observable diagnostics
Phase L recovery replay harness remains a standing gate for buffer-manager changes
```

Not allowed:

```text
no dirty page reaches disk before its covering WAL/sidecar record is durable
no eviction of pinned pages
no checkpoint metadata that lies about unflushed dirty pages
no group-commit batching that changes commit visibility semantics
no heap page-format or raw-log changes
```

Tests:

```text
pin prevents eviction under concurrent readers and writers
unpin allows eviction
dirty page enters flush scheduling
WAL-before-flush violation is detected by a fault-injection test
group commit reduces forced-write count under batched commits without changing visibility
checkpoint/reopen remains clean under buffer pressure
Phase L recovery replay tests rerun after buffer-manager changes
```

Gate:

```text
./gradlew delosMvccBufferManagerPhase2StaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-mvcc:check
```

Commit message:

```text
Add MVCC buffer manager flush discipline
```

Implementation note:

```text
MvccBufferFlushCoordinator now enforces the MVCC WAL-before-page-flush rule for non-zero pageLSN dirty pages.
MvccPageCache can flush dirty pages through a grouped force boundary so multiple dirty pages in one flush batch produce one page-volume force.
PageBackedMvccTableStore records forced page-volume WAL LSNs before flushing pageLSN-bearing dirty pages and routes durable page writes through the coordinator.
MvccBufferManagerPhase2Test covers WAL-before-flush fault injection and grouped force batching.
Phase L recovery replay remains a standing gate after this buffer-manager change.
```

## Micro-slice 5 — Purge daemon scheduling

Execution state: implementation slice delivered by delosdb-mvcc-purge-daemon-scheduling-overlay.zip.

Overlay:

```text
delosdb-mvcc-purge-daemon-scheduling-overlay.zip
```

Goal:
Replace purely opportunistic purge behavior with a safe purge scheduler that cooperates
with long readers, checkpoints, and buffer pressure.

Required behavior:

```text
purge daemon observes oldest active snapshot
purge daemon does not remove versions visible to long readers
purge scheduling can be paused for tests
purge progress is observable through diagnostics
checkpoint and buffer-manager paths can request purge without violating visibility
```

Not allowed:

```text
no background thread that makes tests nondeterministic without a test control
no purge of versions visible to active snapshots
no heap behavior changes
no SQL syntax changes
```

Tests:

```text
long reader blocks unsafe purge
completed reader allows purge
purge cooperates with checkpoint and buffer pressure
purge progress counter advances deterministically under test control
reopen after purge remains correct
```

Gate:

```text
./gradlew delosMvccPurgeDaemonSchedulingStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-mvcc:check
```

Commit message:

```text
Add MVCC purge daemon scheduling
```

Implementation note:

```text
MvccPurgeDaemon adds a deterministic cooperative purge scheduler for inherited MVCC tables.
It is not a free-running background thread: commit boundaries call maybeRunAfterCommit(), tests can pause it by leaving delosdb.mvcc.purgeDaemon.enabled unset, and delosdb.mvcc.purgeDaemon.changedRowsThreshold controls deterministic triggering.
The scheduler observes retained inherited MVCC transactions/snapshots before calling the existing provider-owned vacuum path, exposes schedule/run/skip/last-decision diagnostics, and keeps manual compress/purge behavior unchanged.
MvccSqlPurgeDaemonSchedulingTest proves automatic purge after committed write bursts and default-paused behavior.
```

## Phase N — Heap cleanup phase 2 and fork-diff expansion

Execution state: implementation slice delivered by delosdb-heap-cleanup-phase2-forkdiff-overlay.zip.

Overlay:

```text
delosdb-heap-cleanup-phase2-forkdiff-overlay.zip
```

Goal:
Continue inherited heap/raw-store cleanup behind compatibility gates and expand the
fork-diff classification beyond the initial high-risk file set when touched files justify it.
This slice targets inherited Derby demo VTI cleanup because those files carried thread-unsafe
`SimpleDateFormat` instance state and stale formatter imports, and Phase N explicitly required
that demos-module cleanup not remain homeless.

Required behavior delivered:

```text
classify-as-you-clean expands inherited Derby diff coverage
ApacheServerLogVTI keeps the same timestamp pattern but no longer stores shared SimpleDateFormat state
SubversionLogVTI keeps the same timestamp pattern but no longer stores shared SimpleDateFormat state
DerbyJiraReportVTI, PropertyFileVTI, and LineListVTI remove unused formatter imports/state only
heap diagnostics remain read-only
existing Derby heap databases open and reopen unchanged
```

Not allowed / preserved:

```text
No heap page-format, raw-log, catalog, DRDA/JDBC, or optimizer behavior changed.
no heap page-format change
no raw-log format change
no catalog behavior change
no DRDA/JDBC behavior change
no optimizer behavior change
no inherited-code cleanup without fork-diff classification update
```

Tests and gates:

```text
./gradlew delosHeapCleanupPhase2ForkDiffStaticAnalysis
./gradlew delosDerbyForkDiffClassificationStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew :delosdb-storage-derby:check
```

Commit message:

```text
Expand Derby heap cleanup classification
```

Implementation note:

```text
The two timestamp-parsing demo VTIs now create a formatter per parse path rather than caching a mutable SimpleDateFormat on the VTI instance. This preserves the inherited timestamp patterns while avoiding shared mutable formatter state if the VTI object is reused. The remaining demo VTI files only drop unused formatter imports/state. All five touched inherited demo files are classified in delosdb-derby-fork-diff-classification.txt so the cleanup is visible to the fork-diff gate.
```

## Phase O — Shared-service extraction audit

Execution state: audit slice delivered by delosdb-shared-storage-service-extraction-audit-overlay.zip.

Overlay:

```text
delosdb-shared-storage-service-extraction-audit-overlay.zip
```

Goal:
Audit shared-service extraction candidates only where heap and MVCC both have concrete
proof points. The first named candidate is page checksum/torn-write validation because
heap and MVCC already have related mechanisms.

Required behavior delivered:

```text
shared-service candidates list heap proof path and MVCC proof path
page checksum/torn-write validation is audited as a candidate, not blindly extracted
allocation/free-space abstractions are deferred because provider semantics differ
page/cache abstractions are deferred because heap raw-store cache and MVCC flush coordinator semantics differ
recovery/checkpoint abstractions are deferred because Derby raw-log and MVCC sidecar replay are different format boundaries
statistics/cost and storage-inspector surfaces are recorded as already-shared diagnostic boundaries
```

Not allowed / preserved:

```text
No service is extracted in Phase O.
no shared service created from one-provider evidence
no module merging
no heap format/log behavior change
no MVCC page-format change hidden inside an extraction
no optimizer, catalog, JDBC, or DRDA behavior change
```

Tests and gates:

```text
./gradlew s0CloseoutVerification
```

The shared-service extraction audit is documented in `docs/SHARED-STORAGE-SERVICE-EXTRACTION-AUDIT.md`; it is advisory documentation, not a roadmap/prose S0 gate.

Commit message:

```text
Audit shared storage service extraction candidates
```

Implementation note:

```text
Phase O adds docs/SHARED-STORAGE-SERVICE-EXTRACTION-AUDIT.md as advisory design evidence. It does not add an S0 roadmap/prose gate.
The audit identifies checksum/torn-write validation as READY_FOR_NARROW_DESIGN, but only for a provider-neutral read-only integrity evidence model. Physical heap LOGOP_CHECKSUM encoding, MVCC page-record checksums, MVCC sidecar trailers, allocation/free-space, page-cache flushing, recovery replay, and purge scheduling remain provider-owned.
The audit records storage statistics/cost and storage inspection as already-shared diagnostic boundaries.
```

## Phase P — Performance, concurrency, and external validation closeout

Execution state: validation harness delivered by delosdb-performance-concurrency-validation-overlay.zip.

Overlay:

```text
delosdb-performance-concurrency-validation-overlay.zip
```

Goal:
Make DelosDB's engine improvements measurable and concurrency-validated without adding
long or nondeterministic work to S0.

Required behavior delivered:

```text
root validation task slots exist for JMH, jcstress, SQLancer, two-sided MVCC workload, and long-reader soak
JMH/jcstress/SQLancer slots are not wired into S0
built-in no-dependency MVCC microbenchmark harness records deterministic operation counters
built-in concurrency harness stress-validates pin/unpin and dirty flush structures
two-sided workload harness measures dirty write batching and warm read-path cache hits
long-reader buffer-pressure harness validates the low-level pin invariant behind long-reader-vs-vacuum soak
benchmark baselines are invariant/counter reports, not wall-clock correctness assertions in fast gates
```

Not allowed:

```text
no nondeterministic performance benchmark in s0CloseoutVerification
no benchmark-only behavior changes
no SQLancer task that mutates normal developer databases
no jcstress result treated as optional when concurrency structures are changed
```

Tests and validation tasks:

```text
./gradlew delosJmhMicrobenchmarks
./gradlew delosJcstressConcurrencyValidation
./gradlew delosSqlancerMvccValidation
./gradlew delosTwoSidedMvccWorkloadBenchmark
./gradlew delosLongReaderVacuumSoak
```

Gate:

```text
./gradlew delosPerformanceConcurrencyValidationStaticAnalysis
./gradlew s0CloseoutVerification
```

Optional validation tasks:

```text
./gradlew delosJmhMicrobenchmarks
./gradlew delosJcstressConcurrencyValidation
./gradlew delosTwoSidedMvccWorkloadBenchmark
./gradlew delosLongReaderVacuumSoak
./gradlew delosSqlancerMvccValidation
```

Implementation note:

```text
Phase P adds built-in deterministic validation harnesses first rather than adding external JMH/jcstress/SQLancer dependencies blindly. The root task names are present and kept out of S0. Real external tool integration can replace or wrap these task slots after dependency policy is accepted.
```

Commit message:

```text
Add DelosDB performance and concurrency validation plan
```
