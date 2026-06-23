# Storage Phase N1.3 — Direct RowChanger-backed heap DELETE / UPDATE proof

## Decision

N1.3 is a direct proof only.

It proves that Derby heap DELETE and UPDATE can be driven through Derby's existing `RowChanger` API when the caller already has the full Derby-owned mutation context and a real `RowLocation`.

No SQL routing change.
No heap mutation provider.
No generic Delos mutation API.
No heap lock/reservation abstraction.
Do **not** start N2 yet.
Do **not** start N3 yet.

## What this proves

The proof creates an ordinary Derby heap table, enters the Derby `EmbedConnection` language context, obtains the `DataDictionary` table descriptor, builds a `RowChanger` through `ExecutionFactory.getRowChanger(...)`, and then calls:

```text
RowChanger.updateRow(...) directly
RowChanger.deleteRow(...) directly
```

The proof obtains the required `RowLocation` from the same direct RowChanger heap path used in N1.2:

```text
RowChanger.insertRow(..., true)
```

After the direct heap UPDATE / DELETE operations, normal SQL reads verify the durable table contents.

## Honest boundary

This does not make heap a Delos mutation provider.

The context required for UPDATE / DELETE is still Derby-owned:

```text
heapConglom
heapSCOCI
heapDCOCI
TransactionController
ExecutionFactory
RowChanger
ExecRow old image
ExecRow new image
RowLocation
lockMode
```

That means the safe route remains:

```text
N1.3  direct RowChanger-backed DELETE / UPDATE proof, no SQL routing
N1.4  decide whether RowChanger-backed heap mutation can be wrapped behind a narrow internal adapter
N2    heap INSERT live path only if N1.4 says yes
N3    heap DELETE / UPDATE live path only if N1.4 says yes
```

## Explicit non-goals

```text
No DelosHeapInsertResultSet
No DelosHeapDeleteResultSet
No DelosHeapUpdateResultSet
No EngineHeapMutableTableAccess
No heap INSERT routing
No heap DELETE routing
No heap UPDATE routing
No generic DelosMutableTableAccess.tryLock(...)
No generic reserveMutation(...)
No bridge resurrection
```
