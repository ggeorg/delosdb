# Storage Phase S8 — FaultInjectingPageVolume

## Goal

Add a deterministic I/O-level fault decorator for `DelosPageVolume`.

This phase is still inside the DelosDB Unified Storage I/O Abstraction work. It
adds the test/research volume needed by later recovery proofs, but it does not
wire recovery policy yet.

## Added

- `FaultInjectingPageVolume`
- `FaultInjectingPageVolume.FaultSchedule`
- `runFaultInjectingPageVolumeSmoke`

## Boundary

The decorator can inject:

- failure on the n-th complete-page `writePage(...)`
- failure on the n-th `force()` durability boundary

The decorator does not know about:

- transactions
- commit / abort
- MVCC visibility
- outcome recovery policy
- SQL
- heap
- provider dispatch

## Why no recovery wiring here

S8 supplies the deterministic I/O failure primitive. Recovery behavior will be
proven in the next phase using this decorator. Keeping fault injection below
recovery policy preserves the storage I/O separation of concern.

## Acceptance

- no-fault schedule preserves delegate behavior
- configured write failure is deterministic
- configured write failure does not update the delegate
- configured force failure is deterministic
- retry after a one-shot force failure can pass
- `delosdb-storage-io` still has no upward dependency on MVCC, engine, heap,
  SQL, Derby, or provider-dispatch classes
- O5/C7 provider regression smokes remain green
