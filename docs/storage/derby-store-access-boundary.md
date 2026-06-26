# MODULE6A — Derby store/access MVCC boundary map

This document is a source-gated map for the next DelosDB MVCC integration phase.
It records only current source facts and the smallest safe direction for moving
`delos_mvcc` from transitional result-set routing toward inherited Derby
store/access integration.

## Current truth

DelosDB currently has provider-identity CRUD routing and durable MVCC storage
proofs through MODULE5H-MODULE5N.

The honest integration statement is still:

```text
Derby execution reaches MVCC by provider identity.
Final Derby store/access provider integration is still incomplete.
```

MODULE6E removes the first physical-creation contradiction:

```text
A heap table still creates a heap physical conglomerate.
A delos_mvcc table now asks Derby store/access for a delos_mvcc physical conglomerate.
```

MODULE6F-MODULE6H then proved normal Derby SELECT, INSERT, DELETE, and UPDATE
through inherited store/access paths. MODULE6I retires the old MVCC
Delos*ResultSet bypass classes and leaves compatibility property names only as
honesty guards for old smokes.

## Current physical table creation seam

Source file:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/CreateTableConstantAction.java
```

Current fact after MODULE6E:

```java
String conglomerateImplementation = physicalConglomerateImplementation();
long conglomId = tc.createConglomerate(
        conglomerateImplementation,
        template.getRowArray(),
        null,
        collation_ids,
        properties,
        ...);
```

Meaning:

```text
CREATE TABLE now chooses the physical access-method implementation from the
storage provider. Heap tables still request "heap". delos_mvcc base tables
request "delos_mvcc".
```

Boundary after MODULE6I:

```text
CREATE TABLE ... USING delos_mvcc creates an MVCC physical conglomerate.
Normal SQL SELECT reaches TableScanResultSet -> MvccScanController.
Normal SQL INSERT reaches InsertResultSet -> RowChangerImpl -> MvccConglomerateController.
Normal SQL DELETE reaches DeleteResultSet -> RowChangerImpl -> MvccConglomerateController.
Normal SQL UPDATE reaches UpdateResultSet -> RowChangerImpl -> MvccConglomerateController.
```

## Access method discovery and registration seam

Source files:

```text
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMAccessManager.java
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMTransaction.java
delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/access/conglomerate/ConglomerateFactory.java
```

Current facts:

```text
RAMTransaction.createConglomerate(implementation, ...)
  -> accessmanager.findMethodFactoryByImpl(implementation)
  -> requires the MethodFactory to be a ConglomerateFactory
  -> calls ConglomerateFactory.createConglomerate(...)
```

`RAMAccessManager.findMethodFactoryByImpl(...)` first checks already registered
access methods, then tries to boot a matching method module, and registers it
when found.

`RAMAccessManager.registerAccessMethod(...)` registers `ConglomerateFactory`
instances through `registerConglomerateFactory(...)` and stores them by primary
implementation type and primary format.

`ConglomerateFactory.getConglomerateFactoryId()` documents the current small id
space. Existing built-in factories use heap and btree. The next MVCC factory
must use a new explicit id; do not collide with heap or btree.

Plan implication:

```text
MODULE6B should prove registration/discovery of a delos_mvcc access method
before changing CREATE TABLE physical routing.
```

## SELECT inherited scan seam

Source files:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/TableScanResultSet.java
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/RAMTransaction.java
```

Current inherited path:

```text
TableScanResultSet.openScanController(...)
  -> TransactionController.openCompiledScan(...)
  -> RAMTransaction.openCompiledScan(...)
  -> RAMTransaction.openScan(Conglomerate, ...)
  -> Conglomerate.openScan(...)
  -> ScanController.fetchNext(...)
```

Meaning:

```text
The real SELECT bridge-killer path is an MVCC Conglomerate.openScan(...) that
returns an MVCC ScanController. It is not another GenericResultSetFactory bypass.
```

MODULE6I retirement fact:

```text
GenericResultSetFactory no longer calls DelosTableScanResultSet.createIfEnabled(...).
The old DelosTableScanResultSet source file is stale and removed by the MODULE6I cleanup script.
SELECT now uses inherited TableScanResultSet and MVCC ScanController.
```

## INSERT inherited write seam

Source file:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java
```

Current inherited path:

```text
RowChangerImpl.insertRow(...)
  -> baseCC.insertAndFetchLocation(row, RowLocation)
     or baseCC.insert(row)
```

`baseCC` is the base table `ConglomerateController`.

MODULE6I retirement fact:

```text
GenericResultSetFactory no longer calls DelosInsertResultSet.createIfEnabled(...).
The old DelosInsertResultSet source file is stale and removed by the MODULE6I cleanup script.
INSERT now uses InsertResultSet -> RowChangerImpl -> MvccConglomerateController.
```

## DELETE inherited write seam

Source file:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java
```

Current inherited path:

```text
RowChangerImpl.deleteRow(baseRow, baseRowLocation)
  -> baseCC.delete(baseRowLocation)
```

MODULE6I retirement fact:

```text
GenericResultSetFactory no longer calls DelosDeleteResultSet.createIfEnabled(...).
The old DelosDeleteResultSet source file is stale and removed by the MODULE6I cleanup script.
DELETE now uses DeleteResultSet -> RowChangerImpl -> MvccConglomerateController.
```

DELETE requires a Derby-visible MVCC RowLocation whose logical identity is the
stable MvccRowId. A physical page/slot locator can only be a hint.

## UPDATE inherited write seam

Source file:

```text
delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java
```

Current inherited path:

```text
RowChangerImpl.updateRow(oldBaseRow, newBaseRow, baseRowLocation)
  -> baseCC.replace(baseRowLocation, sparseRowArray, changedColumnBitSet)
```

MODULE6I retirement fact:

```text
GenericResultSetFactory no longer calls DelosUpdateResultSet.createIfEnabled(...).
The old DelosUpdateResultSet source file is stale and removed by the MODULE6I cleanup script.
UPDATE now uses UpdateResultSet -> RowChangerImpl -> MvccConglomerateController.replace(...).
```

UPDATE must append a new version by stable rowId, preserving logical row
identity and performing MVCC visibility/write-conflict checks.

## RowLocation lifecycle seam

Current inherited facts:

```text
TableScanResultSet obtains RowLocation from the base scan when update/delete
paths need it.

RowChangerImpl consumes RowLocation for delete and update.

insertAndFetchLocation(...) can return/fill a RowLocation during insert.
```

Plan implication:

```text
MvccRowLocation must not be a disconnected component proof. It should be
introduced with the MVCC ConglomerateController / ScanController skeleton that
needs it.
```

Required shape:

```text
MvccRowLocation
  rowId
  optional version locator hint
```

Rules:

```text
rowId is stable
version locator is a hint only
visibility recheck is mandatory
page/slot identity is not Derby-visible logical row identity
```

## Heap and btree safety constraints

Plan 3 must preserve inherited heap and btree behavior.

Required safety facts for every store/access milestone:

```text
heap tables still create/open heap conglomerates
btree indexes still create/open btree conglomerates
no delos_mvcc access-method registration may override heap or btree
no root Gradle smoke aliases
no proof-property routing
```

## Retired MVCC Delos result-set inventory

Retired by MODULE6I:

```text
DelosTableScanResultSet
DelosInsertResultSet
DelosDeleteResultSet
DelosUpdateResultSet
```

Retirement facts:

```text
GenericResultSetFactory no longer calls their createIfEnabled(...) methods.
DelosTableScanProviderLookup keeps old proof-property names as literal compatibility constants only.
legacyNativeMvccCrudProofRoutesEnabledForTesting() remains false.
A MODULE6I cleanup script removes the stale source files.
```

## Frozen Plan 3 order

```text
MODULE6A — Derby store/access boundary map
MODULE6B — MVCC access-method registration preflight
MODULE6C — MVCC conglomerate skeleton + logical RowLocation
MODULE6D — direct store/access MVCC scan proof
MODULE6E — CREATE TABLE physical conglomerate switch preflight (green target)
MODULE6F — inherited SELECT through TableScanResultSet
MODULE6G — inherited INSERT through RowChanger/ConglomerateController
MODULE6H — inherited DELETE/UPDATE through RowChanger/ConglomerateController
MODULE6I — shrink/delete transitional Delos result sets (current)
```

## Stop conditions

Stop immediately if:

```text
a new proof property is added
a new GenericResultSetFactory bypass is added
delos_mvcc physical creation remains heap after the physical switch milestone
RowLocation is introduced as a side proof disconnected from store/access
heap or btree behavior breaks
root build.gradle is edited for a smoke
WAL/vacuum/index/native I/O work starts during Plan 3
we attempt to replace the whole Derby store layer at once
```
