# DelosDB Roadmap

DelosDB is a Java-native, Derby-compatible database platform for building and
researching database capabilities against a real SQL engine.

North star:

```text
A Java developer can implement a new database capability — an index type,
storage model, function, type, or cost model — and run it against a real SQL
engine, while DelosDB opens and improves the inherited Derby engine where the
existing seams are too narrow.
```

## Current rule

Finish existing seams before opening new ones.

Do not start `RewriteRuleProvider`, `ExternalTableProvider`,
`SecurityPolicyProvider`, or new `TypeProvider` semantics while the existing
provider surfaces still need hardening.

Workspace metadata is not a cleanup target. Local workspace ZIP snapshots may
contain `.git/`, `.gradle/`, and `.idea/`. Reviewers must ignore those
directories. Cleanup scripts must never delete them.

## Finished seams

### CostModelProvider v2

Status: finished seam, green locally.

Active native path:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProviderResolver
  -> CostModelProvider
```

Proven implementations:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

The old `FromBaseTable` / `IndexProviderCostBridge` path is legacy
optimizer-side diagnostic history only. Native provider-cost consumption belongs
to the store-cost adapter.

Known boundary: `CostModelEstimate.startupCost()` is captured and validated, but
Derby's `StoreCostResult` can propagate only total cost and estimated row count.

### IndexProvider v2

Status: finished abstraction proof, green locally.

Proven implementations:

```text
index btree  -> Derby-compatible SQL-backed index provider
index memory -> provider-owned runtime operations proof
```

`btree` remains the only SQL-creatable index provider. `memory` is visible in the
registry and has its own runtime proof, but `CREATE INDEX ... USING memory` is
intentionally rejected until a real Derby executor/storage bridge exists.

## Shallow seams deliberately frozen

- `StorageProvider`: heap-only provider surface; no second storage engine yet.
- `FunctionProvider`: built-in DelosDB function surface; no external function
  loading yet.
- `TypeProvider`: metadata-only Derby type visibility; no parser, binder, type
  system, or storage-format changes yet.

## Current green gates

```bash
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

Broader checks:

```bash
./gradlew fullVerification
./dev/modernization-audit.sh --verify
./dev/benchmark-baseline.sh
```

## Current product seams

Implemented and green locally:

- Derby-compatible SQL/JDBC baseline through Gradle.
- `CostModelProvider` v2 through heap and B-tree cost providers.
- `IndexProvider` v2 through B-tree and memory providers.
- `StorageProvider` heap-only surface with provider-level capabilities.
- `FunctionProvider` built-in DelosDB function surface.
- `TypeProvider` metadata-only SQL visibility.
- Unified extension registry through `SYSCS_UTIL.DELOSDB_EXTENSIONS()`.
- Type metadata visibility through `SYSCS_UTIL.DELOSDB_TYPES()`.
- DelosDB system-routine test baseline through `DelosDbTestBaselines`.

## Book verification rule

No new book chapter should be added until existing cited chapters stay
source-checked. Current status: Chapters 1--11 are source-checked for the claims
they currently make.

Future chapter edits must preserve each chapter's verification-status paragraph
and update its evidence map when source claims change.

## Next milestone options

Choose one focused track at a time:

1. keep root/docs structure clean: no stale inherited web/release artifacts, no duplicate build/status docs;
2. reduce inherited `RESOLVE` comments in reviewed batches;
3. reduce `instanceof`-then-cast patterns where ownership is clear;
4. only then decide whether `StorageProvider`, `FunctionProvider`, or
   `TypeProvider` deserves a real v2 implementation.


## PostgreSQL-class storage/concurrency direction

After the inherited Derby cleanup pass, the next long-term database architecture
work is source-proof driven, not provider-family driven:

```text
1. MVCC is the long-term concurrency direction.
2. WAL/recovery is a core subsystem.
3. Optimizer work needs real cost/path infrastructure.
4. Indexing needs careful concurrency/latching design.
5. Vacuum/version cleanup belongs to MVCC design.
6. Runtime/executor code must stay evolvable.
```

Current checkpoint document:

```text
docs/postgres-class-storage-concurrency.md
```

Current focused proof gate:

```bash
./gradlew :delosdb-tests:runPostgresClassArchitectureProofTests
```

This does not make DelosDB MVCC yet. It defines the source boundaries that must
be understood before MVCC, version cleanup, or PostgreSQL-class optimizer/index
ideas become implementation work.

## Explicitly out of scope for now

- distributed SQL;
- HA / replication;
- PostgreSQL wire protocol;
- MySQL compatibility;
- external plugin marketplace;
- new provider families;
- production custom storage engine; experimental storage kernels remain opt-in and isolated;
- full JSON/type-system work;
- vector database behavior.


## PostgreSQL-class optimizer path observability

The first optimizer-path step is observability, not enumeration rewrite.
`optimizerPathObservabilitySmoke` records the selected Derby runtime scan shape
next to the DelosDB `CostModelProvider` v2 store-cost probe so future path
infrastructure can be grounded in current behavior.

## PostgreSQL-class index concurrency proof

The first index-concurrency step is proof coverage, not latch redesign.
`runBTreeIndexConcurrencyArchitectureProofTest` pins down the SQL-visible
unique-index conflict and rollback contract while leaving B-tree page latches,
split logic, scan repositioning, and delete/compact behavior unchanged.

## PostgreSQL-class version cleanup proof

The first version-cleanup step is not MVCC implementation. It is a SQL-visible
proof of the current Derby/DelosDB cleanup contract around indexed updates and
deletes. `runVersionCleanupArchitectureProofTest` pins down the behavior a
future MVCC visibility and vacuum subsystem must preserve: rolled-back updates
and deletes do not leave searchable index garbage, committed updates move the
visible key, and committed deletes remove the visible row from heap and index
access paths.

## Experimental MVCC storage module

The first MVCC implementation step is deliberately isolated in a new Gradle
module:

```text
delosdb-storage-mvcc
```

This module is not wired into Derby heap, B-tree, WAL, recovery, SQL execution,
or existing database open paths. It contains an in-memory MVCC core model for
transaction ids, commit sequences, snapshots, version chains, visibility, and
cleanup. That keeps existing Derby-compatible databases on the normal heap path
while DelosDB develops a future versioned-storage implementation safely.

Focused proof task:

```bash
./gradlew :delosdb-storage-mvcc:runMvccCoreModelTest
```

Root alias:

```bash
./gradlew mvccCoreModelTest
```


## Experimental MVCC storage module progress

The MVCC storage module now has both a core visibility model and a table-scan
model. The table-scan model proves snapshot-stable enumeration of visible rows
before any SQL, Derby heap, B-tree, WAL, or recovery integration. This keeps
future `delos_mvcc` work opt-in and separate from Derby-compatible storage.

### MVCC SPI checkpoint

The experimental MVCC work now has a small `VersionedStorageProvider` SPI
skeleton in `delosdb-spi`, implemented by `delosdb-storage-mvcc`. This is still
not SQL wiring and does not affect existing Derby databases. It proves the first
extension boundary for an opt-in `delos_mvcc` storage family: provider metadata,
capabilities, table creation/opening, snapshot reads, table scans, and basic
statistics through the SPI.

Focused check:

```bash
./gradlew :delosdb-storage-mvcc:runVersionedStorageProviderSpiTest
```
- Phase 3 guard: `CREATE TABLE ... USING delos_mvcc` is recognized only as an experimental versioned-storage provider and is rejected with a clean diagnostic until table-scan execution exists.


### MVCC SQL table-scan execution checkpoint

The experimental `delos_mvcc` path now has a first vertical SQL/JDBC proof:

```sql
CREATE TABLE t (id INT, name VARCHAR(40)) USING delos_mvcc;
INSERT INTO t VALUES (1, 'alpha');
SELECT * FROM t;
```

This is deliberately narrow and in-memory. It routes only a small supported SQL
subset through the `VersionedStorageProvider` table-scan path and leaves Derby
heap storage, indexes, WAL/recovery, optimizer costing, and existing database
compatibility untouched. JDBC commit/rollback is now mapped to the provider-local MVCC transaction lifecycle.
The next phase is provider-local recovery logging, still separate from Derby WAL.


### MVCC provider-local recovery-log checkpoint

The experimental `delos_mvcc` provider now has a Phase 6 append-only recovery
log for the narrow SQL row shape used by the table-scan proof. This is
provider-local durability scaffolding, not Derby WAL integration. It proves that
committed MVCC changes can be replayed after reopening the provider while
aborted or incomplete transactions are ignored. Existing Derby heap storage and
existing database compatibility paths remain untouched.

Focused check:

```bash
./gradlew mvccRecoveryLogTest
```

Aggregate MVCC check:

```bash
./gradlew mvccStorageModelTest
```

### MVCC unique-key checkpoint

The experimental `delos_mvcc` SQL path now has the first Phase 7 uniqueness
proof for column-level `PRIMARY KEY` and `UNIQUE` constraints on the narrow
in-memory table-scan bridge. This is still not a B-tree index implementation and
not optimizer integration. It pins the user-visible conflict contract first:
committed duplicate keys fail, active transaction reservations block concurrent
duplicates, rollback releases reservations, and commit keeps reservations
enforced.

Focused check:

```bash
./gradlew versionedStorageUniqueKeySmoke
```

### MVCC provider-owned index checkpoint

The experimental `delos_mvcc` path now includes a Phase 8 provider-owned index
model. Index entries point to MVCC row keys, and lookup rechecks snapshot-visible
row versions before returning data. The SQL bridge supports the first indexed
lookup path with `CREATE INDEX ... ON ...`, `SELECT ... WHERE`, and narrow indexed
`UPDATE`/`DELETE` proofs.

This is still not Derby B-tree integration and not optimizer costing. It is the
storage-engine checkpoint before deeper snapshot isolation, cleanup/vacuum, and
real path-cost work.

### MVCC snapshot-semantics checkpoint

The experimental `delos_mvcc` path now has a Phase 9 snapshot-semantics proof.
Following the PostgreSQL design rule, visibility is no longer treated as one
fixed behavior for all JDBC transactions:

- `READ COMMITTED` and `READ UNCOMMITTED` capture a fresh provider snapshot per
  statement.
- `REPEATABLE READ` and `SERIALIZABLE` keep the same provider snapshot until
  JDBC commit/rollback.
- Own writes remain visible after statement-snapshot refresh.
- Another active writer remains invisible even after refresh.

This is still provider-local MVCC behavior. It does not change Derby heap
storage, Derby locks, Derby WAL, or optimizer costing.

Focused checks:

```bash
./gradlew mvccSnapshotIsolationTest
./gradlew versionedStorageSnapshotIsolationSmoke
```
