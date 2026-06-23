# Storage Phase M2 — Heap scan shadow branch

## Decision

M2 introduces a property-gated heap scan shadow branch at the existing
`GenericResultSetFactory.getTableScanResultSet(...)` seam.

This is **not** heap provider activation.  The returned result set deliberately
extends Derby's existing `TableScanResultSet`, so heap row production, locking,
qualification, row locations, and scan lifetime remain Derby-owned.

The gate is:

```text
delosdb.storage.phaseM.heapScanShadow=true
```

## What M2 proves

M2 proves that the default heap provider can be selected at the same factory
branch where `delos_mvcc` is already selected, but only under an explicit shadow
flag.

The safe route is:

```text
GenericResultSetFactory.getTableScanResultSet(...)
  -> DelosTableScanResultSet.createIfEnabled(params)
       non-default delos_mvcc only
  -> DelosHeapScanShadowResultSet.createIfEnabled(params)
       default heap only, property-gated, read-only base scans only
  -> TableScanResultSet(params)
       ordinary heap route
```

## What M2 does not do

```text
No default behavior change.
No unguarded heap SQL routing.
No DelosNativeTableRegistry heap registration.
No heap mutation route.
No heap row reservation.
No heap lock/reservation API.
No heap provider-side projection contract.
No heap ordered-scan contract.
No bridge resurrection.
```

## Why this shape is safer than full heap activation

K1 concluded that heap scan/cost parity is feasible incrementally, while heap
mutation and locking should be deferred.  M1 then introduced an isolated
`EngineHeapTableAccessLiveCandidate` that can talk to Derby's physical scan APIs
without being used by SQL execution.

M2 keeps the next step equally narrow.  It proves the factory branch can choose a
heap-specific shadow result set for ordinary heap tables, but the shadow result
set still delegates behavior to Derby's `TableScanResultSet` by inheritance.

This avoids changing heap semantics while giving M3 a concrete, guarded branch
from which a supported-shape read provider can later be introduced.

## Acceptance

M2 is accepted when the guard proves:

```text
- shadow flag disabled: heap SELECT uses ordinary TableScanResultSet path
- shadow flag enabled: heap SELECT reaches DelosHeapScanShadowResultSet
- delos_mvcc SELECT still reaches DelosTableScanResultSet, not heap shadow
- heap shadow applies only to default provider, read-only base table scans
- heap mutations remain RowChanger / ConglomerateController-owned
- DelosNativeTableRegistry still registers only delos_mvcc
- no generic heap mutation/lock/reservation contract appears
```

## Next

M3 may attempt a supported-shape heap SELECT live route from this property-gated
branch.  M3 must still preserve Derby-native fallback for unsupported heap SELECT
shapes.
