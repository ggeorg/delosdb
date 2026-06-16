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

Current DelosDB has no MVCC row versions yet, so the first proof is a
visibility/cleanup boundary test against the inherited lock-based store. It pins
down what future MVCC visibility and vacuum must preserve: rolled-back indexed
updates must not leave the new key searchable, committed indexed updates must
move the visible key, rolled-back deletes must preserve the visible key, and
committed deletes must remove the row from both heap-visible and index-visible
access paths.

Proof task:

```bash
./gradlew :delosdb-tests:runVersionCleanupArchitectureProofTest
```

This is not vacuum and it is not MVCC. It is the SQL-visible contract that a
future version store and cleanup process must keep true.

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

## Experimental MVCC storage module checkpoint

The MVCC implementation starts as an isolated module, not as a mutation of
Derby heap storage:

```text
delosdb-storage-mvcc
```

The module owns the first in-memory model for:

```text
- transaction ids
- commit sequences
- snapshots
- row-version chains
- visibility checks
- cleanup safety based on the oldest active snapshot
```

This is intentionally not `CREATE TABLE ... USING delos_mvcc` yet. SQL wiring,
Derby heap integration, WAL record integration, index integration, and recovery
replay are later steps. Existing Derby databases continue to open through the
Derby-compatible heap path.

Proof task:

```bash
./gradlew :delosdb-storage-mvcc:runMvccCoreModelTest
```

This checkpoint keeps the extensibility direction honest: MVCC is being built as
an opt-in storage implementation, not as a silent reinterpretation of existing
Derby tables.


### MVCC table-scan model proof

The experimental `delosdb-storage-mvcc` module now includes a table-scan model proof.
`MvccTable.openScan(...)` materializes the rows visible to a captured snapshot and
returns them through `MvccScan`. This remains intentionally independent of Derby
heap, B-tree, WAL, and SQL execution. The proof task is:

```bash
./gradlew :delosdb-storage-mvcc:runMvccTableScanModelTest
```

The proof covers stable snapshot scans, update/delete visibility, aborted rows,
cleanup interaction, and scan cursor contracts. This is the next step toward an
opt-in `delos_mvcc` storage family without risking existing Derby database
compatibility.

### VersionedStorageProvider SPI skeleton

The MVCC storage module now implements the first narrow DelosDB
`VersionedStorageProvider` SPI boundary. This is not yet SQL execution. It is a
provider contract that proves the MVCC model can sit outside `delosdb-engine` and
be opened through a storage-provider shape:

```text
VersionedStorageProvider
  -> VersionedTable
      -> VersionedScan
      -> TxContext / TxView
```

The experimental provider is named `delos_mvcc`. It supports snapshot visibility,
table scans, manual cleanup, and an in-memory prototype capability. The provider
boundary is intentionally small so MVCC does not become a collection of unrelated
provider families before the storage model is stable.

Proof task:

```bash
./gradlew :delosdb-storage-mvcc:runVersionedStorageProviderSpiTest
```

This keeps the migration story safe: existing Derby-compatible heap tables still
open through the existing heap path, while future `delos_mvcc` tables can use the
new versioned-storage boundary explicitly.

### VersionedStorageProvider registry checkpoint

The experimental `delos_mvcc` provider can now be registered and resolved through
an engine-side versioned-storage provider registry. This is still deliberately
short of SQL execution. The checkpoint proves the extension boundary can describe
an opt-in MVCC provider without adding MVCC logic to Derby heap storage:

```text
ExtensionType.VERSIONED_STORAGE
  -> VersionedStorageProviderRegistry
  -> VersionedStorageProviderResolver
  -> delos_mvcc descriptor and capabilities
```

Focused proof task:

```bash
./gradlew versionedStorageProviderRegistrySmoke
```

The next safe step after this checkpoint is SQL metadata handling for
`CREATE TABLE ... USING delos_mvcc`, initially rejected or metadata-only until the
executor bridge is intentionally added.

### Experimental MVCC SQL metadata guard

The experimental `delosdb-storage-mvcc` module is now visible through the
`VersionedStorageProvider` registry as `delos_mvcc`. SQL recognition remains
intentionally conservative: `CREATE TABLE ... USING delos_mvcc` is recognized
as a versioned-storage provider name, but it fails with a clear
"SQL execution is not implemented yet" diagnostic until the executor bridge
exists. This prevents DelosDB from accidentally creating a Derby heap table
whose metadata claims MVCC storage.


### Versioned-storage execution bridge checkpoint

DelosDB now has an engine-side `VersionedStorageExecutionBridge` that can drive a
registered `VersionedStorageProvider` for table-only operations without wiring
SQL execution. The bridge proves the next boundary in the path to executable
MVCC storage:

```text
VersionedStorageProviderResolver
  -> VersionedStorageExecutionBridge
  -> VersionedTable create/open
  -> insert/read/update/delete/scan/stats
```

The focused proof uses the experimental `delos_mvcc` provider and verifies
snapshot-stable scans across insert, committed update, and aborted delete. This
is still intentionally below Derby SQL/JDBC: it does not modify heap storage,
create catalog-backed MVCC tables, add WAL records, or integrate indexes.

Focused proof task:

```bash
./gradlew versionedStorageExecutionBridgeSmoke
```
