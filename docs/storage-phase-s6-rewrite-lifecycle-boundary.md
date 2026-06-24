# Storage Phase S6 — Rewrite Lifecycle Boundary

S6 migrates the page-backed table store to the storage I/O page-volume contract
without leaking file identity into `DelosPageVolume`.

## What changed

- `PageBackedMvccTableStore` now owns the table-store `Path` separately from the
  raw `DelosPageVolume`.
- Rewrite/open/reopen lifecycle stays above the raw volume contract.
- `PageBackedMvccTableStore` opens file-backed storage through
  `FileChannelPageVolume.open(path)`.
- `DelosPageVolume` remains path-free.
- `MvccVersionLocator` now carries the storage-io-owned `DelosPageId` primitive.

## What did not change

- no heap/provider migration
- no `DelosStorageDispatch`
- no `DelosAppendVolume`
- no off-heap/fault volume yet
- no recovery-policy change
- no page format change
- no MVCC visibility or transaction-semantics change

## Acceptance

- page-backed table store uses `DelosPageVolume`
- no `MvccPageFile` dependency remains in `PageBackedMvccTableStore`
- no `path()` method is added to `DelosPageVolume`
- file-backed rewrite still closes, replaces, reopens, and reloads through the
  table-store lifecycle owner
- O5/C7 smokes remain green, protecting heap and provider parity by regression
