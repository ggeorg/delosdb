# DelosDB inherited Derby code static analysis

Scope: uploaded DelosDB workspace snapshot and uploaded Apache Derby 10.17.1.0 source/documentation archive.

This is a static scan only. No project files were modified and no overlay was produced.

## Baseline facts

- Original Apache Derby archive: `db-derby-10.17.1.0-src.zip`.
- Current DelosDB workspace archive: `delosdb 19.zip`.
- Original Derby Java files scanned: 2,863 files, about 1.23M lines.
- DelosDB Java files scanned: 2,956 files, about 1.23M lines.
- Original Derby engine module: 1,400 Java files, about 553K lines.
- DelosDB engine module: 1,463 Java files, about 557K lines.
- Inherited engine files in common with Derby: 1,399.
- Byte-for-byte unchanged inherited engine files: 1,247.
- Changed inherited engine files: 152.
- New DelosDB engine Java files under `io/github/ggeorg/delosdb/engine/...`: 58+ provider/extension/logging files.

Interpretation: DelosDB is still mostly Derby internals with a small modernization/extension layer. That is good for compatibility, but it means most risk and most performance behavior is still inherited Derby behavior.

## Algorithm implementations found

### SQL compilation and optimization

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/ParserImpl.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/OptimizerImpl.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/FromBaseTable.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/PredicateList.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/NestedLoopJoinStrategy.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/HashJoinStrategy.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/CostEstimateImpl.java`

Implemented algorithm families:

- SQL parser to query tree construction.
- Query tree bind/preprocess/optimize/generate lifecycle.
- Selinger-style cost-based access path and join-order enumeration.
- Join-order permutation search with pruning and timeout logic.
- Predicate pushdown/pullback across join-order positions.
- Nested-loop join costing.
- Hash-join costing and plan selection.
- Sort avoidance through access path ordering.
- Cost estimate propagation through project/restrict/select/join/result-set nodes.

Main weakness:

- The optimizer is large, mutable, stateful, and deeply coupled to compiler nodes. It is not a clean optimizer framework. DelosDB's CostModelProvider v2 is a good seam, but the join enumerator, predicate reasoning, and plan shape rules remain inherited Derby internals.

### Store/access methods

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/Heap.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/HeapController.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/HeapScan.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/HeapCostController.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTree.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeController.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeScan.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/ControlRow.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/LeafControlRow.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BranchControlRow.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeCostController.java`

Implemented algorithm families:

- Heap table access.
- Heap sequential scans.
- Heap compression / row movement.
- B-tree insert/delete/search/scan.
- B-tree page binary search.
- B-tree page split propagation.
- B-tree forward/backward scans.
- B-tree cost estimation.
- Access-method factory lookup through Derby conglomerate factory IDs.

Important source detail:

- `ControlRow.searchForEntry()` performs binary search on a B-tree page and explicitly tries to avoid object allocation during that search.
- `BTreeCostController` contains hard-coded page/row cost assumptions and comments saying it mostly uses heap scan costs for B-tree leaf scans and ignores some qualifier costs.

Main weakness:

- The heap and B-tree implementation is real, deep, and proven, but tightly coupled to Derby raw store pages, row formats, latches, and recovery. It is not yet an independent provider API implementation. That is why `CREATE INDEX USING memory` correctly remains rejected for executor/storage integration.

### Sorting

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/ExternalSortFactory.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/MergeSort.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/MergeInserter.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/MergeScan.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/access/sort/SortBuffer.java`

Implemented algorithm families:

- In-memory sort buffer.
- External merge sort with merge runs.
- Unique-sort variant for index creation / duplicate handling.

Weaknesses:

- `ExternalSortFactory` still defaults to about 1 MB target memory (`DEFAULT_MEM_USE = 1024*1024`) and has a `RESOLVE` comment saying sort memory sizing should use row counts/row sizes more intelligently.
- Sort sizing is old-JVM-era and not container/JVM-ergonomic.

### Locking and deadlock detection

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/services/locks/ConcurrentLockSet.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/locks/Deadlock.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/locks/LockControl.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/locks/LockSpace.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/locks/Timeout.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/RowLocking1.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/RowLocking2.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/RowLocking3.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/RowLockingRR.java`

Implemented algorithm families:

- Lock table with concurrent hash map entries.
- Compatibility-based lock granting.
- Lock wait queues.
- Timeout handling.
- Deadlock detection using waiter graph traversal.
- Row/container/table locking policies for different isolation levels.

Weaknesses:

- The lock manager is partly modernized (`ConcurrentHashMap`, `ReentrantLock`, `Condition`) but still carries old monitor-era concepts and diagnostic snapshots.
- It uses a lot of custom concurrency code. Any changes here must be proven with stress tests, not only unit tests.

### Cache / buffer replacement

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/services/cache/ConcurrentCache.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/cache/ClockPolicy.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/cache/BackgroundCleaner.java`

Implemented algorithm families:

- Concurrent cache table.
- Clock replacement policy.
- Background cleaner.

Weaknesses:

- Derby's cache policy is page/cache-entry oriented and predates modern JVM allocation and observability patterns. It may still be fine, but DelosDB should benchmark it before touching it.

### Logging, recovery, and transactions

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/LogToFile.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/FileLogger.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/LogAccessFile.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/LogRecord.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/Xact.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/XactFactory.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/TransactionTable.java`

Implemented algorithm families:

- Write-ahead logging.
- Checkpoints.
- Undo/redo recovery mechanics.
- Transaction state management.
- Savepoints.
- XA transaction support.

Weaknesses:

- `LogToFile.java` is very large and synchronization-heavy. It is central correctness code and should not be casually modernized.
- Any JVM 21 I/O improvement here needs crash/recovery proof first.

### SQL execution and generated bytecode

Key files:

- `delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/*ResultSet.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/bytecode/*`
- `delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/*`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectGeneratedClass.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectMethod.java`
- `delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectLoaderJava2.java`

Implemented algorithm families:

- Runtime result-set tree execution.
- Generated activation classes.
- Derby custom classfile writer.
- Generated method dispatch through `GeneratedMethod`.
- Fast direct dispatch for generated `e0` to `e9` methods.
- Reflective fallback dispatch for other generated methods.

Critical JVM 21 observation:

- `VMDescriptor` still declares classfile version 45.3, described in source as Java 1.0.2 era.
- The generated SQL activation bytecode is therefore intentionally ancient classfile format.
- Java 21 can still load it, but this is a major modernization target. It affects module boundaries, verification behavior, diagnostics, and possible use of newer invocation mechanisms.

## Stale / legacy code candidates

### 1. Old Derby functional harness JVM classes

Files:

- `delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/jdk13.java`
- `delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/jdk14.java`
- `delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/jdk110.java` through `jdk121.java`
- `delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/RunSuite.java`
- `delosdb-tests/src/test/java/org/apache/derbyTesting/functionTests/harness/RunTest.java`

Why it looks stale:

- These classes model old JVM launch behavior and old Derby harness behavior.
- DelosDB is Gradle-only and no longer uses the old Derby harness as the primary test runner.

Action:

- Do not delete immediately. First add a Gradle task/report that proves no active DelosDB test task depends on this harness. Then quarantine or remove in a cleanup overlay.

### 2. OSGi stub module

Files:

- `delosdb-osgi-stub/src/main/java/org/osgi/framework/*`

Why it looks stale:

- This is an inherited compatibility/stub area.
- If DelosDB first release does not support OSGi, this should be explicitly classified as compatibility-only, not active architecture.

Action:

- Keep unless module dependencies prove it can be removed. If kept, mark as legacy compatibility, not DelosDB extension platform.

### 3. Demos and old tools

Files/modules:

- `delosdb-demos/`
- `delosdb-tools/src/main/java/org/apache/derby/impl/tools/ij/*`
- `delosdb-tools/src/main/java/org/apache/derby/impl/tools/dblook/*`
- `delosdb-tools/src/main/java/org/apache/derby/impl/tools/sysinfo/*`

Why it looks stale:

- They are inherited Derby user-facing tools. They may still be useful, but they are not central to the DelosDB engine modernization.

Action:

- Do not rewrite now. Either keep as compatibility tools or split from the core modernization work.

### 4. Root-local generated junk

Observed in snapshot:

- `derby.log`

Action:

- It is local runtime output, not source. It should not be included in overlays. If cleaning workspace later, remove only project-owned local junk, never `.git`, `.gradle`, or `.idea`.

## Duplicate-code clusters

### Runtime statistics descriptor duplication

Files:

- `RealHashLeftOuterJoinStatistics.java`
- `RealNestedLoopLeftOuterJoinStatistics.java`
- `RealSetOpResultSetStatistics.java`
- `RealUnionResultSetStatistics.java`
- `RealJoinResultSetStatistics.java`
- `RealScalarAggregateStatistics.java`
- `RealSortStatistics.java`
- `RealGroupedAggregateStatistics.java`
- `RealTableScanStatistics.java`
- `RealLastIndexKeyScanStatistics.java`
- `RealHashScanStatistics.java`

Pattern:

- Repeated construction of `XPLAINResultSetDescriptor` with long positional argument lists.

Risk:

- Easy to introduce subtle mistakes when adding diagnostics or changing runtime statistics columns.

Suggested fix later:

- Introduce an internal descriptor builder or mapper for runtime statistics only. This is not a provider feature.

### Scan factory / result-set constructor duplication

Files:

- `BulkTableScanResultSet.java`
- `MultiProbeTableScanResultSet.java`
- `GenericResultSetFactory.java`

Pattern:

- Long repeated constructor/factory argument lists for scan result sets.

Risk:

- Fragile parameter ordering and poor readability.

Suggested fix later:

- Introduce an internal immutable scan specification object, but only after tests lock current behavior.

### Forward/backward B-tree search duplication

File:

- `ControlRow.java`

Pattern:

- Forward and backward page search logic share much of the same binary-search shape.

Risk:

- Bug fixes in one direction may not be applied to the other.

Suggested fix later:

- Do not refactor first. Add targeted B-tree scan/reposition regression tests, then reduce duplication.

### Provider registry family wrappers

Files:

- `CostModelProviderRegistry.java`
- `IndexProviderRegistry.java`
- `StorageProviderRegistry.java`
- `FunctionProviderRegistry.java`
- `TypeProviderRegistry.java`
- matching `*ProviderResolver.java` files

Pattern:

- These are now thin wrappers around shared `ProviderRegistry<P>` and `ProviderResolver<P>`, but the family wrapper pattern is still repetitive.

Risk:

- Low. The current design is acceptable because it keeps public family vocabulary clear.

Suggested fix later:

- Leave it alone for now. Do not generalize further until the first extension seams stabilize.

## Weak implementation candidates

### 1. Generated classfile version is extremely old

Files:

- `delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/VMDescriptor.java`
- `delosdb-engine/src/main/java/org/apache/derby/iapi/services/classfile/ClassHolder.java`

Evidence:

- `JAVA_CLASS_FORMAT_MAJOR_VERSION = 45`
- `JAVA_CLASS_FORMAT_MINOR_VERSION = 3`
- Comment says this corresponds to very old JDK 1.0.2-era classfiles.

Why it matters:

- This is probably the most important JVM 21 modernization target.
- The old format keeps Derby compatible but prevents a clean modern story around bytecode generation, verification, diagnostics, and modular generated classes.

Safe approach:

- First add a generated-class inspection smoke that creates a simple query plan, captures generated bytecode, and verifies classfile version.
- Then evaluate whether to keep v45.3 intentionally, bump generated bytecode, or replace reflective method dispatch with method handles while leaving classfile version unchanged.

### 2. Generated method dispatch still uses reflection fallback

Files:

- `ReflectGeneratedClass.java`
- `ReflectMethod.java`

Evidence:

- Direct calls exist for generated methods `e0` through `e9`.
- Other generated methods use `Method.invoke()` through `ReflectMethod`.

Why it matters:

- Java 21 HotSpot optimizes method handles and direct calls better than reflective invocation.
- Reflection is also noisier with modules and diagnostics.

Safe approach:

- Measure before changing.
- Add a micro-smoke around generated expression methods.
- Consider `MethodHandle` caching for non-`e0`..`e9` generated methods.

### 3. Module exports are too broad because generated plans live in the unnamed module

File:

- `delosdb-engine/src/main/java/module-info.java`

Evidence:

- Comment says packages are exposed so query plans generated into the unnamed module can access them.

Why it matters:

- This is a module-boundary smell, not a functional bug.
- It weakens the Java 21 modularization story.

Safe approach:

- Do not hide exports yet. First prove exactly which generated classes need which packages.
- Later, consider generated classes in a controlled DelosDB classloader/module strategy.

### 4. External sort memory sizing is old

File:

- `ExternalSortFactory.java`

Evidence:

- Default target memory is about 1 MB.
- Source has a `RESOLVE` comment about better sizing based on estimated rows/row size/free memory.

Why it matters:

- Java 21 servers normally run with larger heaps and container-aware memory limits.
- A fixed old-memory-era sort target may spill too early.

Safe approach:

- Add a sort benchmark first.
- Then make sort memory policy explicit and testable.

### 5. XML factory hardening is incomplete by modern standards

Files:

- `SqlXmlUtil.java`
- `XmlVTI.java`

Evidence:

- `FEATURE_SECURE_PROCESSING` is enabled.
- External general entities are disabled.
- The scan did not show the full modern set of XML hardening controls everywhere, such as external parameter entities, disallow-doctype-decl, and external DTD/schema access restrictions.

Why it matters:

- This is security-sensitive. Do not assume existing settings are enough.

Safe approach:

- Add XML parser hardening tests before changing parser options because XML compatibility may be affected.

### 6. Client non-locator CLOB code uses deprecated `StringBufferInputStream`

File:

- `delosdb-client/src/main/java/org/apache/derby/client/am/ClientClob.java`

Evidence:

- `reInitForNonLocator()` suppresses deprecation and uses `java.io.StringBufferInputStream`.
- Comment says the path is legacy and only for very old servers.

Why it matters:

- This is not an urgent engine issue, but it is stale Java API usage.

Safe approach:

- Keep if compatibility with old Derby network servers matters.
- Otherwise quarantine behind a compatibility flag or replace with byte-array streams using explicit charset behavior.

### 7. SecurityManager removal is mostly done, but names/comments remain

Files:

- `SecurityUtil.java`
- `DatabasePermission.java`
- `EmbedConnection.java`
- `InternalDriver.java`
- `SystemProcedures.java`

Evidence:

- Code comments say SecurityManager support is removed or now NOP.

Why it matters:

- Good modernization step. Remaining issue is clarity: legacy security APIs should be explicitly described as compatibility surfaces, not active protection.

Safe approach:

- Documentation and naming cleanup only. Do not remove public APIs casually.

## JVM 21 / HotSpot improvement backlog

Priority 1: generated bytecode and dispatch

- Inspect and document generated classfile version.
- Add a generated-plan bytecode smoke.
- Benchmark generated expression dispatch.
- Consider `MethodHandle` caching for reflective fallback methods.
- Consider a modern generated-class loader/module boundary instead of broad module exports.

Priority 2: synchronization and concurrency hotspots

Highest-risk files by size/synchronization:

- `LogToFile.java` — large, synchronization-heavy, correctness-critical.
- `FileContainer.java`
- `BaseDataFileFactory.java`
- `RawStore.java`
- `Xact.java`
- `EmbedConnection.java`
- `EmbedStatement.java`
- `ConcurrentLockSet.java`

Action:

- Do not mechanically replace `synchronized`.
- Use JFR/JMH/stress tests to find real contention first.
- Prefer targeted changes: cache maps, generated method caches, and safe immutable metadata first.

Priority 3: old collection cleanup

Observed in stripped source:

- Engine: 228 `StringBuffer` references, 16 `Stack` references, a few `Vector`/`Hashtable` references.
- Client: 385 `synchronized` references, 35 `StringBuffer` references, 9 `Stack` references.
- Tests: many `Vector` and old harness usages.

Action:

- Do not bulk-convert.
- Convert only local, non-public, non-serialized structures.
- Do not change `FormatableHashtable` or other on-disk/serialized compatibility structures without a format migration plan.

Priority 4: sort and memory policy

- Benchmark old 1 MB default sort behavior under Java 21.
- Add container-aware/heap-aware sort memory policy later.
- Keep old default behavior until performance tests prove an improvement.

Priority 5: I/O modernization

Potential areas:

- `LogAccessFile.java`
- `LogToFile.java`
- `RAFContainer.java`
- `BaseDataFileFactory.java`

Action:

- NIO/FileChannel/MappedByteBuffer changes are dangerous without crash tests.
- Start with observability and benchmark harness, not implementation changes.

## Recommended next overlays

### Overlay A: static-analysis guardrails only

Goal:

- Add scripts/checks that report stale patterns without changing runtime code.

Possible contents:

- `scripts/scan-inherited-derby-hotspots.sh`
- Gradle verification task that reports:
  - generated classfile version constants
  - forbidden new `finalize()` methods
  - new `StringBufferInputStream` usage
  - new broad module exports
  - old harness dependency list

Why first:

- Safe, proof-driven, no runtime behavior change.

### Overlay B: classify stale test harness

Goal:

- Prove which old harness files are used by active Gradle tests.
- Mark unused harness areas as quarantined or remove only if proven unused.

### Overlay C: generated bytecode inspection smoke

Goal:

- Create one test/smoke that prepares a query, captures generated activation class metadata, and reports classfile version and dispatch path.

### Overlay D: XML hardening tests

Goal:

- Add tests for XXE/DTD behavior before changing XML parser settings.

## Not recommended now

- Do not rewrite the optimizer.
- Do not replace the B-tree implementation.
- Do not change log/recovery I/O.
- Do not bulk-convert `synchronized` to locks.
- Do not bulk-convert old collections.
- Do not remove old test harness files until Gradle task dependency is proven.
- Do not add new provider families.
