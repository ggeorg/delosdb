# Storage Phase S5 — Safe Caller Page-Volume Migration

S5 migrates only the safe page-backed caller that does not require the table
rewrite lifecycle boundary: `MvccIndexStore`.

## What changed

`MvccIndexStore` now depends on the neutral storage I/O contract:

- `DelosPageVolume`
- `DelosPage`
- `DelosPageId`
- `FileChannelPageVolume`

It no longer directly depends on:

- `MvccPageFile`
- `MvccPage`
- `MvccPageId`

## What did not change

S5 does not migrate `PageBackedMvccTableStore` yet because its `rewrite()` path
still owns file lifecycle behavior. That lifecycle is the S6 boundary and must
not be hidden behind `DelosPageVolume.path()`.

S5 also does not add off-heap storage, fault injection, recovery rewiring, or
any provider-dispatch behavior.

## Boundary rule

`delosdb-storage-io` remains below `delosdb-storage-mvcc`. The storage I/O module
must not depend on MVCC, engine, Derby, heap, SQL, or provider-dispatch classes.

## Verification

The S5 verification checks that:

- `MvccIndexStore` uses `DelosPageVolume`.
- `MvccIndexStore` no longer directly imports the old MVCC page-file primitives.
- `PageBackedMvccTableStore` remains unmigrated until the S6 rewrite lifecycle boundary.
- O5 and C7 regression smokes continue to protect heap/provider behavior.
