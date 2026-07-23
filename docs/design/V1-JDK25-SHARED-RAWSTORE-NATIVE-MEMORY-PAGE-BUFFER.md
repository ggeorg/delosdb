# DelosDB v1 JDK 25 bounded native RawStore page-I/O mirrors

Status: Stage 8.5 implemented; user verification pending.

## Decision

Stage 8.5 permits a database-scoped native `MemorySegment` mirror for physical RawStore page I/O.
It does not replace the inherited page image.

```text
CachedPage
    byte[] pageData                         authoritative cache and codec image
    DelosHeapPageBuffer
        MemorySegment.ofArray(pageData)     stable heap alias
        optional native lease               bounded physical-I/O mirror only
```

The inherited byte array remains the only page-format, checksum, encryption-input, cache, and
recovery authority. Before a native write, the heap image is copied into the native mirror. After a
native read completes, the native image is copied into the heap image before decoding continues.

## Enablement boundary

The production default is disabled:

```text
DelosRawStoreNativeMemory.DEFAULT_LIMIT_BYTES = 0
```

There is no SQL procedure, JDBC attribute, system property, service provider, or public API that can
turn native page memory on. The Stage 8.5 executable proof uses a package-private, bounded,
consume-on-boot planning directory for one exact canonical database identity.

A requested budget is honored only when the active `StorageFactory` explicitly reports:

```text
supportsNativeRandomAccessMemorySegments() == true
```

Directory storage opts in. Virtual-memory and alternate storage factories retain the default false
capability and therefore remain heap-only.

## Ownership and limits

Each `BaseDataFileFactory` owns exactly one `DelosRawStoreNativeMemory` allocator. The allocator:

```text
binds once to one database lifecycle
uses Arena.ofShared() per native page-buffer lease
enforces one hard byte limit before allocation
returns null and records heap fallback when the limit is exhausted
returns null and records heap fallback after native allocation OOME
tracks leases by identity only
stops accepting allocations during shutdown
closes every remaining lease before diagnostics publication
```

Allocation and reservation are serialized so concurrent page creation cannot transiently exceed the
hard limit. The allocator keeps no page contents, page identifiers, transaction identifiers, or
unbounded history.

## Cached-page lifecycle

`CachedPage` creates the wrapper with the database-owned allocator when a page image is installed.
It closes the wrapper when the cache identity is cleared or the page size changes. Page-format
subtype replacement transfers the existing wrapper rather than allocating a second native mirror.

This preserves the invariant:

```text
one installed byte[] page image
    -> one DelosHeapPageBuffer
    -> zero or one native lease
```

## Physical I/O

`DirRandomAccessFile` accepts heap and native segments through the same absolute `FileChannel`
read/write loops. The inherited `RandomAccessFile` pointer remains unchanged.

`RAFContainer` and `RAFContainer4` select the wrapper's read or write segment. They preserve the
Stage 8.1 complete-transfer contract, Stage 8.2 exact byte accounting, Stage 8.3 fault boundaries,
force semantics, sparse-growth compatibility, and closed-on-interrupt reopen protocol.

Native page operations are a subset of total page operations. The diagnostics schema therefore
requires:

```text
native read operations <= all page-read operations
native read bytes      <= all page-read bytes
native write operations <= all page-write operations
native write bytes      <= all page-write bytes
```

## Diagnostics schema version 2

`DelosRawStoreIoSnapshot` schema version 2 adds:

```text
nativeMemoryEnabled
nativeMemoryLimitBytes
currentNativeMemoryBytes
peakNativeMemoryBytes
nativeBufferAllocations
nativeBufferReleases
nativeBufferFallbacks
nativeBufferReleaseFailures
nativePageReadOperations
nativePageReadBytes
nativePageWriteOperations
nativePageWriteBytes
currentNativeBuffers
peakNativeBuffers
unclosedNativeBuffersAtShutdown
unreleasedNativeMemoryBytesAtShutdown
```

A clean shutdown has zero current native bytes and buffers, matching allocation/release counts, and
zero terminal leak evidence. If shutdown has to force-close leaked leases, the terminal immutable
snapshot records their pre-cleanup count and bytes.

## Executable proof

```text
:delosdb-tests:runDelosSharedRawStoreNativeMemoryPageBufferTest
```

The focused proof covers:

```text
native directory positional read/write and pointer preservation
hard-limit enforcement and deterministic heap fallback
lease reuse, release, clean shutdown, and intentional leak evidence
one file database shared by heap and RawStore-backed MVCC
exact database isolation between armed and default databases
checkpoint, shutdown, reopen, and canonical state preservation
memory-database rejection of native ownership
normal reopen returning to the disabled production default
```

The package-private control bridge is compiled only as the existing engine test patch. It is not
packaged into runtime artifacts.

## Permanent gate

```text
delosSharedRawStoreNativeMemoryPageBufferStaticAnalysis
```

The gate protects database ownership, the zero default, storage capability opt-in, bounded planning
and terminal evidence, hard-limit fallback, explicit lease closure, inherited byte-array authority,
heap/native copy direction, diagnostics schema version 2, focused test wiring, and the absence of
mapped regions or application-facing native-memory controls.

## Explicit exclusions

Stage 8.5 does not add:

```text
a native page cache
native page-format authority
mapped files or mapped regions
Arena.global()
an unbounded native-memory pool
application configuration for native memory
memory-database native allocation
asynchronous I/O or io_uring
page-format, WAL, recovery, locking, or transaction changes
Lucene work
```

Stage 8.6 may evaluate segmented mapped regions only after Stage 8.5 is verified and only through a
separate production decision backed by recovery, lifecycle, address-space, and benchmark evidence.
