# Storage Phase S10 — MemorySegment Migration Gate

S10 is a gate, not a hidden migration.

The storage I/O proposal names `MemorySegment + Arena` as the target page-memory direction, but the current build baseline is Java 21. The Foreign Function & Memory API becomes stable in Java 22. Therefore this phase records the gate and prevents accidental partial migration.

## Decision

Do not migrate `ByteBuffer` page internals to `MemorySegment` in S10.

S10 proves that:

- `delosdb-storage-io` still compiles on the current Java baseline.
- no production storage I/O or page-backed storage source imports `java.lang.foreign`;
- no production source uses `MemorySegment`, `Arena.of`, or `Linker.nativeLinker`;
- the migration remains an explicit future overlay, not an accidental mixed-platform change.

## Scope

Covered:

- Java baseline gate;
- source-shape guard against accidental FFM usage;
- explicit deferral of MemorySegment migration while Java 21 remains the baseline.

Not covered:

- `ByteBuffer` to `MemorySegment` migration;
- mapped page volume;
- O_DIRECT;
- io_uring;
- recovery policy changes;
- heap/provider work.

## Acceptance

Run:

```bash
./gradlew :delosdb-storage-io:runStoragePhaseS10MemorySegmentGateSmoke \
          :delosdb-storage-io:verifyStoragePhaseS10MemorySegmentGate
```

The gate passes when the current baseline remains clean and the future migration remains explicit.
