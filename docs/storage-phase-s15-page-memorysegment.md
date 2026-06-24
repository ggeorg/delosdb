# Storage Phase S15 — MemorySegment Page Image

S15 moves the raw `DelosPage` page image from a plain `byte[]` field to a
`java.lang.foreign.MemorySegment` field now that the build baseline has moved to
Java 25.

This is intentionally narrow:

- page format is unchanged
- `DelosPageVolume` is unchanged
- file/mapped/off-heap/fault volume behavior is unchanged
- MVCC recovery policy is unchanged
- heap/provider behavior is unchanged

The external storage API still exchanges complete encoded page images where it
already did. The MemorySegment change is inside the raw storage page primitive.

Run:

```bash
bash ./scripts/delete-stale-storage-smoke-dbs.sh
./gradlew :delosdb-storage-io:compileDelosDbStorageIo \
          :delosdb-storage-io:runDelosPagePrimitiveSmoke \
          :delosdb-storage-io:runDelosPageMemorySegmentSmoke \
          :delosdb-storage-io:runDelosPageVolumeContractSmoke \
          :delosdb-storage-io:runFileChannelPageVolumeSmoke \
          :delosdb-storage-io:runOffHeapPageVolumeSmoke \
          :delosdb-storage-io:runFaultInjectingPageVolumeSmoke \
          :delosdb-storage-io:runMappedPageVolumeSmoke \
          :delosdb-storage-io:runDelosPageVolumeFactorySmoke \
          storagePhaseO5FullProviderParityCloseoutSmoke \
          storagePhaseC7StabilizationSmoke
```
