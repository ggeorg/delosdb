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

This is still only a preflight. SQL SELECT/INSERT/DELETE/UPDATE may still use
transitional Delos result-set seams until MODULE6F-MODULE6H prove the inherited
execution paths.

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

Boundary:

```text
This does not make SQL SELECT use TableScanResultSet -> MvccScanController yet.
That bridge-killer proof remains MODULE6F.
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

Current DelosDB bypass still exists:

```text
GenericResultSetFactory
  -> DelosTableScanResultSet.createIfEnabled(...)
```

Plan implication:

```text
Do not improve DelosTableScanResultSet for Plan 3 unless the change directly
helps isolate or remove it. MODULE6F should prove SELECT through inherited
TableScanResultSet and MVCC ScanController.
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

Current DelosDB bypass still exists:

```text
GenericResultSetFactory
  -> DelosInsertResultSet.createIfEnabled(...)
```

Plan implication:

```text
The real INSERT bridge-killer path is MvccConglomerateController.insert(...) and
insertAndFetchLocation(...), not more DelosInsertResultSet behavior.
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

Current DelosDB bypass still exists:

```text
GenericResultSetFactory
  -> DelosDeleteResultSet.createIfEnabled(...)
```

Plan implication:

```text
DELETE requires a Derby-visible MVCC RowLocation whose logical identity is the
stable MvccRowId. A physical page/slot locator can only be a hint.
```

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

Current DelosDB bypass still exists:

```text
GenericResultSetFactory
  -> DelosUpdateResultSet.createIfEnabled(...)
```

Plan implication:

```text
UPDATE must eventually become append-new-version-by-rowId through
MvccConglomerateController.replace(...), preserving logical row identity and
performing MVCC visibility/write-conflict checks.
```

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

## Transitional Delos result-set inventory

Current transitional result-set seams:

```text
DelosTableScanResultSet
DelosInsertResultSet
DelosDeleteResultSet
DelosUpdateResultSet
```

Current caller:

```text
GenericResultSetFactory
```

Policy:

```text
These classes may remain while inherited store/access proofs are incomplete.
Do not add new capabilities to them.
After inherited SELECT/INSERT/DELETE/UPDATE paths are green, shrink or delete
these classes in a dedicated cleanup milestone.
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
MODULE6I — shrink/delete transitional Delos result sets
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
