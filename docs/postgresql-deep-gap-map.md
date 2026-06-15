# PostgreSQL-class Storage and Execution Gap Map

This note compares DelosDB's inherited Derby architecture with the PostgreSQL source tree uploaded for analysis. It is not a claim that DelosDB should copy PostgreSQL. PostgreSQL is the reference system for the kind of mature database architecture DelosDB can study while preserving Derby compatibility.

The uploaded PostgreSQL source tree has this rough shape:

```text
C source files:        1,544
C header files:        1,020
SQL regression files:    855
SGML documentation:      430
```

That scale matters. PostgreSQL is not only an SQL executor. It is a coordinated storage, transaction, recovery, optimizer, executor, vacuum, and index-concurrency system. DelosDB's current work should therefore focus on source understanding and safe boundary proofs before attempting deep architectural rewrites.

## Executive Summary

| Pillar | PostgreSQL shape | Current Derby/DelosDB shape | Current DelosDB gap | Safe next DelosDB step |
|---|---|---|---|---|
| MVCC | Tuple headers carry transaction visibility; snapshots decide visibility. | Lock-based concurrency; committed delete/purge behavior; no row-version visibility model. | No MVCC row format, no snapshot visibility engine, no version chain. | Add a transaction/visibility source map and SQL-visible visibility proof matrix. |
| WAL/recovery | WAL is a central subsystem with resource managers, transaction status, redo, checkpoints, and recovery. | Derby has a real log/recovery system, but DelosDB has not exposed it as an architecture seam. | Recovery is still inherited and under-mapped. | Map commit/abort/log record/checkpoint/restart flow before any behavior change. |
| Optimizer paths | Explicit `Path`/`RelOptInfo`/cost infrastructure. | Derby has `AccessPath`, `CostEstimate`, `StoreCostController`, and optimizer enumeration. | CostModelProvider v2 is useful, but not yet a richer path model. | Extend path observability toward selected path records and later path objects. |
| Index concurrency/latching | B-tree code has explicit concurrency algorithms, page movement, uniqueness checks, deletion, and vacuum cooperation. | Derby B-tree has latches, logical locks, split/search/delete behavior; only lightly cleaned. | Latch/lock/search/split protocol is not yet source-mapped deeply enough for redesign. | Build a B-tree latch/lock source map and avoid deeper refactoring until covered. |
| Vacuum/version cleanup | Vacuum/pruning is tied to visibility horizon and index cleanup. | Derby purges committed deleted rows opportunistically; no MVCC vacuum. | No version cleanup subsystem because there are no MVCC versions yet. | Define version cleanup concepts only after transaction visibility model exists. |
| Runtime/executor | Plan-state executor with explicit scan/sort/join nodes. | Derby generated activation/result-set tree; now cleaner after constructor cleanup. | Runtime is cleaner, but visibility/access-path context is not first-class. | Carry more access-path and transaction/visibility context into scan/result-set diagnostics. |

## 1. MVCC and Visibility

### PostgreSQL source anchors

```text
src/include/access/htup_details.h
src/include/utils/snapshot.h
src/backend/access/heap/heapam_visibility.c
src/backend/access/heap/heapam.c
src/backend/utils/time/snapmgr.c
src/backend/storage/ipc/procarray.c
src/backend/access/transam/clog.c
src/backend/access/transam/xact.c
```

PostgreSQL's MVCC is not an isolated feature. The tuple header has transaction identity fields such as `t_xmin` and `t_xmax`, and tuple chains are connected through `t_ctid`. Snapshot state is represented explicitly with `xmin`, `xmax`, and transaction arrays. Visibility is decided through functions such as:

```text
HeapTupleSatisfiesMVCC(...)
HeapTupleSatisfiesUpdate(...)
HeapTupleSatisfiesVacuum(...)
HeapTupleSatisfiesVisibility(...)
```

The important point is that tuple visibility is a normal path through the heap access method. It is not only a lock-manager concern.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/Xact.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/RAMTransaction.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/HeapController.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/Heap.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeController.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeForwardScan.java
```

Derby has transaction objects, lock policies, committed-delete purge behavior, and row/index visibility through lock and delete state. It does not have PostgreSQL-style row versions with snapshot visibility.

### Gap

DelosDB does not yet have:

```text
- a row-version identity
- tuple xmin/xmax equivalent
- snapshot object for SQL visibility
- version chains
- oldest-active-transaction horizon
- index entries that cooperate with MVCC visibility
```

### Safe next DelosDB work

Do not implement MVCC yet. First add a source map and proof matrix for current visibility behavior:

```text
- committed insert visibility
- rolled-back insert invisibility
- committed update old-key/new-key visibility
- rolled-back update visibility
- committed delete invisibility
- rolled-back delete visibility
- index path and heap path agreement
- behavior across restart recovery
```

That produces the baseline a future MVCC layer must preserve.

### Danger zone

Do not change row format, heap page layout, B-tree entries, or transaction IDs until WAL/recovery and index behavior are mapped.

## 2. WAL and Recovery

### PostgreSQL source anchors

```text
src/backend/access/transam/xlog.c
src/backend/access/transam/xloginsert.c
src/backend/access/transam/xlogreader.c
src/backend/access/transam/xlogrecovery.c
src/backend/access/transam/rmgr.c
src/backend/access/transam/xact.c
src/backend/access/transam/clog.c
src/backend/access/rmgrdesc/heapdesc.c
src/backend/access/rmgrdesc/nbtdesc.c
src/backend/access/rmgrdesc/xactdesc.c
```

PostgreSQL treats WAL as a first-class subsystem. WAL insertion, log reading, recovery, transaction status, and resource-manager-specific descriptions are explicit. Heap and B-tree operations are tied into WAL through resource manager records and replay logic.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/LogToFile.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/FileLogger.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/LogRecord.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/log/CheckpointOperation.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/Xact.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/BeginXact.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/EndXact.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/TransactionTable.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/data/BaseDataFileFactory.java
```

Derby has real WAL/recovery machinery. `LogToFile` describes a non-circular file-backed log with checkpoint behavior and crash recovery. `Xact` tracks transaction state and log positions. Loggable operations and undo operations already exist.

### Gap

DelosDB currently treats this mostly as inherited machinery. The architecture is not yet explicit enough to support MVCC work.

Missing DelosDB-level maps:

```text
- where transaction begin/commit/abort records are written
- where transaction status is durable
- how checkpoints define restart points
- how logical undo differs from physical undo
- how heap and B-tree changes become log records
- which recovery operations are idempotent
- which invariants must hold before page/version changes
```

### Safe next DelosDB work

Next code/proof campaign should be WAL/recovery mapping, not MVCC:

```text
1. Add focused dirty-restart tests with insert/update/delete/index cases.
2. Add source comments only at stable subsystem boundaries.
3. Add a developer report that maps log record classes to SQL-visible effects.
4. Avoid changing log format.
```

### Danger zone

Do not change log record format, transaction table semantics, checkpoint rules, or undo behavior casually. Any future MVCC row-version design must be recoverable before it is useful.

## 3. Optimizer Path Infrastructure

### PostgreSQL source anchors

```text
src/include/nodes/pathnodes.h
src/backend/optimizer/plan/planner.c
src/backend/optimizer/path/allpaths.c
src/backend/optimizer/path/costsize.c
src/backend/optimizer/path/indxpath.c
src/backend/optimizer/path/joinrels.c
src/backend/optimizer/util/pathnode.c
src/backend/optimizer/util/relnode.c
```

PostgreSQL has explicit path data structures: `PlannerInfo`, `RelOptInfo`, `Path`, `IndexPath`, `JoinPath`, `NestPath`, `MergePath`, and `HashPath`. Costing is not just a number returned by storage. It is part of a path search space.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/OptimizerImpl.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/FromBaseTable.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/AccessPathImpl.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/CostEstimateImpl.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/HashJoinStrategy.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/NestedLoopJoinStrategy.java
delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/cost/CostModelProbe.java
```

Derby already has a real optimizer and access path concept. DelosDB has improved cost-model routing through CostModelProvider v2 and has added optimizer path observability around heap versus B-tree selection.

### Gap

DelosDB does not yet have a clean PostgreSQL-style path vocabulary:

```text
- no DelosDB-level `Path` object family
- no stable path record for external diagnostics
- no extensible scan path hierarchy
- no cost vector beyond inherited cost estimates and provider estimates
- no explicit selectivity/cardinality extension point
```

### Safe next DelosDB work

Do not rewrite optimizer enumeration yet. First make the existing decisions visible:

```text
- selected conglomerate
- access-method family
- index name
- join strategy
- estimated rows
- estimated cost
- consumed provider cost
- predicates considered for the path
```

The next code step can introduce a diagnostic-only immutable `DelosDbOptimizerPath` record used by smoke tests and traces. It should not drive planning yet.

### Danger zone

Do not replace Derby's optimizer enumeration until the current access-path decisions are fully observable and reproducible.

## 4. Index Concurrency and Latching

### PostgreSQL source anchors

```text
src/backend/access/nbtree/README
src/backend/access/nbtree/nbtsearch.c
src/backend/access/nbtree/nbtinsert.c
src/backend/access/nbtree/nbtpage.c
src/backend/access/nbtree/nbtutils.c
src/backend/access/nbtree/nbtree.c
```

PostgreSQL's B-tree implementation is explicit about concurrency. The README discusses Lehman-Yao style behavior, right links, page locks, page splits, deletion, suffix truncation, deduplication, and the interlocking needed to avoid incorrect scans.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/ControlRow.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BranchControlRow.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/LeafControlRow.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeController.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeForwardScan.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeMaxScan.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeLockingPolicy.java
```

DelosDB has only made a page-local B-tree search helper cleanup and added proof coverage for boundary scans, max scans, split/deadlock tests, and index-concurrency SQL contracts. That is the right limit for now.

### Gap

DelosDB does not yet have a clear source-level map of:

```text
- latch acquisition order
- page split protocol
- sibling movement protocol
- scan repositioning rules
- logical locks versus physical latches
- delete/purge interaction with active scans
- index uniqueness conflict behavior under concurrent transactions
```

### Safe next DelosDB work

Create an index concurrency source map before any further code refactor:

```text
1. Map page-local search.
2. Map leaf split.
3. Map branch split and parent insertion.
4. Map right/left sibling movement.
5. Map scan reposition after latch release.
6. Map unique-key conflict handling.
```

Add tests only where the map exposes missing coverage.

### Danger zone

Do not touch page split, latch ordering, scan traversal, delete compaction, or repositioning until these maps and tests are in place.

## 5. Vacuum and Version Cleanup

### PostgreSQL source anchors

```text
src/backend/access/heap/vacuumlazy.c
src/backend/access/heap/pruneheap.c
src/backend/access/heap/heapam_visibility.c
src/backend/commands/vacuum.c
src/backend/commands/analyze.c
src/backend/access/nbtree/nbtpage.c
src/backend/access/nbtree/nbtinsert.c
```

PostgreSQL vacuum is not generic garbage collection. It is tied to tuple visibility, transaction horizons, index cleanup, heap pruning, and page-level conditions.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/heap/HeapController.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/access/btree/BTreeController.java
delosdb-engine/src/main/java/org/apache/derby/impl/store/raw/xact/Xact.java
```

Derby has committed-delete purge and B-tree reclaim behavior. That is not MVCC vacuum. It is cleanup under a lock-based model.

### Gap

DelosDB does not yet have:

```text
- version cleanup horizon
- dead version classification
- index entries pointing to multiple row versions
- pruning of version chains
- vacuum/recovery cooperation
- vacuum/index concurrency rules
```

### Safe next DelosDB work

Keep the current version-cleanup proof tests. Add no vacuum code yet. The next prerequisite is MVCC vocabulary and transaction horizon mapping.

A safe design skeleton later would define interfaces or records such as:

```text
VersionVisibilityDecision
VersionCleanupCandidate
OldestActiveTransactionSnapshot
VersionCleanupHorizon
```

But these should remain diagnostic/model concepts until heap row-version storage exists.

### Danger zone

Do not implement vacuum before row versions and visibility are designed. Vacuum cannot be correct if visibility is not first-class.

## 6. Runtime and Executor Evolution

### PostgreSQL source anchors

```text
src/include/nodes/plannodes.h
src/include/nodes/execnodes.h
src/backend/executor/execProcnode.c
src/backend/executor/nodeSeqscan.c
src/backend/executor/nodeIndexscan.c
src/backend/executor/nodeSort.c
src/backend/executor/nodeNestloop.c
src/backend/executor/nodeHashjoin.c
```

PostgreSQL execution has explicit plan nodes and plan state. The executor calls plan-state nodes through a consistent execution interface.

### Current Derby/DelosDB anchors

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/TableScanResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/IndexRowToBaseRowResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/HashScanResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/SortResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/JoinResultSet.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectGeneratedClass.java
delosdb-engine/src/main/java/org/apache/derby/impl/services/reflect/ReflectMethod.java
```

DelosDB has substantially cleaned this area: result-set constructors now use named parameter objects in many paths, runtime statistics descriptor builders are centralized, generated bytecode targets classfile 50.0, and fallback generated-method dispatch uses MethodHandle.

### Gap

Derby execution is still activation/result-set-tree based rather than PostgreSQL-style plan-state based. That is acceptable for compatibility, but future MVCC and optimizer work need better context flow.

Missing DelosDB-level executor concepts:

```text
- selected path object carried into execution diagnostics
- scan visibility context
- transaction visibility context
- per-scan heap-visible/index-visible counters
- explicit scan opening record for heap/index/provider path
```

### Safe next DelosDB work

The next executor work should not rewrite execution. It should add context and diagnostics:

```text
- expose selected path id/name in runtime statistics
- expose access-method family in scan result-set stats
- count rows rejected by qualifiers separately from rows skipped by visibility later
- prepare for visibility counters without changing visibility behavior
```

### Danger zone

Do not replace Derby activation/result-set execution wholesale. Keep compatibility and evolve the runtime around explicit context.

## DelosDB Priority Order After This Comparison

### Campaign A: WAL/recovery source map, second pass

```text
Goal: make recovery boundaries explicit before MVCC.
Targets:
- LogToFile
- FileLogger
- LogRecord
- Xact
- EndXact
- CheckpointOperation
- TransactionTable
Deliverables:
- source map
- dirty-restart tests for insert/update/delete/index cases
- no log-format change
```

### Campaign B: optimizer path records

```text
Goal: move from cost-only diagnostics toward path infrastructure.
Targets:
- FromBaseTable
- AccessPathImpl
- CostEstimateImpl
- CostModelProbe
Deliverables:
- diagnostic DelosDbOptimizerPath record
- selected path emitted from smoke tests
- no optimizer enumeration rewrite
```

### Campaign C: B-tree latch/lock source map

```text
Goal: understand page concurrency before changing it.
Targets:
- ControlRow
- LeafControlRow
- BranchControlRow
- BTreeController
- BTreeForwardScan
Deliverables:
- latch/lock map
- split/reposition proof coverage
- no latch/split behavior change
```

### Campaign D: MVCC design skeleton

```text
Goal: introduce vocabulary, not storage changes.
Deliverables:
- transaction visibility vocabulary
- version cleanup vocabulary
- mapping from current Derby behavior to future MVCC behavior
- no row-format change
```

## Bottom Line

PostgreSQL confirms that DelosDB's long-term architecture must treat MVCC, WAL/recovery, optimizer paths, B-tree concurrency, vacuum, and executor context as one system. The current DelosDB code cleanup made the runtime more evolvable. The next serious step is not to implement MVCC. It is to make WAL/recovery and visibility boundaries explicit enough that MVCC can be engineered safely later.
