# Storage Phase S12 — Page Volume Factory Preparation

S12 adds the small construction seam for page volumes.

Added:

- `DelosPageVolumeFactory`
- `DelosPageVolumeFactories`
- `runDelosPageVolumeFactorySmoke`

This is real storage construction work only. It does not introduce provider dispatch, heap migration, append-log abstraction, recovery-policy changes, or source-shape guard work.

The factory helpers cover the existing page-volume implementations:

- `fileChannel(syncPolicy)`
- `mapped(syncPolicy, maxPages)`
- `offHeap()`
- `faultInjecting(delegateFactory, schedule)`

This prepares later volume/layout wiring without forcing mutation logs or outcome logs into `DelosPageVolume`.
