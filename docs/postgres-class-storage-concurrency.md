# PostgreSQL-class storage and concurrency direction

This note defines the next DelosDB source-check campaign after the inherited
Derby code-quality cleanup. It is not a new provider family and it is not an
MVCC implementation. It is the source trail for moving DelosDB toward a serious
storage/concurrency architecture while preserving Derby compatibility.

## Six pillars

### 1. MVCC is the long-term concurrency direction

Current DelosDB inherits Derby's lock-based concurrency model. MVCC is not a
switch that can be added at the SQL surface. It affects row format,
transaction identity, visibility checks, indexes, undo/redo, checkpointing,
recovery, and cleanup of old versions.

Initial DelosDB work should therefore expose the current transaction and
visibility boundaries before adding versioned rows.

Source trail to inspect first:

```text
org.apache.derby.impl.store.raw.xact
org.apache.derby.impl.store.raw.data
org.apache.derby.impl.store.access.RAMTransaction
org.apache.derby.impl.store.access.heap
org.apache.derby.impl.store.access.btree
```

### 2. WAL/recovery is a core subsystem

DelosDB must treat WAL, checkpoint, undo, redo, and restart recovery as core
architecture. The first proof is deliberately small: a dirty restart must replay
committed work and roll back the transaction that was active when the process
exited.

Proof task:

```bash
./gradlew :delosdb-tests:runWalRecoveryArchitectureProofTest
```

The test pins down the current Derby contract that future MVCC work must
preserve.

### 3. Optimizer work needs path infrastructure

`CostModelProvider` v2 proves that DelosDB can route cost decisions through a
controlled extension point. That is not yet PostgreSQL-class path planning.
Future optimizer work needs explicit scan, index, join, sort, row-count, and
cost-vector concepts before any rewrite of Derby enumeration is attempted.

Current source trail:

```text
org.apache.derby.impl.sql.compile.FromBaseTable
org.apache.derby.impl.sql.compile.OptimizerImpl
org.apache.derby.iapi.store.access.StoreCostController
io.github.ggeorg.delosdb.engine.extension.cost
```

First observability proof:

```bash
./gradlew optimizerPathObservabilitySmoke
```

This smoke keeps Derby enumeration unchanged. It records the selected runtime
scan shape from Derby runtime statistics and prints a stable
`DelosDBOptimizerPath{...}` diagnostic line beside the native
`StoreCostControllerBridge` `CostModelProvider` v2 probe. The purpose is to make
path facts visible before introducing any richer path/cost model.

### 4. Indexing needs careful concurrency and latching design

DelosDB has only touched page-local B-tree search cleanup after boundary proof
tests. It must not refactor page split, latch, reposition, delete/compact, or
scan traversal behavior without stronger proof coverage.

Proof tasks:

```bash
./gradlew :delosdb-tests:runBTreeSearchRefactorProofTests
./gradlew :delosdb-tests:runBTreeIndexConcurrencyArchitectureProofTest
```

`runBTreeSearchRefactorProofTests` protects page-local search, max-scan,
and split/deadlock boundaries. `runBTreeIndexConcurrencyArchitectureProofTest`
protects the SQL-visible contract around unique-index conflicts, rollback of
indexed inserts, and committed duplicate-key rejection. This still does not
inspect page latches directly; latches are a source-level mechanism. The proof
pins down the transactional behavior that latch and logical-lock changes must
preserve.

Source trail:

```text
org.apache.derby.impl.store.access.btree.ControlRow
org.apache.derby.impl.store.access.btree.LeafControlRow
org.apache.derby.impl.store.access.btree.BranchControlRow
org.apache.derby.impl.store.access.btree.BTreeController
org.apache.derby.impl.store.access.btree.BTreeForwardScan
```

### 5. Vacuum/version cleanup belongs to MVCC design

Vacuum is not a later garbage collector. Once versioned rows exist, DelosDB will
need a crash-safe cleanup model tied to the oldest active transaction, index
entries that may point at dead versions, and scans that may still need older
versions.

For now this remains design-only because there are no row versions yet.

### 6. Runtime/executor code must stay evolvable

The inherited runtime cleanup campaign reduced fragile constructor plumbing,
runtime-statistics descriptor construction, generated-method dispatch, and sort
policy coupling. This is the foundation that lets future visibility and
access-path work be introduced in explicit places instead of through scattered
constructor arguments and hidden side effects.

Current proof aggregation:

```bash
./gradlew :delosdb-tests:runPostgresClassArchitectureProofTests
```

## PostgreSQL comparison checkpoint

PostgreSQL should be used as the reference architecture for source study, not as
a direct Java port target. The next comparison pass should focus on source
boundaries, not feature lists:

```text
PostgreSQL MVCC visibility:     src/backend/access/heap/heapam_visibility.c
PostgreSQL WAL/recovery:        src/backend/access/transam/xlog.c
PostgreSQL optimizer costing:   src/backend/optimizer/path/costsize.c
PostgreSQL B-tree search:       src/backend/access/nbtree/nbtsearch.c
PostgreSQL lock manager:        src/backend/storage/lmgr/lock.c
```

The DelosDB question is not "can Derby become PostgreSQL?" The useful question
is: which source boundaries must DelosDB expose and clean before PostgreSQL-class
ideas can be engineered safely in a Java-native, Derby-compatible engine?
