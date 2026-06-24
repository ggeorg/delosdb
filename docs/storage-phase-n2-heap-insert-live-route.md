# Storage Phase N2 — Heap INSERT live route

## Decision

N2 starts the first heap mutation live route, but only for INSERT and only behind an explicit property gate:

```text
delosdb.storage.phaseN2.heapInsertLiveRoute=true
```

This is the next step after N1 / N1.5 proved the RowChanger-backed heap mutation shape. It does not start heap DELETE or heap UPDATE routing.

## Production shape

```text
GenericResultSetFactory.getInsertResultSet(...)
  -> DelosInsertResultSet.createIfEnabled(params)
       delos_mvcc native INSERT remains first
  -> DelosHeapInsertResultSet.createIfEnabled(params)
       default-provider heap only
       property-gated
       supported ordinary INSERT shapes only
       RowChanger-backed through EngineHeapRowChangerMutationAdapter
  -> InsertResultSet(params)
       ordinary Derby fallback
```

`DelosHeapInsertResultSet` uses `EngineHeapRowChangerMutationAdapter`, which wraps Derby's existing `RowChanger` surface. RowChanger still owns heap insertion, row-location creation, conglomerate mutation, and index maintenance.

## Supported N2 shape

N2 supports ordinary immediate heap INSERT rows where Derby has already produced normalized source rows.

The live route is intentionally rejected for:

```text
- delos_mvcc tables
- non-default providers
- deferred INSERT
- bulk INSERT / replace mode
- FK checks
- triggers
- generated-column clauses
- CHECK generated methods
- autoincrement handling
- MERGE-owned INSERT actions
```

Unsupported shapes fall back to ordinary Derby `InsertResultSet`.

## What N2 does not claim

```text
No heap DELETE live route.
No heap UPDATE live route.
No EngineHeapMutableTableAccess.
No new generic DelosMutableTableAccess method.
No generic DelosMutableTableAccess.tryLock(...).
No heap row reservation.
No heap locking parity.
No N3 behavior.
```

## Acceptance

N2 is complete when the smoke proves:

```text
- property disabled: heap INSERT remains Derby-owned
- property enabled: supported heap INSERT reaches DelosHeapInsertResultSet
- heap INSERT uses EngineHeapRowChangerMutationAdapter
- inserted rows are visible through ordinary SQL SELECT
- index maintenance still works through RowChanger
- unsupported heap INSERT falls back to Derby InsertResultSet
- delos_mvcc INSERT remains routed before heap INSERT
- heap DELETE / UPDATE live routes do not exist
- no heap lock/reservation API appears
```

## Next step

After N2 is green, the next step is N3 only if INSERT remains stable:

```text
N3 — heap DELETE / UPDATE live path
```

N3 must still avoid a generic heap lock/reservation claim unless the source proves it can be implemented honestly.
