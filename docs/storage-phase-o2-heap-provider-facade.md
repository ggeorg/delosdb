# Storage Phase O2 — Heap provider facade

O2 is the next full-provider-parity closeout step after O1.

O1 proved that one property gate can activate the supported heap live routes together:

- heap SELECT
- heap INSERT
- heap UPDATE
- heap DELETE

O2 starts consolidating those scattered result-set-local pieces behind a real heap access facade:

```text
EngineHeapTableAccess
```

## Decision

O2 does **not** make a new generic locking or reservation contract.

The heap facade is honest about what exists today:

```text
scan:
  EngineHeapTableAccess.scan(...)
    -> EngineHeapTableAccessLiveCandidate
    -> TransactionController.openCompiledScan/openScan
    -> ScanController

cost:
  EngineHeapTableAccess.estimateTableCost(...)
    -> existing Derby heap cost mapping

insert mutation adapter:
  EngineHeapTableAccess.openMutationAdapter(...)
    -> EngineHeapRowChangerMutationAdapter
    -> RowChanger
```

DELETE and UPDATE remain available through the already-proven N3 result-set boundary. They are not yet moved behind a provider-neutral mutable table contract in O2.

## Non-goals

O2 deliberately does not add:

```text
- EngineHeapMutableTableAccess
- heap implementation of DelosMutableTableAccess
- generic DelosMutableTableAccess.tryLock(...)
- generic reserveMutation(...)
- heap MVCC-style reservation
- heap locking parity claim beyond Derby-owned row locking / transaction ownership
- default-on provider parity
```

## Acceptance

O2 is accepted when the behavior smoke proves:

```text
- O1/O2 gate still enables supported heap SELECT / INSERT / UPDATE / DELETE
- heap SELECT opens through EngineHeapTableAccess.scan(...)
- heap INSERT opens its RowChanger adapter through EngineHeapTableAccess.openMutationAdapter(...)
- final SQL row state after INSERT + UPDATE + DELETE is correct
- no exact-text history-marker guard chain is reintroduced
```

## Next

After O2 is green, the next honest closeout step is:

```text
O3 — move heap DELETE / UPDATE behind the heap facade or document why Derby-owned result-set inheritance remains the honest boundary
```
