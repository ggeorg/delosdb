# Storage Phase O5 — Full provider parity closeout

O5 is the closeout proof for destination A.

## Decision

DelosDB now has two live provider routes under the Delos storage architecture:

| Provider | Scan | Insert | Update | Delete | Cost | Transaction / locking truth |
| --- | --- | --- | --- | --- | --- | --- |
| `delos_mvcc` | live native route | live native route | live native route | live native route | MVCC stats-backed estimate | MVCC snapshot plus MVCC reservation/concurrency |
| `heap` | live supported route through `EngineHeapTableAccess` | live supported route through `EngineHeapTableAccess.openMutationAdapter(...)` | live supported RowChanger-backed route | live supported RowChanger-backed route | Derby heap cost mapping through `EngineHeapTableAccess` | Derby-owned row locking and recovery-log semantics |

The old proof-only heap source name is gone:

```text
EngineHeapTableAccessProof.java
```

The real heap facade is:

```text
EngineHeapTableAccess
```

The shared Derby heap support/helper class is:

```text
EngineHeapDerbyAccessSupport
```

## Important honesty boundary

O5 does **not** claim that heap has MVCC snapshot isolation or MVCC-style row reservation.

O5 does **not** add:

```text
DelosMutableTableAccess.tryLock(...)
reserveMutation(...) on the generic mutable contract
heap MVCC-style reservation
heap snapshot isolation
```

Heap locking remains Derby-owned. That is the honest heap guarantee.

## Acceptance

O5 is accepted when the behavior smoke proves:

```text
- stale EngineHeapTableAccessProof.java has been deleted
- heap facade exposes scan/cost capability and Derby-owned guarantees
- heap facade cost mapping returns the expected estimate
- delos_mvcc INSERT / UPDATE / DELETE / SELECT use live native routes
- heap INSERT / UPDATE / DELETE / SELECT use supported live heap routes under the O gate
- heap SELECT reaches EngineHeapTableAccess
- heap INSERT reaches EngineHeapTableAccess.openMutationAdapter(...)
- heap lookup remains default-provider heap
```

At this point the architecture claim is true in the supported-route sense:

```text
DelosDB has two live providers under the Delos storage architecture:
  delos_mvcc
  heap
```
