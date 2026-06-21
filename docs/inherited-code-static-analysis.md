# Inherited Derby Code Static Analysis Summary

Last regenerated: 2026-06-20

This summary describes the current inherited-code state after the DelosDB
inherited-code modernization pass. It compares the current DelosDB workspace
against the uploaded Apache Derby 10.17.1.0 source archive and records which
areas were modernized, which areas remain inherited, and which areas should not
be refactored without a separate proof campaign.

This is a static source summary, not a roadmap. The source of truth remains the
code and the verification tasks listed below.

## Source baseline

```text
Reference source: db-derby-10.17.1.0-src.zip
Current source:   DelosDB workspace after inherited-code modernization overlays
Scope:            Java source; generated build output and workspace metadata excluded
```

Workspace metadata such as `.git/`, `.gradle/`, `.idea/`, `build/`, `out/`, and
`__MACOSX/` is excluded from the comparison and must not be included in overlays.

## B9 Phase B static-analysis closeout

B9 adds a narrow static-analysis gate for the real Derby store module boundary.
It is not a production Java deletion pass. The gate checks cleanup signals that
are safe before Phase C:

```text
stale exact B6 readiness phrase count: 0
duplicate Gradle task registrations: none
duplicate top-level Gradle definitions: none
duplicate legacy Derby store source ownership: none
stale workspace artifacts: none after cleanup script
```

The source-ownership check is limited to the inherited Derby store packages under
`org.apache.derby.iapi.store.*` and `org.apache.derby.impl.store.*` across
`delosdb-engine`, `delosdb-engine-kernel`, and `delosdb-storage-derby`.

The cleanup script is:

```bash
./scripts/cleanup-overlay-b9-stale-files.sh
```

It removes local artifacts such as `derby.log`, `.DS_Store`, `*.orig`, `*.rej`,
backup `*~` files, and `__MACOSX/`. It intentionally does not remove `.git/`,
`.gradle/`, `.idea/`, `build/`, or source files.

## High-level source delta

| Area | Count |
|---|---:|
| Original Derby Java files scanned | 2,810 |
| Current DelosDB Java files scanned | 2,985 |
| Original Derby engine Java files | 1,400 |
| Current DelosDB engine Java files | 1,485 |
| Inherited engine files still present by path | 1,400 |
| Inherited engine files still byte-for-byte unchanged | 1,198 |
| Inherited engine files changed by DelosDB | 202 |
| DelosDB-added engine Java files | 85 |
| Original engine files removed by DelosDB | 0 |

Interpretation: DelosDB remains Derby-compatible by inheritance. The current
modernization pass changed a significant but still bounded slice of inherited
engine code. No original Derby engine file was removed.

## Current inherited-code audit highlights

The current `dev/inherited-code-quality-audit.sh --verify` report shows:

| Area | Current count |
|---|---:|
| Production `Object.finalize()` overrides | 0 |
| All-tree `Object.finalize()` overrides | 0 |
| Production `StringBufferInputStream` references | 0 |
| Production lifecycle `stop` / `suspend` / `resume`-looking calls | 13 |
| Production XML factory references | 20 |
| Production reflection method lookup/invoke references | 101 |
| Direct runtime-statistics `XPLAINResultSetDescriptor` constructors | 1 |
| Sort memory policy landmarks | 7 |
| Legacy Derby harness launcher/JVM files retained | 21 |
| `module-info.java` files | 10 |
| JPMS exports | 88 |
| JPMS internal-looking exports | 54 |

The remaining direct `XPLAINResultSetDescriptor` constructor is intentionally in
`XPLAINResultSetDescriptorBuilder`. The remaining direct timing descriptor
constructor is intentionally in `XPLAINResultSetTimingsDescriptorBuilder`.

## Algorithms still inherited from Derby

The following algorithm families remain substantially Derby-owned and should be
changed only with focused proof tests:

### Compiler and optimizer

- SQL parsing into query trees.
- Binding, preprocessing, optimization, and code generation.
- Selinger-style access-path and join-order search.
- Predicate pushdown and pullback.
- Nested-loop, hash-join, and sort-avoidance costing.

### Execution

- Runtime result-set tree execution.
- Generated activation classes.
- Derby bytecode generation and generated method dispatch.

### Storage and access methods

- Heap table access.
- Heap sequential scan and row movement.
- B-tree search, insert, delete, scan, page split, and page-local binary search.

### Sorting

- In-memory sort buffer.
- External merge sort.
- Run creation and merge insertion.

### Transactions, locking, and recovery

- Write-ahead logging.
- Checkpoints.
- Undo/redo recovery.
- Savepoints.
- Row/container/table locking.
- Lock wait, timeout, and deadlock behavior.

## Modernized areas

### Runtime statistics descriptor construction

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts/XPLAINResultSetDescriptorBuilder.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts/XPLAINResultSetTimingsDescriptorBuilder.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/rts/Real*.java
```

What changed:

- Replaced repeated long positional `XPLAINResultSetDescriptor` construction
  with a named builder.
- Replaced repeated timing descriptor construction with a named timing builder.
- Preserved descriptor values and runtime-statistics semantics.

Current state:

```text
Raw result-set descriptor constructor calls: only the builder
Raw timing descriptor constructor calls:     only the timing builder
```

### Result-set constructor plumbing

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/*ResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/*ResultSetParameters.java
```

Added parameter-object constructor paths for major result-set families:

```text
AggregateResultSetParameters
CurrentOfResultSetParameters
DeleteCascadeResultSetParameters
HashScanResultSetParameters
HashTableResultSetParameters
IndexRowToBaseRowResultSetParameters
InsertResultSetParameters
JoinResultSetParameters
LastIndexKeyResultSetParameters
LeftOuterJoinResultSetParameters
NormalizeResultSetParameters
ProjectRestrictResultSetParameters
SetOpResultSetParameters
SortResultSetParameters
SubqueryResultSetParameters
TableScanResultSetParameters
UnionResultSetParameters
UpdateResultSetParameters
```

What changed:

- Reduced fragile 15--20 argument constructor calls in
  `GenericResultSetFactory`.
- Preserved the old constructors for compatibility.
- Kept optimizer choice, scan semantics, locking, and DML behavior unchanged.

### Generated execution and JVM 21 boundary

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/services/bytecode/asm/AsmJava.java
delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/VMOpcode.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectGeneratedClass.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectMethod.java
```

What changed:

- Replaced the inherited generated-bytecode writer with the ASM backend.
- Registered `AsmJava` directly as the production compiler module.
- Emits generated activation bytecode as Java 21 classfiles, major version 65.
- Preserved direct generated `e0` through `e9` dispatch.
- Added MethodHandle-backed fallback dispatch for non-direct generated methods,
  with reflective invocation retained as compatibility fallback.
- Quarantined the old classfile writer after the ASM language suite and jar
  smokes were green.

Current generated activation boundary:

```text
module compiler = org.apache.derby.impl.services.bytecode.asm.AsmJava
classfile major = 65
```

### XML factory hardening with Derby compatibility

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/iapi/xml/SecureXmlFactory.java
delosdb-engine/src/main/java/org/apache/derby/iapi/types/SqlXmlUtil.java
delosdb-engine/src/main/java/org/apache/derby/vti/XmlVTI.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/XMLOptTrace.java
```

What changed:

- Centralized XML factory creation in `SecureXmlFactory`.
- Preserved Derby SQL/XML behavior for DTD default attributes and internal
  entity expansion limits.
- Blocked external entity expansion where compatible with inherited tests.

Important compatibility note: the first hardening attempt was too strict and
broke Derby XML tests. The final state is intentionally compatible with
`XMLBindingTest` and `XMLXXETest`.

### Sort memory and grow/spill policy

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/ExternalSortFactory.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/MergeInserter.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/SortMemoryPolicy.java
```

What changed:

- Extracted pure sort sizing policy into `SortMemoryPolicy`.
- Replaced the inherited fixed automatic 1 MiB sort target with a conservative
  JVM-aware automatic target.
- Preserved `derby.storage.sortBufferMax` as the hard user override.
- Kept `ExternalSortFactory.DEFAULT_MEM_USE` as a compatibility alias for
  inherited merge-inserter heuristics.
- Extracted dynamic in-memory grow/spill decisions from `MergeInserter` into
  `SortMemoryPolicy` without changing the heuristic.

Verification coverage:

```text
sortMemoryPolicyProbe
sortMemoryObservabilityAudit
runInheritedAlgorithmClosureProofTests
```

### CostModelProvider v2 path guard

Source paths:

```text
delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/cost/CostModelProbe.java
dev/cost-model-provider-storecost-smoke/src/main/java/delosdb/smoke/CostModelProviderStoreCostSmoke.java
```

What changed:

- Added a stable diagnostic path label for the active store-cost-controller
  path.
- Strengthened the smoke proof that CostModelProvider v2 flows through
  `StoreCostControllerBridge`.
- Guarded against accidental fallback to the legacy `IndexProviderCostBridge`
  path during v2 queries.

The optimizer is still not rewritten.

### B-tree page-local search cleanup

Source path:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/ControlRow.java
```

What changed:

- Added focused B-tree directional boundary tests.
- Grouped boundary, max-scan, and split/deadlock tests before refactoring.
- Extracted the duplicated page-local forward/backward binary-search helper.

What did not change:

- Page split logic.
- Latch behavior.
- Traversal and repositioning.
- Delete/compact behavior.
- Disk format.

### Transaction, locking, and recovery proof coverage

Source paths:

```text
delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/tests/store/TransactionLockingRecoveryProofTest.java
delosdb-tests/build.gradle
```

What changed:

- Added proof coverage for row-lock timeout/release, savepoint rollback, and
  checkpoint visibility.
- Grouped the proof into algorithm-closure checks.

What did not change:

- Lock manager state machines.
- Log replay.
- Recovery code.
- Raw store state transitions.

### Service, monitor, and loader cleanup

Source paths:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectClassesJava2.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/DatabaseClasses.java
delosdb-engine/src/main/java/org/apache/derby/iapi/services/loader/ClassInfo.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/monitor/BaseMonitor.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/monitor/ProtocolKey.java
```

What changed:

- Simplified generated-class and thread-context class-loader selection.
- Simplified `ClassInfo` constructor caching.
- Removed stale Class.newInstance-era fallback plumbing in the touched path.
- Improved exception-cause preservation in application-class loading.
- Made `ProtocolKey` state private/final and simplified equality.
- Made generated-class diagnostic file writing use try-with-resources.
- Extracted module/sub-sub-protocol tag parsing in `BaseMonitor`.

What did not change:

- Monitor boot semantics.
- Module naming.
- Service lifecycle semantics.
- Engine boot resource format.

### Legacy Derby harness quarantine

Source paths:

```text
delosdb-tests/build.gradle
delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/*.java
```

What changed:

- The stale Derby VM adapter and `RunSuite` launcher layer remains in the
  source tree for reference.
- It is excluded from active Gradle test compilation.
- `RunTest`, `RunList`, `NetServer`, `jvm`, and `currentjvm` remain compiled for
  inherited compatibility.

## Current verification commands

Core gates:

```bash
./gradlew :delosdb-engine:compileDerbyEngine inheritedCodeQualityAudit derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Modernization-specific proof gates:

```bash
./gradlew generatedBytecodeAsmJvm21Proof
./gradlew sortMemoryPolicyProbe sortMemoryObservabilityAudit
./gradlew costModelProviderStoreCostSmoke indexProviderCostInfluenceSmoke
./gradlew :delosdb-tests:runInheritedAlgorithmClosureProofTests
```

Focused safety gates:

```bash
./gradlew :delosdb-tests:runBTreeSearchRefactorProofTests
./gradlew :delosdb-tests:runTransactionLockingRecoveryProofTest
./dev/inherited-code-quality-audit.sh --verify
./dev/legacy-derby-harness-audit.sh --verify
```

## Remaining hotspots

These are known inherited-code areas that remain intentionally conservative.

### Reflection and dynamic loading

Reflection count is still high. This is expected because Derby uses reflection
for generated methods, DataSource property binding, tool compatibility, monitor
boot, optional Lucene support, and DRDA integration.

Do not globally replace reflection. Treat each reflective call site as a local
compatibility surface.

### JPMS exports

The engine and supporting modules still have broad exports, including internal
packages. This remains necessary because generated plan classes and inherited
module wiring still depend on those boundaries.

Do not narrow JPMS exports until generated-plan loading is moved to a named
module strategy or otherwise proven safe.

### B-tree internals

The only B-tree production cleanup done in this pass was page-local search
helper extraction. Do not continue into page split, latch, scan repositioning, or
compaction without a separate crash/corruption-focused proof campaign.

### Locking and recovery

Lock and recovery code received proof tests, not refactors. This is intentional.
Do not refactor `LockSet`, `LockControl`, `ConcurrentLockSet`, `RAMTransaction`,
`RawStore`, `LogToFile`, or `FileLogger` without stronger dirty-shutdown and
restart coverage.

### Optimizer enumeration

The optimizer is guarded and observed, not rewritten. Access-path and join-order
enumeration should remain unchanged until there are baseline metrics and focused
plan-shape tests.

## Recommended next static-analysis checks

If this summary is regenerated again, check these deltas first:

```text
- Direct XPLAIN descriptor constructors should remain centralized in builders.
- Production StringBufferInputStream references should remain zero.
- Generated bytecode should remain on the ASM Java 21 path and keep `generatedBytecodeAsmJvm21Proof` green.
- XML tests must remain compatible with DTD defaults and internal entity limits.
- Sort policy changes must keep derby.storage.sortBufferMax as hard override.
- B-tree changes must keep runBTreeSearchRefactorProofTests green.
- Transaction/log/lock changes must keep runTransactionLockingRecoveryProofTest green.
```
