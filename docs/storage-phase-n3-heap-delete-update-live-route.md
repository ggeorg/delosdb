# Storage Phase N3 — Heap DELETE / UPDATE live route

N3 is the heap DELETE / UPDATE counterpart to N2.

It introduces a property-gated heap mutation route for supported ordinary heap DELETE and UPDATE statements only:

```text
delosdb.storage.phaseN3.heapDeleteUpdateLiveRoute=true
```

## Scope

N3 is deliberately narrow:

```text
GenericResultSetFactory.getDeleteResultSet(...)
  -> DelosDeleteResultSet.createIfEnabled(...)
       delos_mvcc native DELETE remains first
  -> DelosHeapDeleteResultSet.createIfEnabled(...)
       default-provider heap only
       property-gated
       ordinary supported heap DELETE only
  -> DeleteResultSet(...)
       ordinary Derby fallback

GenericResultSetFactory.getUpdateResultSet(...)
  -> DelosUpdateResultSet.createIfEnabled(...)
       delos_mvcc native UPDATE remains first
  -> DelosHeapUpdateResultSet.createIfEnabled(...)
       default-provider heap only
       property-gated
       ordinary supported heap UPDATE only
  -> UpdateResultSet(...)
       ordinary Derby fallback
```

The heap DELETE/UPDATE route keeps Derby's RowChanger-owned behavior by extending Derby's existing `DeleteResultSet` and `UpdateResultSet` instead of inventing a new heap mutation contract.

## Supported shape

N3 supports ordinary immediate heap DELETE/UPDATE shapes:

```text
no FK
no trigger
not deferred
not MERGE-owned
no generated/check clause for UPDATE
no autoincrement update shape
```

Unsupported shapes continue through the ordinary Derby result sets.

## Non-goals

N3 does not introduce:

```text
EngineHeapMutableTableAccess
heap implementation of DelosMutableTableAccess
generic DelosMutableTableAccess.tryLock(...)
generic reserveMutation(...)
heap row-reservation parity
heap locking parity
provider-neutral deadlock detection
```

## Decision

N3 is a live heap mutation milestone for DELETE/UPDATE execution boundaries, but it remains RowChanger-backed and property-gated. It is not Phase O full provider parity.

The next step after N3 is O-prep only if the DELETE/UPDATE route is stable, or a narrow N3 repair if runtime behavior exposes a Derby shape that must fall back instead of route.
