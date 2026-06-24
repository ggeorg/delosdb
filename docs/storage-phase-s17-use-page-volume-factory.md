# Storage Phase S17 — Use Page-Volume Factory in Page-Backed Stores

S17 wires the storage I/O construction seam into the existing page-backed stores without adding any Gradle phase tasks or source-shape guards.

## Scope

Changed:

- `PageBackedMvccTableStore` now opens and reopens page volumes through `DelosPageVolumeFactory`.
- `MvccIndexStore` now opens and reopens page volumes through `DelosPageVolumeFactory`.

Preserved:

- `DelosPageVolume` remains path-free.
- File-backed behavior remains the default.
- Rewrite/compact behavior remains file-backed and path-owned by the store.
- No heap/provider behavior changes.
- No `DelosStorageDispatch`.
- No new Gradle verification task.
- No source-shape guard.

## Why

S12 added the construction seam. S17 uses it in production storage code so the factory is not dead infrastructure.

The default factory remains `DelosPageVolumeFactories.fileChannel()`, so behavior stays equivalent to direct `FileChannelPageVolume.open(path)`.
