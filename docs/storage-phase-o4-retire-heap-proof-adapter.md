# Storage Phase O4 — retire heap proof adapter naming

O4 is a cleanup and truth-alignment step after O3.

The accepted truth is now:

- `delos_mvcc` is a live native Delos provider for supported scan, insert, update, delete, cost, and MVCC reservation behavior.
- `heap` has supported live SELECT / INSERT / UPDATE / DELETE routes behind the O gate.
- heap SELECT and INSERT now pass through `EngineHeapTableAccess`.
- heap UPDATE and DELETE remain RowChanger-backed through the accepted result-set boundary.
- heap locking and transaction behavior remain Derby-owned; no MVCC-style heap reservation is claimed.

## Cleanup

The old `EngineHeapTableAccessProof` class name is retired. It was accurate during K/M/N proof work, but after O1/O2/O3 it became misleading.

The remaining shared heap support is now named:

```text
EngineHeapDerbyAccessSupport
```

That class is not a mutable provider API. It provides shared heap provider name, scan/cost context keys, heap row identity wrapping, and Derby cost support used by `EngineHeapTableAccess`, the heap scan candidate, and heap cost mapping.

## Non-goals

O4 does not add:

- heap MVCC-style locking;
- generic `tryLock(...)`;
- generic `reserveMutation(...)`;
- heap implementation of `DelosMutableTableAccess`;
- default-on final parity.

## Acceptance

O4 is complete when:

1. the old `EngineHeapTableAccessProof.java` source file is removed;
2. production code uses `EngineHeapDerbyAccessSupport`;
3. O3 two-provider behavior still works after the rename;
4. no new exact-text history-marker guard is introduced.
