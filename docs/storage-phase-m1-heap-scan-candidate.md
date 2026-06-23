# Storage Phase M1 — Heap scan live-candidate, no SQL routing

## Decision

M1 introduces an isolated heap scan candidate:

```text
EngineHeapTableAccessLiveCandidate
```

This is not heap activation.

The accepted K1 answer remains in force:

```text
Heap scan/cost parity:
  feasible incrementally.

Heap mutation/locking parity:
  deferred.

Row reservation:
  MVCC-specific for now.
```

## Scope

M1 proves only that a heap read provider can be shaped around Derby's existing heap cursor APIs:

```text
TransactionController.openScan(...)
TransactionController.openCompiledScan(...)
ScanController.fetchNext(...)
ScanController.fetchLocation(...)
```

The candidate returns `DelosRow` values with heap `DelosRowIdentity` wrappers, but only when called directly with an explicit physical-access context.

## Non-goals

M1 does not change SQL behavior.

```text
No GenericResultSetFactory heap branch.
No FromBaseTable.generate() change.
No TableScanResultSet replacement for heap SQL.
No DelosNativeTableRegistry heap registration.
No heap mutation route.
No heap row reservation API.
No heap locking claim.
No deadlock detection claim.
No optimizer behavior change.
No bridge resurrection.
```

## Why this is safe

The previous proof-only adapter, `EngineHeapTableAccessProof`, showed the Derby concepts needed for heap access but intentionally threw from public capability methods. M1 keeps that class proof-only and adds a separate candidate class that can open Derby heap scans only when a caller supplies the same explicit context objects Derby already uses.

This means ordinary heap SQL remains Derby-native while the provider contract gets a concrete read-side candidate for later shadow testing.

## Candidate behavior

The candidate:

```text
- implements DelosFilterableTableAccess only
- advertises FILTERABLE only
- advertises no semantic guarantees
- leaves DelosPredicate ownership above the provider
- refuses provider-side projection pushdown
- requires physicalAccessAllowed=true
- requires a TransactionController
- requires a heap conglomerate id
- requires a row template
- may use openCompiledScan if compiled conglomerate context is supplied
- otherwise uses openScan
```

## Route after M1

M2 is now the next heap step:

```text
M2 — heap scan shadow branch
  Optional property-gated branch only:
    -Ddelosdb.storage.phaseM.heapScanShadow=true
  GenericResultSetFactory may branch for default-provider heap only under the shadow flag.
```

M1 intentionally does not add that flag or branch.
