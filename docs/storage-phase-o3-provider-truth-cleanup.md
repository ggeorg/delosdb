# Storage Phase O3 — Provider truth cleanup

O3 updates the current architecture truth after the accepted O2 heap provider facade.

## Current truth after O2

`delos_mvcc` remains the fully native MVCC provider for its supported SQL path:

```text
scan
insert
delete
update
cost
MVCC-specific reservation/concurrency
```

Default-provider heap is no longer only a compile-time proof adapter. Heap now has supported live read/write execution routes behind the heap provider-parity gate:

```text
heap SELECT  -> DelosHeapLiveTableScanResultSet -> EngineHeapTableAccess.scan(...)
heap INSERT  -> DelosHeapInsertResultSet -> EngineHeapTableAccess.openMutationAdapter(...)
heap UPDATE  -> DelosHeapUpdateResultSet -> Derby RowChanger-owned update boundary
heap DELETE  -> DelosHeapDeleteResultSet -> Derby RowChanger-owned delete boundary
heap cost    -> EngineHeapTableAccess -> existing heap cost mapping
```

## What O3 deliberately does not claim

O3 does not claim heap MVCC-style locking parity.

O3 does not add a generic `tryLock(...)` method.

O3 does not add a generic reservation API.

O3 does not say heap implements MVCC reservation.

O3 does not delete `EngineHeapTableAccessProof` yet. That is a separate O4 cleanup once all remaining proof-only references are retired safely.

## Why this is a cleanup phase

Earlier docs and guards were written when the honest state was:

```text
Live Delos provider: delos_mvcc
Derby-native provider: heap
Proof-only adapter: EngineHeapTableAccessProof
```

After M3, N2, N3, O1, and O2, that is stale. The current truth is:

```text
Live MVCC provider:
  delos_mvcc

Live heap execution provider for supported shapes:
  heap through EngineHeapTableAccess plus RowChanger-owned Derby mutation boundaries

Still not claimed:
  heap MVCC-style locking/reservation parity
```

## Verification

The O3 smoke is behavior-focused. It does not check exact comments or historical marker strings.

It proves in one run:

```text
- a delos_mvcc table uses the native provider route for INSERT / UPDATE / DELETE / SELECT
- a heap table uses the live heap route for INSERT / UPDATE / DELETE / SELECT under the O gate
- heap SELECT passes through EngineHeapTableAccess
- heap INSERT passes through EngineHeapTableAccess.openMutationAdapter(...)
- heap DELETE / UPDATE remain supported live routes without a heap locking claim
```

