# Storage Phase L1 — MVCC row reservation

## Decision

L1 adds a narrow, MVCC-specific row-reservation capability for native
`delos_mvcc` mutation concurrency.

This is **not** a generic Delos mutable-table lock API.

Current provider truth remains:

```text
Live Delos provider:
  delos_mvcc

Derby-native provider:
  heap

Proof-only heap adapter:
  EngineHeapTableAccessProof
```

## Why this is L1 and not heap provider parity

K1 chose the safe A-lite route:

```text
heap scan/cost parity:
  feasible incrementally later

heap mutation parity:
  defer

heap locking parity:
  defer
```

That means L1 must not add a method such as:

```text
DelosMutableTableAccess.tryLock(...)
```

Heap cannot honestly implement that while heap mutation still belongs to Derby's
`RowChangerImpl` / `ConglomerateController` path.

## New shape

L1 introduces:

```text
DelosMvccReservableTableAccess
DelosMvccMutationReservation
```

Only `EngineMvccTableAccess` implements the new capability.

The generic mutation contract remains:

```text
DelosMutableTableAccess.validateMutable(...)
DelosMutableTableAccess.prepareMutation(...)
insert/update/delete(...)
```

`DelosMutationPreparation` remains deliberately honest: it still does not expose
`lockAcquired`, `reservationAcquired`, or any equivalent generic lock-state
field.

## Execution behavior

For native `delos_mvcc` UPDATE/DELETE:

```text
DelosTableScanResultSet
  -> DelosRowIdentity
  -> EngineMvccTableAccess.reserveMutation(...)
  -> EngineMvccTableAccess.update/delete(...)
  -> VersionedWriteConflictException if another active transaction reserved row
  -> DelosMutationConflictMapper maps to SQLState 40001
```

Reservations are keyed by:

```text
DelosTableIdentity + native MVCC row key
```

They are released when the native statement transaction commits or aborts.

## What this does not claim

L1 does not claim:

```text
- heap live mutation routing
- heap row locking
- generic Delos tryLock semantics
- deadlock detection
- wait/notify lock scheduling
- lock escalation
- predicate/range locks
- Serializable isolation
```

The conflict surface remains the existing native MVCC write/write conflict
surface, mapped at Derby execution boundary to SQLState `40001`.

## Guard

`verifyStoragePhaseL1MvccRowReservation` proves:

```text
- DelosMutableTableAccess has no reserveMutation or tryLock method
- DelosMvccReservableTableAccess exists as MVCC-only capability
- EngineMvccTableAccess implements MVCC row reservation
- EngineHeapTableAccessProof does not implement MVCC row reservation
- DelosMutationPreparation remains free of generic lock/reservation state
- direct competing MVCC reservation conflicts
- a held reservation blocks native SQL UPDATE with SQLState 40001
- a held reservation blocks native SQL DELETE with SQLState 40001
- reservation release after abort allows later mutation success
```

## Next step

After L1, continue toward M1 only when we are ready to investigate heap scan as a
candidate without SQL routing.  Do not activate heap mutation or locking from
this step.
