# Storage Phase N1.5 — Heap RowChanger mutation adapter direct proof

## Purpose

N1.5 introduces one narrow internal adapter around Derby's existing `RowChanger`
mutation surface:

```text
EngineHeapRowChangerMutationAdapter
```

This is still a proof step. It is not N2. It is not N3.

## Decision

The N1.2 and N1.3 direct proofs showed that heap INSERT, DELETE, and UPDATE can
be driven through Derby's `RowChanger` when the caller supplies the real Derby
heap mutation context. N1.5 wraps those calls behind one internal adapter so the
next decision can examine one seam rather than three duplicated direct proofs.

## Adapter surface

```text
EngineHeapRowChangerMutationAdapter.open(...)
insert(ExecRow row) -> RowLocation
update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)
delete(ExecRow row, RowLocation rowLocation)
finish()
close()
```

The adapter receives explicit Derby context:

```text
ExecutionFactory
heapConglom
heapSCOCI
heapDCOCI
irgs
indexCIDS
indexSCOCIs
indexDCOCIs
numberOfColumns
TransactionController
changedColumnIds
baseRowReadList
baseRowReadMap
streamStorableColIds
Activation
indexNames
lockMode
```

## What N1.5 proves

N1.5 proves, through an ordinary heap table, that the adapter can:

```text
- insert a heap row and return RowLocation
- update a heap row by RowLocation
- delete a heap row by RowLocation
- finish and close the underlying RowChanger
- leave normal SQL SELECT able to verify the final table contents
```

## What N1.5 does not do

```text
No SQL routing change.
No heap INSERT live route.
No heap DELETE live route.
No heap UPDATE live route.
No DelosHeapInsertResultSet.
No DelosHeapDeleteResultSet.
No DelosHeapUpdateResultSet.
No EngineHeapMutableTableAccess.
No heap implementation of DelosMutableTableAccess.
No generic Delos mutation API.
No generic DelosMutableTableAccess.tryLock(...).
No heap row reservation.
No heap locking parity claim.
Do **not** start N2 yet.
Do **not** start N3 yet.
```

## Why this is still not provider parity

`EngineHeapRowChangerMutationAdapter` is intentionally Derby-internal. It is not
a storage-provider contract. It still depends on Derby's own `RowChanger`,
`TransactionController`, compiled conglomerate information, and current language
context.

That is acceptable for N1.5 because the proof is not claiming heap is a full
Delos mutation provider. The proof only reduces the future risk of N2/N3 by
putting Derby heap mutation behind one explicit internal seam.

## Next safe step

After N1.5 is green, the next safe step is:

```text
N1.6 — adapter-backed heap mutation classification decision
        decide whether N2 can start with INSERT only,
        or whether another non-routed proof is needed first.
```
