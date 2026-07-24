# DelosDB v1 JDK 25 heap MemorySegment page-buffer experiment

Status: Stage 8.4 verified experiment; removed from the v1 runtime by Stage 8.7.2.

## Experiment result

Stage 8.4 proved that a stable `MemorySegment.ofArray(pageData)` alias could preserve the inherited
RawStore page format, file-pointer semantics, memory-database behavior, heap/MVCC state, diagnostics,
and fault boundaries.

The proof was correct, but the alias did not improve the production ownership model:

```text
byte[] pageData remained authoritative
ByteBuffer.wrap(pageData) was already zero-copy
channel operations still required a ByteBuffer view
one wrapper and one segment object were added per cached page
RawStore gained another dispatch layer
```

## Final decision

```text
REMOVE_HEAP_MEMORY_SEGMENT_FROM_V1_RAWSTORE
```

Stage 8.7.2 restores the direct positional byte-array path and removes:

```text
MemorySegment methods from StorageRandomAccessFile and DirRandomAccessFile
DelosHeapPageBuffer
CachedPage segment ownership
FileContainer segment bridges
RAFContainer and RAFContainer4 segment dispatch
old Stage 8.4 runtime-focused test and task
```

The Stage 8.7.1 shared complete-transfer helpers remain, so directory storage still has one readable
channel-read loop and one readable channel-write loop.

## Retained evidence

The experiment remains documented and reproducible through the test-only representation benchmark:

```text
:delosdb-tests:runDelosSharedRawStorePageIoRepresentationDecisionTest
delosSharedRawStoreHeapMemorySegmentPageBufferStaticAnalysis
```

The gate now protects the final removal decision and absence of heap-segment ownership in production
RawStore code.

See `V1-JDK25-RAWSTORE-PAGE-IO-REPRESENTATION-DECISION.md` for the complete Stage 8.7.2 decision.
