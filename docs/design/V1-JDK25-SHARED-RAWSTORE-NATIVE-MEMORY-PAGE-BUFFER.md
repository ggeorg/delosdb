# DelosDB v1 JDK 25 native page-I/O mirror experiment

Status: Stage 8.5 verified experiment; removed from the v1 runtime by Stage 8.7.2.

## Experiment result

Stage 8.5 proved that directory RawStore page I/O could use bounded native `MemorySegment` mirrors
without changing the inherited page format, WAL, recovery, locking, or transaction semantics.
Database-scoped hard limits, fallback, lease cleanup, diagnostics, heap/MVCC sharing, and shutdown
leak evidence were verified.

The proof also established the permanent costs:

```text
full-page heap-to-native copy before every write
full-page native-to-heap copy after every read
one arena and lease lifecycle per admitted cached page
additional capability, accounting, diagnostics, and shutdown code
production default disabled
no supported application or database selector
```

## Final decision

```text
REMOVE_NATIVE_PAGE_IO_MIRROR_FROM_V1_RAWSTORE
```

The native mirror did not demonstrate a v1 performance or ownership benefit sufficient to justify
its runtime surface. Stage 8.7.2 removes:

```text
StorageFactory native-segment capability
DelosRawStoreNativeMemory
DelosRawStoreNativeMemoryDirectory
native leases from cached pages
native read/write dispatch
native diagnostics fields and counters
native test bridge and old Stage 8.5 runtime-focused task
```

`DelosRawStoreIoSnapshot` advances to schema version 3 and returns to operational page-I/O,
force, recovery, and handle evidence only.

## Retained evidence

The native representation remains reproducible in test-only code:

```text
:delosdb-tests:runDelosSharedRawStorePageIoRepresentationDecisionTest
delosSharedRawStoreNativeMemoryPageBufferStaticAnalysis
```

The gate now protects the final removal decision and absence of native page ownership in production
RawStore code.

See `V1-JDK25-RAWSTORE-PAGE-IO-REPRESENTATION-DECISION.md` for the complete Stage 8.7.2 decision.
