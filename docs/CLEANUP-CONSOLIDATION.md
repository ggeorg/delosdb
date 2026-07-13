# Cleanup and Consolidation Phase

> **Status:** The Phase 6 build/module closeout is implemented and awaits the final JDK 25 gates. The canonical closeout record is [`PHASE-6-CLOSEOUT.md`](PHASE-6-CLOSEOUT.md). This document remains the historical cleanup policy and execution record.

DelosDB entered this phase after the storage closeout plan to reduce duplication, make module boundaries clearer, and keep verification gates strong while avoiding broad rewrites.

The phase was intentionally conservative. Its goal was to make the existing architecture easier to maintain, not to collapse working boundaries prematurely.

## Entry criteria

Before starting a cleanup slice, keep the following gates green:

```sh
./gradlew verifyDelosRuntimeStorageProviders
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew s0CloseoutVerification
./gradlew :delosdb-storage-mvcc:check
./gradlew :delosdb-storage-api:check :delosdb-storage-derby:check :delosdb-storage-bridge:check :delosdb-storage-mvcc:check
```

A cleanup slice should be small enough that a failing gate clearly identifies the changed boundary.

## Completed storage closeout

The completed storage plan delivered these checkpoints:

```text
Derby heap sanity checker
MVCC isolation read-view checkpoint
heap object deserialization filter and static gate
cross-engine consistency framework and static gate
runtime artifact/provider model and static gate
```

These are now part of the baseline. Future cleanup should preserve them, not re-litigate them.

## Module boundaries to keep

Keep these modules separate for now:

```text
:delosdb-storage-api
:delosdb-storage-derby
:delosdb-storage-bridge
:delosdb-storage-mvcc
:delosdb-tests
```

Rationale:

```text
storage-api
  owns provider-neutral diagnostics and storage seams

storage-derby
  contains inherited Derby-compatible storage implementation

storage-bridge
  connects Derby access-method behavior to DelosDB storage providers

storage-mvcc
  owns the opt-in MVCC engine implementation

tests
  owns compatibility and DelosDB integration gates
```

These boundaries are useful because they separate compatibility inheritance from DelosDB-owned storage evolution.

## Derby product/module parity to preserve

Do not consolidate these modules as part of cleanup:

```text
:delosdb-tools
:delosdb-server
:delosdb-runner
:delosdb-optionaltools
:delosdb-client
:delosdb-commons
:delosdb-engine
```

They correspond to original Derby source/product boundaries such as:

```text
java/org.apache.derby.tools
java/org.apache.derby.server
java/org.apache.derby.runner
java/org.apache.derby.optionaltools
java/org.apache.derby.client
java/org.apache.derby.commons
java/org.apache.derby.engine
```

Cleanup may improve their Gradle wiring, generated-resource handling, or runtime artifact modeling, but it should not collapse them into a single source set. A merge is allowed only if a later design explicitly proves the boundary is no longer Derby-compatible or no longer product-visible.

The maintained parity classification is `gradle/static-analysis/delosdb-derby-module-parity.txt`, guarded by `delosDerbyModuleParityStaticAnalysis`.

## Cleanup priorities

### 1. Remove duplicate build knowledge

Runtime artifact knowledge should live in the runtime artifact convention script, not in scattered hard-coded jar lists.

A good cleanup removes a duplicate list and strengthens the modeled source of truth. A bad cleanup moves the same list to another file without reducing drift.

### 2. Stabilize static gates

S0 gates should catch structural drift before long SQL integration tests fail. Prefer small textual/static gates for:

```text
runtime artifact model ownership
provider discovery
heap checker read-only/no-output behavior
object-filter single-install behavior
cross-engine consistency no-repair semantics
```

Static gates should not be so brittle that harmless method renames break the build. They should protect architecture, not exact prose.

### 3. Classify inherited code before changing it

For inherited Derby code, classify before editing:

```text
compatibility boundary
load-bearing inherited implementation
diagnostic-only helper
stale fork artifact
duplicate utility
DelosDB-owned seam
```

Do not delete inherited code merely because it looks old. Delete or isolate it when a gate proves it is stale, duplicate, or outside the supported DelosDB workflow.

### 4. Prefer adapters over mixed ownership

If a class has both inherited Derby behavior and DelosDB-specific behavior, prefer a small adapter or policy class over mixing more DelosDB rules directly into inherited code.

Examples of good ownership:

```text
MvccBridgeIsolationPolicy owns bridge isolation mapping
HeapSanityChecker owns heap consistency checks
DelosHeapObjectDeserializationFilter owns opt-in object filtering
DelosStorageConsistencyDiagnostics owns provider-neutral reporting
```

### 5. Keep tests focused

Every cleanup overlay should identify its primary gate. Examples:

```text
runtime packaging cleanup
  verifyDelosRuntimeStorageProviders

storage bridge cleanup
  :delosdb-tests:runDelosMvccSqlIntegrationTest

heap compatibility cleanup
  heap static gate + SYSCS_CHECK_TABLE test coverage

module build cleanup
  s0CloseoutVerification + focused affected module checks
```

Avoid cleanup overlays that require the full tree to fail before the actual bug is visible.

## Consolidation rules

Consolidation is allowed when it removes real friction:

```text
removes duplicate generated-resource wiring
removes duplicate runtime artifact declarations
removes circular task dependencies
makes provider discovery explicit
clarifies compatibility vs DelosDB-owned seams
```

Consolidation is not justified when it only:

```text
reduces the visible number of Gradle modules
hides classpath drift inside a larger source set
mixes inherited Derby compatibility code with MVCC implementation code
makes runtime artifact names less Derby-compatible
weakens static gates
```

## Suggested next cleanup slices

Recommended order:

```text
1. Storage module dependency report
2. Runtime artifact convention cleanup, if more duplicate jar knowledge remains
3. Inherited storage dead-code and duplicate-code classification
4. Heap/raw-store diagnostic helper review
5. MVCC bridge policy extraction review
6. Derby module-parity review for any future module-boundary change
```

Each slice should produce either:

```text
a small code cleanup with tests
a static gate improvement
a documentation update
a classification report with no source behavior change
```


## Cleanup/consolidation closeout

This phase is considered closed for now after the following structural gates are green:

```text
delosModuleDependencyBoundaryStaticAnalysis
delosOverlayCleanupScriptStaticAnalysis
delosWorkspaceChurnStaticAnalysis
delosDerbyModuleParityStaticAnalysis
delosHeapRawStoreDuplicateClassificationReport
```

The exit decision is intentionally conservative: the repository keeps maintained gates for module boundaries, one-shot overlay scripts, workspace churn, inherited duplicate classification, and Derby module parity. Completed phase-specific proof manifests and the empty dead-code candidate report were removed rather than retained as permanent state. Future work should return to feature or correctness slices rather than continuing broad cleanup.

## Anti-goals for this phase

Do not use cleanup/consolidation as a reason to:

```text
change Derby heap format
change raw-store logging
replace DRDA/JDBC compatibility
flip default storage to delos_mvcc
remove MVCC candidate-index fallback unless separately proven safe
remove Derby optimizer fallback or remainder predicates
turn consistency diagnostics into repair tools
introduce a new serialization format into the DRDA/JDBC path
```
