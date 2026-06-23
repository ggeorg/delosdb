# Storage Phase N1.1 — Heap mutation context shape

N1.1 is a shape gate only. It defines the minimum honest context that a later heap mutation proof would have to provide before any heap mutation can be routed through a Delos provider boundary.

No production SQL behavior changes in N1.1.

## Decision

Do **not** start N2 yet.
Do **not** start N3 yet.
Do **not** introduce `EngineHeapMutableTableAccess` yet.
Do **not** introduce heap `DelosHeapInsertResultSet`, `DelosHeapDeleteResultSet`, or `DelosHeapUpdateResultSet`.
Do **not** add a generic `DelosMutableTableAccess.tryLock(...)` or generic reservation API for heap.

The honest next executable proof is still a direct RowChanger-backed proof, not a SQL-routed heap mutation provider.

## Minimum honest context

A later direct RowChanger-backed heap mutation proof must carry Derby's real mutation context rather than flattening it into a generic Delos row API.

The minimum context is:

```text
heap identity:
  heapConglom
  heapSCOCI
  heapDCOCI

index maintenance:
  irgs
  indexCIDS
  indexSCOCIs
  indexDCOCIs
  indexNames

row shape:
  numberOfColumns
  changedColumnIds for UPDATE
  baseRowReadList for partial DELETE / UPDATE rows
  baseRowReadMap for partial UPDATE rows

execution context:
  TransactionController
  Activation
  lockMode decoded from compiled constants

mutation inputs:
  INSERT: ExecRow, optionally returning RowLocation
  DELETE: ExecRow plus RowLocation
  UPDATE: old ExecRow, new ExecRow, RowLocation
```

This is the minimum shape because `RowChangerImpl` is responsible for both heap mutation and index maintenance. It owns the heap `ConglomerateController`, `RowLocation` template, `IndexSetChanger`, and the actual insert/delete/update calls.

## Why this is not a Delos mutable provider yet

Derby heap mutation is not a single storage-method call. The route also includes generated columns, autoincrement, deferred processing, triggers, referential checks, cursor update/delete behavior, index maintenance, lock-mode selection, and row-location handling. Those pieces are still owned by Derby result sets and `RowChangerImpl`.

N1.1 therefore records the context shape only. It does not claim that heap mutation can already implement the same contract as `delos_mvcc` mutation.

## Next safe step

N1.2 may build a direct, non-SQL-routed RowChanger-backed heap INSERT proof if the source shape remains stable.

N1.3 may build direct, non-SQL-routed RowChanger-backed heap DELETE / UPDATE proofs only after N1.2 is green.

N2 must wait until the RowChanger-backed proof shows that an honest `EngineHeapMutableTableAccess` boundary can exist without hiding Derby behavior.
