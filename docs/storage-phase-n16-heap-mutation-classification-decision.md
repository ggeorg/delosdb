# Storage Phase N1.6 — Heap mutation classification decision

## Purpose

N1.6 decides what the N1.5 `EngineHeapRowChangerMutationAdapter` proof permits
next.

N1.6 is still not a heap mutation live route. It is a classification gate before
N2.

## Source-backed decision

N1.2 proved direct RowChanger-backed heap INSERT.
N1.3 proved direct RowChanger-backed heap DELETE / UPDATE.
N1.4 decided that those calls are wrappable behind a narrow internal adapter.
N1.5 proved that adapter for INSERT / DELETE / UPDATE without SQL routing.

The adapter proof is strong enough to start N2, but only for the smallest safe
case:

```text
N2 may start with heap INSERT only.
```

N2 must remain property-gated and RowChanger-backed. It must not generalize heap
mutation parity.

## N2 allowed scope after N1.6 is green

```text
Allowed:
  - DelosHeapInsertResultSet
  - property-gated heap INSERT live route
  - supported ordinary heap table INSERT shapes only
  - EngineHeapRowChangerMutationAdapter as the implementation seam
  - Derby RowChanger remains owner of heap/index mutation mechanics
  - unsupported INSERT shapes fall back to ordinary Derby InsertResultSet

Not allowed:
  - default behavior change
  - heap DELETE live route
  - heap UPDATE live route
  - generic heap mutable provider API
  - EngineHeapMutableTableAccess
  - heap implementation of DelosMutableTableAccess
  - generic DelosMutableTableAccess.tryLock(...)
  - generic reservation API for heap
  - heap locking parity claim
  - N3
```

## Why INSERT first

INSERT is the narrowest heap mutation shape because the source row is already in
the generated Derby execution tree and RowChanger can return the inserted
`RowLocation` directly. DELETE and UPDATE still depend on cursor row identity,
old/new row handling, and more invasive result-set ownership. Those remain N3 or
a later pre-N3 proof.

## N2 design constraint

N2 must not introduce a generic Delos mutation contract just because heap INSERT
can be routed. Heap's working implementation seam is still Derby `RowChanger`,
not a provider-independent mutation API.

## Acceptance for N1.6

N1.6 is complete when the guard proves:

```text
- EngineHeapRowChangerMutationAdapter exists and remains internal-only
- N1.5 adapter proof remains present
- GenericResultSetFactory still has no heap mutation routing yet
- no DelosHeapInsertResultSet exists yet
- no DelosHeapDeleteResultSet exists
- no DelosHeapUpdateResultSet exists
- no EngineHeapMutableTableAccess exists
- no heap implementation of DelosMutableTableAccess exists
- no generic DelosMutableTableAccess.tryLock(...) appears
```

## Next safe step

After N1.6 is green, the next safe step is:

```text
N2 — property-gated heap INSERT live route
     supported ordinary heap INSERT only
     RowChanger-backed through EngineHeapRowChangerMutationAdapter
     no DELETE / UPDATE route
     no heap locking parity
     no generic mutable provider contract
```
