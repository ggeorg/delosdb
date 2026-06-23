# Storage Phase N1.4 — Heap RowChanger Adapter Decision

## Purpose

N1.4 decides whether the direct RowChanger proofs from N1.2 and N1.3 are strong enough to justify a narrow heap mutation adapter before any heap SQL mutation routing begins.

This is still a decision and guard milestone. It does not introduce heap mutation routing.

## Decision

Decision: YES, but internal-only adapter first.

The RowChanger-backed heap mutation path is wrappable, but it must be wrapped as a Derby-internal heap adapter before it is exposed through any provider-level mutation route.

The safe next milestone is N1.5, not N2:

```text
N1.5 — EngineHeapRowChangerMutationAdapter direct proof
        INSERT / DELETE / UPDATE through RowChanger behind one narrow internal adapter
        no SQL routing
        no DelosMutableTableAccess heap implementation yet
        no heap lock/reservation API
```

Do **not** start N2 yet.
Do **not** start N3 yet.

## Why the adapter is feasible

N1.2 proved direct heap INSERT with:

```text
ExecutionFactory.getRowChanger(...)
RowChanger.open(...)
RowChanger.insertRow(..., true)
RowLocation returned
normal SQL SELECT verifies visibility
```

N1.3 proved direct heap DELETE / UPDATE with:

```text
RowChanger.updateRow(...) directly
RowChanger.deleteRow(...) directly
RowLocation
normal SQL SELECT verifies final table contents
```

The required context was identified in N1.1:

```text
heapConglom
heapSCOCI
heapDCOCI
irgs
indexCIDS
indexSCOCIs
indexDCOCIs
indexNames
numberOfColumns
changedColumnIds
baseRowReadList
baseRowReadMap
TransactionController
Activation
lockMode
```

That context is Derby-owned and already present in existing InsertResultSet, DeleteResultSet, and UpdateResultSet code paths. The adapter should centralize that context instead of inventing a generic Delos mutation contract too early.

## Adapter shape allowed after N1.4

The allowed N1.5 production shape is narrow and internal:

```text
EngineHeapRowChangerMutationAdapter
  insert(ExecRow row) -> RowLocation
  update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)
  delete(ExecRow row, RowLocation rowLocation)
```

It may use:

```text
ExecutionFactory.getRowChanger(...)
RowChanger.open(lockMode)
RowChanger.insertRow(..., true)
RowChanger.updateRow(...)
RowChanger.deleteRow(...)
RowChanger.finish()
RowChanger.close()
```

It must stay Derby-internal and RowChanger-backed.

## Adapter shape not allowed yet

N1.4 does not permit:

```text
DelosHeapInsertResultSet
DelosHeapDeleteResultSet
DelosHeapUpdateResultSet
EngineHeapMutableTableAccess
heap implementation of DelosMutableTableAccess
heap SQL INSERT routing
heap SQL DELETE routing
heap SQL UPDATE routing
DelosMutableTableAccess.tryLock(...)
DelosMutableTableAccess.reserveMutation(...)
heap row reservation
heap locking parity claim
```

## Why not N2 yet

The RowChanger proofs show that heap mutation can be performed honestly, but they do not yet prove that the context can be carried safely through one reusable production adapter. N1.5 should prove that first.

N2 can only start after a direct adapter proof shows that INSERT can be performed through the adapter without smuggling state through SQL result-set routing.

N3 can only start after the same adapter shape proves DELETE / UPDATE with explicit RowLocation input and Derby-owned locking behavior.

## Current route

```text
N1.4 — decide adapter route: YES, internal-only
N1.5 — direct EngineHeapRowChangerMutationAdapter proof, no SQL routing
N2   — heap INSERT live path, only if N1.5 is green and still honest
N3   — heap DELETE / UPDATE live path, only if N1.5 is green and still honest
```
