# Storage Phase S7 — OffHeapPageVolume

S7 adds an in-memory `DelosPageVolume` implementation under `delosdb-storage-io`.

## Scope

`OffHeapPageVolume` is a raw storage I/O implementation. It owns only:

- dense page-id-indexed in-memory page images
- complete page read/write
- page allocation
- page count
- no-op force boundary
- `SyncPolicy.NONE`
- close/open state

It does not own MVCC visibility, transaction state, recovery policy, SQL execution, heap behavior, or provider dispatch.

## Design notes

The implementation stores encoded page images rather than mutable `DelosPage` objects. Reads decode a fresh page instance, and writes copy the complete page image. This preserves complete-page semantics and avoids object aliasing in tests.

Sparse writes are rejected. Pages are dense and allocated by page id in ascending order.

## Boundary rules

`OffHeapPageVolume` must not import from:

- `delosdb-storage-mvcc`
- Derby / `org.apache.derby`
- heap / engine classes
- SQL execution classes
- provider-dispatch classes

`OffHeapPageVolume` has no filesystem identity. It must not expose `path()`.

## Verification

Run:

```bash
./gradlew :delosdb-storage-io:runOffHeapPageVolumeSmoke \
          :delosdb-storage-io:verifyDelosDbStorageIoBoundary \
          storagePhaseO5FullProviderParityCloseoutSmoke \
          storagePhaseC7StabilizationSmoke
```
