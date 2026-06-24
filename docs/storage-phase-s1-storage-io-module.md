# Storage Phase S1 — Storage I/O Module Boundary

S1 creates the `delosdb-storage-io` Gradle module and neutral Java package for
DelosDB Unified Storage I/O Abstraction work.

This overlay is intentionally behavior-preserving. It does not introduce
`DelosPage`, `DelosPageId`, `DelosPageVolume`, file-backed page volumes,
off-heap volumes, fault injection, rewrite lifecycle changes, recovery changes,
or provider dispatch.

The module boundary is guarded by `:delosdb-storage-io:verifyDelosDbStorageIoBoundary`.
The guard proves that `delosdb-storage-io` does not depend on MVCC, engine,
Derby, heap, SQL, or provider-dispatch code.

Heap remains Derby-owned and protected by O5/C7 smokes. The current Delos
page-backed path remains the first consumer in later milestones, but S1 does
not migrate any caller.
