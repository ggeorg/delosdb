# DelosDB Algorithm Inventory

## Purpose

This document is a source-backed inventory of the main algorithms and execution boundaries that are
specific to DelosDB or materially important to Derby compatibility. It is not a roadmap and it does
not define build authority. Source code, executable tests, module metadata, and structural manifests
remain authoritative.

## SQL compilation and generated execution

DelosDB keeps Derby's parser, binder, optimizer, activation contract, and result-set execution model.
Generated activations use the JDK Class-File API through the existing DelosDB generation contract.
The important production authorities include:

```text
ClassFileJava
JavaFactory / ClassBuilder / MethodBuilder
OptimizerImpl
GenericPreparedStatement
stable selected-plan model
Derby ResultSet implementations
```

The compiler may expose deterministic plan and execution evidence, but diagnostics do not become a
second optimizer or executor.

## Stable plans and execution evidence

`EXPLAIN` and `EXPLAIN ANALYZE` use one stable selected-plan model. Runtime evidence is attached to
that selected plan rather than reconstructed from Java implementation classes after execution.

Important boundaries include:

```text
StablePlanExecutionRenderer
StablePlanExecutionEvidence
ExplainNode
ExplainAnalyzeResultSet
```

Operator timing and bounded storage evidence are diagnostic. They do not influence plan selection,
transaction outcome, or storage behavior.

## Heap and RawStore compatibility

The inherited Derby heap and RawStore remain the compatibility and physical persistence foundation.
Key inherited authorities include:

```text
TransactionController
OpenHeap
HeapController
BasePage / StoredPage
FileContainer
AllocPage
RawStore logging, recovery, backup, and restore
```

Changes in these areas require compatibility evidence because page formats, log behavior, catalogs,
JDBC behavior, and DRDA behavior are product compatibility surfaces.

## MVCC access method

`delos_mvcc` is a RawStore-backed Derby access method. It owns logical versioning and concurrency
semantics while RawStore owns physical persistence and recovery.

Current implementation authorities include:

```text
DerbyMvccAccessMethodProvider
MvccConglomerateFactory
MvccRawStoreRuntime
MvccRawStoreTransactionContext
MvccRawStoreTable
MvccRawStoreScanController
MvccRawStoreConglomerateController
MvccRawStoreRowDirectory
MvccRawStoreVersionReader
MvccRawStoreVersionRows
MvccRawStoreOrderedIndex
MvccRawStoreVacuum
MvccRawStoreMaintenanceService
```

There is no independent MVCC page volume, custom WAL, sidecar checkpoint system, or second recovery
authority.

## MVCC snapshot visibility

Visibility is based on database-wide published commit sequences and transaction identity.
`MvccRawStoreTransactionContext` captures a snapshot sequence lazily from `MvccRawStoreRuntime`.
`MvccRawStoreVersionReader` accepts:

```text
an uncommitted version only for its creating transaction
or
a committed version when
    beginSequence <= snapshotSequence < endSequence
```

A bounded snapshot-lease registry protects retained-reader horizons. When the bounded slots are full,
the runtime falls back to the locked retained-snapshot registry without changing visibility semantics.

Current-row anchors and immutable current-version read images are read accelerators only. A miss or
validation failure falls back to authoritative RawStore lookup and version traversal.

## MVCC identity and commit publication

The RawStore-backed runtime owns database-wide transaction IDs and commit-sequence publication.
Table-local row and version IDs identify durable logical history inside one MVCC conglomerate.
Transaction work is staged inside the Derby transaction boundary, stamped before RawStore commit,
and made visible only through the runtime's published commit-sequence frontier after successful
commit.

The relevant current authorities include:

```text
MvccRawStoreDatabaseMetadata
MvccRawStoreTransactionContext
MvccRawStoreRuntime
MvccRawStoreTable
```

RawStore remains the durable transaction decision authority.

## Ordered indexes

`MvccRawStoreOrderedIndex` and related generation/predicate code maintain version-aware ordered access
for `delos_mvcc`. The ordered index is stored in RawStore containers and follows the same Derby
transaction, logging, recovery, and backup lifecycle as other MVCC state.

Candidate hints and cached read structures do not replace authoritative logical row/version identity.

## Locking

`MvccRawStorePhysicalLocking` defines the RawStore physical-lock mode used by the access method.
`MvccRawStoreLogicalLock` provides logical conflict coordination where MVCC semantics require it.
Physical latches and locks protect RawStore structures; logical MVCC visibility remains governed by
transaction identity and commit-sequence rules.

## Vacuum and maintenance

`MvccRawStoreMaintenanceService` and `MvccRawStoreVacuum` operate on RawStore-backed MVCC structures.
The vacuum horizon is bounded by the oldest retained snapshot. Vacuum must not remove version history
that can still be observed by a live retained reader.

Maintenance does not own a second checkpoint, log, or durability system.

## Optimizer statistics and cost

Derby's optimizer remains authoritative. DelosDB exposes storage statistics and cost information
through provider-neutral contracts and MVCC/heap implementations where appropriate. These inputs may
improve costing and diagnostics but do not create a parallel optimizer.

## Diagnostics and JFR

Diagnostics are read-only observations. They must not select a storage provider, decide visibility,
repair data, commit transactions, or change optimizer authority.

The production storage-lifecycle JFR surface intentionally has one live MVCC analyze/statistics hook:

```text
DelosStorageLifecycleJfr.recordMvccAnalyzeStatistics
```

Retired experimental event surfaces are not production authorities.

## Differential and external validation

DelosDB uses several independent evidence styles:

```text
heap/MVCC differential SQL execution
DRDA client/server compatibility
crash and reopen recovery
fault injection
module-boundary structural analysis
SQLancer and other opt-in external validation
performance and concurrency measurement
```

Timing evidence is diagnostic. A performance result does not authorize a behavior or format change
without separate correctness and compatibility evidence.

## Ownership rule

When documentation and implementation disagree, resolve the disagreement against the current source,
executable tests, and structural manifests. Do not preserve an obsolete algorithm description merely
because it was once part of the implementation.
