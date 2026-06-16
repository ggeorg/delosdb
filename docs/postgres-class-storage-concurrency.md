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

### SQL metadata guard

`CREATE TABLE ... USING delos_mvcc` is intentionally a guarded SQL surface at this stage. The name is recognized through the versioned-storage provider family, but the statement must fail cleanly until the executor can route table creation, inserts, and table scans into the MVCC provider. This prevents a dangerous fallback where a Derby heap table is created while metadata suggests MVCC semantics.


### Phase 4 checkpoint: first SQL table-scan proof

`CREATE TABLE ... USING delos_mvcc` has moved from a metadata guard to the first
real SQL/JDBC table-scan proof. The supported path is intentionally small:
create a simple `delos_mvcc` table, insert committed rows, and read them back via
`SELECT *` or `COUNT(*)`. This proves the engine can route a user-visible SQL
surface into the versioned-storage provider without reinterpreting existing Derby
heap tables.

The boundary remains strict: no index integration, no WAL/recovery replay, no
optimizer path work, and no Derby transaction lifecycle mapping yet. Those remain
separate phases.


### Phase 6: provider-local MVCC recovery log

`delosdb-storage-mvcc` now includes a narrow append-only recovery log owned by
the experimental provider. The log records table creation, row insert/update/delete
operations, and transaction commit/abort boundaries. Reopening the provider
replays only committed transactions and ignores aborted or incomplete transactions.

This is intentionally not Derby WAL. It is a stepping stone that lets the MVCC
provider prove recovery semantics before DelosDB decides how versioned-storage
records should participate in the full database recovery subsystem.

### Phase 7 checkpoint: primary-key / unique conflict proof

`delos_mvcc` now proves the first uniqueness semantics above the table-scan
storage path. Column-level `PRIMARY KEY` and `UNIQUE` declarations are recognized
by the experimental SQL bridge. Inserts reserve unique values in the owning
MVCC transaction; committed reservations reject duplicates, active reservations
block concurrent duplicates, and rollback releases the reservation.

This is intentionally not the final index design. It does not add a B-tree,
secondary-index storage, optimizer path selection, or Derby heap changes. It is
a behavior checkpoint before real index structures are attached to MVCC rows.

### Phase 8 checkpoint: provider-owned MVCC index model

`delos_mvcc` now has the first provider-owned index structure. The index stores
candidate row identifiers and always rechecks MVCC table/version visibility before
returning a row. This follows the PostgreSQL design rule that an index entry is
not independently visible; the heap/version chain remains authoritative.

The SQL proof is intentionally narrow: `CREATE INDEX ... ON mvcc_table(column)`,
`SELECT * ... WHERE column = literal`, plus indexed `UPDATE` and `DELETE` through
the experimental bridge. The index is not Derby B-tree integration, does not add
optimizer costing, and does not reinterpret Derby heap pages.

### Phase 9 checkpoint: PostgreSQL-style snapshot semantics

`delos_mvcc` now separates transaction-stable snapshots from statement-fresh
snapshots. This follows the PostgreSQL design point that `READ COMMITTED` uses a
new snapshot for each statement, while `REPEATABLE READ` holds a stable snapshot
for the transaction.

The provider exposes this through a transaction-context refresh primitive. The
SQL bridge uses the current JDBC isolation level to choose between:

- statement-fresh snapshots for `READ COMMITTED` / `READ UNCOMMITTED`; and
- transaction-stable snapshots for `REPEATABLE READ` / `SERIALIZABLE`.

The storage proof also verifies that a refreshed statement view keeps the same
transaction identity, so own writes remain visible, while rows written by another
active transaction remain invisible.

### Phase 10 checkpoint: MVCC cleanup / vacuum

`delos_mvcc` now has a provider-local cleanup pass that follows the PostgreSQL
visibility rule for dead tuple removal: a physical version may be removed only
when no active snapshot can still see it. The same rule is applied to
provider-owned index candidates. Index entries are pruned only after the
version chain no longer contains a visible-or-snapshot-protected value for that
index key.

The proof covers update cleanup, committed-delete cleanup, aborted-insert
cleanup, logical row removal, dead-version estimates, and index-candidate
pruning. This is still a manual provider-local cleanup pass, not a background
vacuum daemon and not Derby heap page compaction.

### Phase 11 checkpoint: recovery hardening and compact checkpoint image

`delos_mvcc` now hardens its provider-local recovery log. The implementation is
still deliberately separate from Derby WAL, but it follows the PostgreSQL-guided
recovery rule that a durable commit boundary decides which changes are replayed.
Recovery now treats repeated commit records idempotently, ignores an incomplete
final record as a torn log tail, and keeps aborted or incomplete transactions out
of the recovered image.

The provider also has a conservative checkpoint operation. It refuses to compact
while provider-local transactions are active, because old snapshots may still
need older physical versions. When safe, it rewrites the append-only log into a
compact committed image containing table metadata and currently visible rows.
This is a prototype checkpoint/compaction step, not Derby log integration, but it
creates the next foundation for crash-safety work before indexes and optimizer
costing are made durable.

### Phase 12 checkpoint: write/write conflict behavior

`delos_mvcc` now exposes write-conflict behavior through the versioned-storage
SPI and the experimental JDBC bridge. The provider follows the PostgreSQL-guided
rule that readers do not block writers, but competing writers cannot both modify
the same visible row version. An active writer/delete reserves the version by
marking its delete boundary; a second writer that tries to update the same row
gets a provider-neutral `VersionedWriteConflictException`, which the SQL bridge
maps to SQLState `40XL1`.

Rollback releases the conflict because the delete boundary belongs to an aborted
transaction. Commit makes the new version authoritative for fresh snapshots,
while stale snapshots may still read the old version but may not overwrite it.
This remains provider-local MVCC behavior; it is not Derby lock-manager
integration and not blocking wait-queue support yet.

### Phase 13 checkpoint: MVCC table-scan vs index-scan path observability

`delos_mvcc` now exposes the first optimizer-facing statistics for provider-owned
MVCC access paths. The SQL bridge chooses between a provider-owned table scan and
a provider-owned index scan for simple equality predicates. Index statistics
report both raw candidate counts and visible matches after rechecking the MVCC
version chain. This preserves the PostgreSQL-guided rule that index entries are
candidate TIDs/row identifiers, while table/version visibility remains the final
authority.

The recorded path includes visible row count, physical version count,
dead-version estimate, index candidate count, visible index match count, and
rough table-scan vs index-lookup cost estimates. This is intentionally a bridge
checkpoint, not full Derby optimizer path enumeration yet.
