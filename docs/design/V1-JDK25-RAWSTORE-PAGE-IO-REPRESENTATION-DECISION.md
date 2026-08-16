# DelosDB v1 RawStore page-I/O representation decision

Status: VERIFIED — final v1 page-I/O representation decisions are frozen.

## Decision

```text
KEEP_POSITIONAL_BYTE_ARRAY
REMOVE_HEAP_MEMORY_SEGMENT_FROM_V1_RAWSTORE
REMOVE_NATIVE_PAGE_IO_MIRROR_FROM_V1_RAWSTORE
```

The inherited RawStore `byte[]` page image remains the only page-cache, codec, and physical-I/O
representation in the v1 runtime. `FileChannel` positional operations continue to use zero-copy
`ByteBuffer.wrap(byte[])` views and the explicit `force(boolean metadata)` contract.

The heap `MemorySegment` alias and native page-I/O mirror experiments remain documented and
reproducible experiments, but their production classes, APIs, counters, lifecycle hooks, and focused
runtime tests are removed.

## Why the byte-array path wins

The direct path already has the properties required by RawStore:

```text
one authoritative page image
no page-representation copy
one shared complete FileChannel read loop
one shared complete FileChannel write loop
absolute positional access
closed-on-interrupt recovery
explicit content-only versus metadata force
file and memory storage compatibility
```

`ByteBuffer.wrap(pageData)` is a zero-copy view over the inherited page array. A stable heap
`MemorySegment.ofArray(pageData)` did not remove a copy, replace page ownership, simplify the
storage contract, or reduce the number of channel views created per operation. It added a wrapper
and a segment object to every cached page plus another dispatch layer in both RawStore containers.

The native mirror required a full-page heap-to-native copy before every write and a full-page
native-to-heap copy after every read. It also required arenas, leases, hard-limit accounting,
fallback accounting, shutdown cleanup, additional diagnostics fields, test-only activation, and
storage-factory capability negotiation. It was disabled for normal applications and had no
supported production selector.

## Reproducible diagnostic workload

The focused decision test compares three equivalent 4 KiB positional workloads:

```text
BYTE_ARRAY
HEAP_MEMORY_SEGMENT
NATIVE_MEMORY_MIRROR
```

Each mode writes and reads the same pages through `FileChannel` positional operations. The proof
requires identical SHA-256 final-state evidence and records median read/write timing over repeated
runs. Timing is diagnostic only and has no pass/fail threshold.

A supplementary balanced JDK 21 preview-equivalent run over repeated 32 MiB directions produced:

```text
BYTE_ARRAY           write 34.667 ms   read 25.101 ms
HEAP_MEMORY_SEGMENT  write 36.521 ms   read 24.488 ms
NATIVE_MEMORY_MIRROR write 35.167 ms   read 24.118 ms

heap/array write ratio:   1.054
heap/array read ratio:    0.976
native/array write ratio: 1.014
native/array read ratio:  0.961
```

The small differences change by direction and environment and do not establish a sustained advantage
for either foreign-memory representation. The native path still performs the additional full-page
copies and carries the larger lifecycle surface.

The native mode also allocated substantially more Java-side operation scaffolding in that run.
These numbers are environment-specific and do not define the decision by themselves. They support
the structural result: neither alternative demonstrated a v1 benefit that justified its permanent
runtime complexity.

The authoritative JDK 25 report is generated at:

```text
build/reports/delosdb/stage8.7.2-page-io-representation-decision.txt
```

## Runtime simplification

The final representation decision removes:

```text
MemorySegment positional methods from StorageRandomAccessFile
native-segment capability from StorageFactory
MemorySegment overloads from DirRandomAccessFile
DelosHeapPageBuffer
DelosRawStoreNativeMemory
DelosRawStoreNativeMemoryDirectory
CachedPage segment/native lease ownership
segment/native overloads in FileContainer, RAFContainer, and RAFContainer4
native diagnostics counters and snapshot fields
native test bridge and old heap-segment/native-mirror runtime tests
```

The shared `ByteBuffer` transfer helpers remain. There is still exactly one complete
channel-read loop and one complete channel-write loop.

## Diagnostics schema

Removing the native-mirror fields is an intentional diagnostics schema change. The shared
snapshot advances to schema version 3 and contains only operational production state:

```text
page reads and bytes
page writes and bytes
content-only and metadata force counts
read/write/force failures
closed-channel and reopen evidence
in-flight page I/O
open container handles
shutdown handle leaks
```

The public snapshot no longer advertises an unavailable native-memory feature.

## Non-goals

This decision does not:

```text
change the RawStore page format
change cache replacement or page pinning
change WAL, recovery, locking, or transactions
remove the JDK 25 Foreign Function & Memory API from research tests
change the mapped-region NO_GO_FOR_V1_RAWSTORE decision
remove the retained pre-convergence MVCC oracle
begin Lucene work
```

## Permanent evidence

```text
:delosdb-tests:runDelosSharedRawStorePageIoRepresentationDecisionTest
delosSharedRawStoreHeapMemorySegmentPageBufferStaticAnalysis
delosSharedRawStoreNativeMemoryPageBufferStaticAnalysis
delosSharedRawStoreProductionCloseoutStaticAnalysis
```
