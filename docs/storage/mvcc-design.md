# DelosDB MVCC Integration Plan — MODULE5A Boundary Map

Status: source-gated first pass.

Purpose: identify the least-damage path from the current MVCC bridge/proof code to a real Derby-integrated DelosDB MVCC storage provider.

This is not a broad architecture essay. It is the gate before MODULE5B+ implementation.

## 1. Binding decision

DelosDB continues with its own native MVCC engine.

The native MVCC engine must become a real Derby/DelosDB storage provider. It must not remain a SQL-text bridge, regex/classifier executor, or side database.

Primary model:

```text
PostgreSQL-like version chains stored in Delos slotted pages.

MvccRowId
  -> head MvccVersionLocator(pageId, slotId)
       -> previous MvccVersionLocator(pageId, slotId)
            -> previous MvccVersionLocator(pageId, slotId)
```

Integration rule:

```text
Integration before sophistication.
```

The first real win is not WAL, native I/O, or a better index. The first real win is:

```text
normal Derby execution reaching MVCC without the SQL bridge.
```

## 2. Current bridge/proof inventory

These classes are current scaffolding. They are useful, but they must not become the final storage architecture.

| Class / file | Current role | Status | Replacement path | Delete after |
|---|---|---|---|---|
| `delosdb-storage-mvcc/.../DelosMvccSqlOptInSession.java` | Regex SQL-shaped opt-in MVCC session. | Proof-only bridge. | Derby parser/compiler/execution path reaches MVCC provider. | After Derby SELECT + INSERT/DELETE/UPDATE reach MVCC without bridge. |
| `delosdb-storage-mvcc/.../MvccSqlOptInSmokeTest.java` | Smoke coverage for opt-in bridge. | Proof-only test. | Derby SQL provider tests. | After equivalent Derby-path SQL tests exist. |
| `delosdb-engine/.../DelosVersionedStorageQueryTreeClassifier.java` | QueryTreeNode classifier for temporary bridge routing. The source comment says it is not binder/optimizer/executor integration and regex routing remains fallback. | Temporary bridge/classifier. | Derby catalog/provider dispatch at execution/access-store boundary. | After Derby execution no longer needs classifier routing for core MVCC table operations. |
| `delosdb-engine/.../VersionedStorageExecutionBridge.java` | Narrow engine-to-`VersionedStorageProvider` bridge. The source comment says it is deliberately not wired to Derby SQL execution yet. | Keep temporarily as provider operation adapter. | Move final operations behind Derby-visible table/access/storage provider seam. | After a real MVCC access/store provider owns table open/scan/DML. |
| `delosdb-engine/.../DelosNativeTableRegistry.java` | Catalog-to-provider table registry used by native ResultSet execution. Opens provider table access from `TableDescriptor` metadata. | Useful scaffolding, but too in-memory and statement-transaction oriented. | Derby-visible MVCC table identity + Derby transaction-hosted MVCC context. | Keep until table metadata and transaction lifecycle move behind final provider seam. |
| `delosdb-engine/.../DelosTableScanResultSet.java` | Delos-specific native MVCC scan result set selected by `GenericResultSetFactory` under proof properties. | Bridge-killer candidate, but still Delos-specific ResultSet path. | First Derby SELECT proof, then move lower toward store/access provider seam. | Keep until a proper access/store scan controller path replaces it or is confirmed unnecessary. |
| `delosdb-engine/.../DelosInsertResultSet.java` | Delos-specific native INSERT result set selected by `GenericResultSetFactory` under proof property. | Temporary DML seam. | Derby transaction-hosted MVCC insert through final provider seam. | After final insert path exists. |
| `delosdb-engine/.../DelosDeleteResultSet.java` | Delos-specific native DELETE result set selected by `GenericResultSetFactory` under proof property. | Temporary DML seam. | Derby transaction-hosted MVCC delete through final provider seam. | After final delete path exists. |
| `delosdb-engine/.../DelosUpdateResultSet.java` | Delos-specific native UPDATE result set selected by `GenericResultSetFactory` under proof property. | Temporary DML seam. | Derby transaction-hosted MVCC update through final provider seam. | After final update path exists. |
| `delosdb-engine/.../DelosTableScanProviderLookup.java` | Factory-side lookup seam. Resolves `TableDescriptor` storage provider name from activation/LCC and proof gates native scan/DML branches. | Useful source-gated proof seam. | Replace proof properties with provider dispatch driven by durable table metadata. | After provider dispatch is default for MVCC tables. |

Bridge policy:

```text
Do not add new bridge features unless they directly reduce or replace bridge code.
```

## 3. Source facts: Derby execution entry points

### 3.1 SELECT / scan entry

Observed path:

```text
org.apache.derby.impl.sql.compile.FromBaseTable.generateResultSet(...)
  -> acb.pushGetResultSetFactoryExpression(mb)
  -> getScanArguments(...)
  -> JoinStrategy.resultSetMethodName(...)
  -> ResultSetFactory method call
```

For normal table scans this reaches:

```text
org.apache.derby.impl.sql.execute.GenericResultSetFactory.getTableScanResultSet(...)
```

Current Delos hook in `GenericResultSetFactory.getTableScanResultSet(...)`:

```text
DelosTableScanProviderLookup.observeFactoryLookupIfEnabled(...)
DelosTableScanResultSet.createIfEnabled(params)
DelosHeapLiveTableScanResultSet.createIfEnabled(params)
DelosHeapScanShadowResultSet.createIfEnabled(params)
fallback: new TableScanResultSet(params)
```

Meaning:

```text
The current best SELECT proof point is ResultSetFactory-level dispatch.
It is already inside normal Derby generated execution.
It is not yet the final access/store provider boundary.
```

Least-damage next step:

```text
Use this point for the first bridge-killer SELECT proof,
while MODULE5A continues to evaluate whether a lower TransactionController/openScan provider seam is practical.
```

### 3.2 INSERT entry

Observed path:

```text
GenericResultSetFactory.getInsertResultSet(...)
  -> DelosInsertResultSet.createIfEnabled(params)
  -> DelosHeapInsertResultSet.createIfEnabled(params)
  -> fallback: new InsertResultSet(params)
```

Meaning:

```text
INSERT already has a Derby execution ResultSet seam.
Current native path opens DelosNativeTableRegistry access and commits a provider statement transaction on close.
This is not yet Derby transaction lifecycle integration.
```

Risk:

```text
Current provider transaction is statement-scoped, not Derby transaction-scoped.
MODULE5D must fix this before DML is considered real.
```

### 3.3 DELETE entry

Observed path:

```text
GenericResultSetFactory.getDeleteResultSet(...)
  -> DelosDeleteResultSet.createIfEnabled(source, activation)
  -> DelosHeapDeleteResultSet.createIfEnabled(source, activation)
  -> fallback: new DeleteResultSet(source, activation)
```

Current native DELETE requires a `DelosTableScanResultSet` source and currently supports the equality/native-mutation proof shape.

Meaning:

```text
DELETE is coupled to the temporary Delos scan result set.
It is a proof path, not final storage/access integration.
```

### 3.4 UPDATE entry

Observed path:

```text
GenericResultSetFactory.getUpdateResultSet(...)
  -> DelosUpdateResultSet.createIfEnabled(source, generationClauses, checkGM, activation)
  -> DelosHeapUpdateResultSet.createIfEnabled(...)
  -> fallback: new UpdateResultSet(...)
```

Current native UPDATE requires a `DelosTableScanResultSet` source and uses Derby-generated replacement row handling before calling the provider mutation boundary.

Meaning:

```text
UPDATE is closer to Derby execution than the SQL bridge, but still depends on Delos-specific ResultSet routing.
```

## 4. Source facts: Derby access/store boundary

The inherited store/access API already exposes the shape we ultimately want to imitate or join:

```text
org.apache.derby.iapi.store.access.TransactionController
  createConglomerate(...)
  openConglomerate(...)
  openScan(...)
  openCompiledScan(...)
  commit()
  abort()

org.apache.derby.iapi.store.access.ConglomerateController
  insert(...)
  delete(StoreRowLocation)
  fetch(StoreRowLocation, ...)
  replace(StoreRowLocation, ...)

org.apache.derby.iapi.store.access.ScanController
  next()
  fetch(...)
  delete()
  replace(...)
```

Implementation currently lives in inherited Derby store:

```text
org.apache.derby.impl.store.access.RAMTransaction
  createConglomerate(...) -> ConglomerateFactory.createConglomerate(...)
  openConglomerate(...) -> Conglomerate.open(...)
  openScan(...) -> Conglomerate.openScan(...)
  commit() -> rawtran.commit()
  abort() -> rawtran.abort()
```

Important source fact:

```text
RAMTransaction dispatches by Conglomerate implementation/factory.
That is the credible long-term provider insertion area, but it is high risk because it is inherited Derby raw/access store machinery.
```

Least-damage approach:

```text
1. Use existing GenericResultSetFactory Delos branches for the first bridge-killer proof.
2. In parallel, map whether an MVCC Conglomerate/ScanController/ConglomerateController can be introduced without forcing inherited heap/raw store through Delos abstractions.
3. Only move below ResultSetFactory after the access/store seam is proven by source and tests.
```

## 5. Source facts: table identity

Current catalog-level provider metadata exists:

```text
CreateTableNode
  storageProviderName

CreateTableConstantAction
  DataDescriptorGenerator.newTableDescriptor(..., storageProviderName)

TableDescriptor
  DEFAULT_STORAGE_PROVIDER_NAME = "heap"
  getStorageProviderName()
  setStorageProviderName(...)
```

Current native registry:

```text
DelosNativeTableRegistry.registerNativeExecutionTable(...)
DelosNativeTableRegistry.openNativeExecutionTableAccess(TableDescriptor)
```

Current native registry strength:

```text
It uses Derby TableDescriptor metadata.
It is not a SQL-text router.
It can reconstruct table entries from catalog metadata after in-memory registry clear.
```

Current native registry weakness:

```text
It is still an in-memory Java registry around VersionedTableMetadata.
It opens a statement-scoped provider transaction.
It is not yet durable MVCC table metadata rooted in a Derby conglomerate/catalog identity.
```

MODULE5C target:

```text
Derby-visible MVCC table identity must exist without SQL bridge ownership.
Heap and MVCC tables must coexist.
Restart must preserve MVCC table metadata.
```

## 6. Source facts: transaction lifecycle risk

Current inherited access transaction path:

```text
RAMTransaction.commit()
  closeControllers(false)
  rawtran.commit()

RAMTransaction.abort()
  invalidateConglomerateCache()
  closeControllers(true)
  rawtran.abort()
  parent_tran.abort() if present
```

Current native Delos provider path:

```text
DelosNativeTableRegistry.openNativeExecutionTableAccess(...)
  beginStatementTransaction(...)
  returns NativeExecutionTableAccess

NativeExecutionTableAccess.close()
  finishStatementTransaction(...)
  provider transaction coordinator commit

NativeExecutionTableAccess.abort()
  provider transaction coordinator abort
```

Risk:

```text
This creates two transaction truths:
  Derby transaction lifecycle
  provider statement transaction lifecycle
```

MODULE5D hard invariant:

```text
Derby commit is not complete until MVCC commit outcome is durable.
Derby rollback must make MVCC writes invisible.
On restart, MVCC must never expose a transaction Derby would not treat as committed.
```

MODULE5D implementation direction:

```text
Attach an MVCC transaction context to the Derby transaction/language connection lifecycle,
then make native ResultSet access use that context instead of creating an independent statement transaction.
```

Exact hook remains source-gated; candidates to inspect next:

```text
LanguageConnectionContext transaction accessors
Activation transaction accessors
TransactionController lifecycle
RAMTransaction context ownership
EmbedConnection commit/rollback path
```

## 7. Row identity and RowLocation decision

Current MVCC durable model already has:

```text
MvccRowId
MvccVersionLocator(pageId, slotId)
MvccRowDirectory
MvccVersionRecord
```

Final Derby-visible row location must be logical-first:

```text
MvccRowLocation:
  rowId
  optional version locator hint
```

Rules:

```text
rowId is stable logical identity
version locator may become stale
visibility recheck is mandatory
vacuum cannot invalidate visible row locations
indexes may contain stale candidates
```

Risk to inspect next:

```text
Derby inherited code may expect StoreRowLocation to behave like a heap physical location.
MODULE5A-2 must map StoreRowLocation implementations and consumers before final RowLocation design is coded.
```

## 8. Storage I/O boundary decision

Current `delosdb-storage-io` source owns:

```text
io.github.ggeorg.delosdb.storage.io.page.DelosPage
io.github.ggeorg.delosdb.storage.io.page.DelosPageId
io.github.ggeorg.delosdb.storage.io.page.DelosPageIo
io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume
io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume
io.github.ggeorg.delosdb.storage.io.volume.MappedPageVolume
io.github.ggeorg.delosdb.storage.io.volume.OffHeapPageVolume
io.github.ggeorg.delosdb.storage.io.volume.FaultInjectingPageVolume
org.apache.derby.io.* inherited Derby VFS contracts
```

Decision:

```text
delosdb-storage-io is the DelosDB storage platform layer.
It must be Java-first, JDK-25-ready, and native-capable later.
```

MODULE5B target:

```text
Harden storage-io contracts before deeper MVCC recovery work.
Do not build native I/O yet.
Do not make mmap authoritative for WAL correctness.
```

Immediate storage-io contract questions:

```text
page size metadata
alignment metadata
readFully/writeFully semantics
forceData/forceMetadata split
checksum hook
pageLSN readiness
native/direct buffer allocation boundary
short read/write handling
```


## 8.5 MODULE5B–MODULE5G execution status

Current fast-pass status after the MODULE5 provider-route smokes:

```text
Green so far:
  Derby-visible delos_mvcc table identity exists in catalog/provider metadata.
  Derby transaction lifecycle reaches the MVCC transaction-context hook.
  Normal Derby SELECT full scan reaches delos_mvcc by provider identity.
  Normal Derby INSERT reaches delos_mvcc by provider identity.
  Simple normal Derby UPDATE and DELETE reach delos_mvcc by provider identity.
  Default heap tables still route through the inherited Derby heap path.
```

Important limitation:

```text
This is provider-identity execution routing, not final Derby store/access provider integration.
The Delos ResultSet family remains a transitional execution seam.
```

Tightened bridge rule before WAL/pageLSN:

```text
Do not add new capability to DelosMvccSqlOptInSession.
Do not add new proof-property routes for core CRUD.
For core delos_mvcc CRUD, provider identity must be the route source.
```

The MODULE5G preflight smoke exists to enforce that the old native proof
properties are cleared while normal Derby INSERT / SELECT full scan / UPDATE /
DELETE still work over a `USING delos_mvcc` table.

## 9. Fast implementation sequence

### MODULE5A-1: this boundary map

Deliverable:

```text
docs/storage/mvcc-design.md
```

No Java behavior changes.

### MODULE5A-2: bridge comments + deletion markers

Small source-only documentation pass.

For each bridge/proof class, add a source comment with:

```text
current role
why it exists
replacement milestone
delete-after milestone
```

No behavior changes.

### MODULE5B-1: storage-io contract hardening

Add contract methods or documentation/tests for:

```text
page size metadata
alignment
force semantics
readFully/writeFully
checksum hooks
pageLSN field readiness
```

No MVCC semantics changes.

### MODULE5C-1: Derby-visible MVCC table identity proof

Goal:

```text
CREATE/OPEN identity for MVCC-backed table lives in Derby metadata, not SQL bridge registry.
```

Proofs:

```text
heap table still works
MVCC table identity can be created/opened
heap and MVCC table identities coexist
restart preserves MVCC table metadata
bridge not used for identity
```

### MODULE5D-1: Derby transaction attaches MVCC context

Goal:

```text
Derby transaction hosts MVCC transaction context.
```

Proofs:

```text
Derby commit marks MVCC committed
Derby rollback marks MVCC aborted
active-at-crash invisible
committed/aborted status survives restart
native ResultSet no longer creates independent statement transaction for final path
```

### MODULE5E-1: Derby SELECT full scan reaches MVCC

Goal:

```text
normal Derby SELECT reaches MVCC table scan without SQL bridge.
```

This is the first bridge-killer proof.

### MODULE5F-1: Derby INSERT/DELETE/UPDATE reaches MVCC

Goal:

```text
normal Derby DML appends/deletes/updates MVCC versions without SQL bridge.
```

Only after this do we start WAL/pageLSN, vacuum, and index work.

## 10. Stop conditions

Stop and re-plan if any of these are true:

```text
No credible Derby access/store dispatch point can be identified.
Derby transaction lifecycle cannot host MVCC transaction context without two transaction truths.
Derby RowLocation assumptions force physical heap identity too deeply for logical MVCC row ids.
MVCC table identity cannot survive restart without the bridge registry.
```

## 11. Current recommendation

Finish the MODULE5G provider-route CRUD preflight, then make the next plan.

Do not start these until the preflight is green:

```text
WAL
native I/O
indexes
vacuum
buffer manager
new SQL bridge capabilities
```

The next plan should start from the honest status: provider-identity CRUD routing
is green, but final Derby store/access provider integration is not complete.
