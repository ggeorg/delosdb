# DelosDB v1 JDK 25 shared RawStore heap MemorySegment page buffer

Status: Stage 8.4 implemented; user verification pending.

## Purpose

Stage 8.4 introduces a heap-backed `MemorySegment` view over the existing inherited RawStore page
buffer. The byte array remains the page-cache owner, the page format remains byte-for-byte unchanged,
and the segment is only a JDK 25 alias over that array.

This is a compatibility proof with no page-format change, not a native-memory cutover. It establishes
the shared page-buffer boundary required before DelosDB can evaluate off-heap or mapped ownership in
later stages.

## Frozen ownership model

```text
CachedPage
    owns byte[] pageData
    owns one stable DelosHeapPageBuffer wrapper
        -> same byte[]
        -> MemorySegment.ofArray(pageData)

FileContainer
    accepts the stable page-buffer view
    retains a byte[] compatibility bridge for alternate containers

RAFContainer / RAFContainer4
    use the segment positional overloads
    preserve encryption, checksum, fault, metric, and recovery behavior
```

The byte array remains:

```text
page-cache ownership authority
inherited page-codec input
inherited encryption/decryption input
on-disk page-format authority
```

The segment owns no memory. It has no arena, no close lifecycle, and no independent page identity.

## Shared positional contract

`StorageRandomAccessFile` now provides heap-segment overloads for:

```text
readFullyAt(long position, MemorySegment buffer, long offset, long length)
writeAt(long position, MemorySegment buffer, long offset, long length)
```

The compatibility defaults require a heap-backed segment, obtain the underlying byte-array view, and
delegate to the existing pointer-preserving byte-array methods. This gives virtual memory and alternate
storage wrappers the same contract without creating another backend.

`DirRandomAccessFile` overrides the segment methods and performs complete positional transfers through
`FileChannel` and the segment's `ByteBuffer` view. The inherited `RandomAccessFile` pointer is not
mutated.

## RawStore integration

`CachedPage` creates one stable segment alias whenever it installs or replaces its page array. Reusing
the same page array reuses the same alias. Page-format subtype replacement transfers the array through
the same installation method so the new cached-page instance receives a matching alias.

`RAFContainer` and `RAFContainer4` consume the stable alias for ordinary page reads and writes.
Encrypted writes wrap the existing temporary encryption array for the duration of that write only;
the clear-text cached page keeps its stable alias.

The following remain unchanged:

```text
page number to byte-offset calculation
page size and page format
allocation and padding compatibility
checksums and encryption
WAL ordering and recovery
closed-on-interrupt reopen coordination
fault-injection points and occurrence counting
I/O diagnostics and exact byte accounting
heap and MVCC transaction semantics
```

## Safety boundary

Stage 8.4 accepts heap-backed segments only.

The shared contract rejects:

```text
native segments
mapped segments
read-only destination segments
out-of-range slices
transfers larger than ByteBuffer capacity
```

Production Stage 8.4 code contains no `Arena`, native allocation, mapped region, or explicit segment
lifetime. Native and mapped memory remain out of scope until Stage 8.5 and Stage 8.6 define database
ownership, limits, shutdown, leak detection, recovery, and fallback behavior.

## Executable proof

The focused lane is:

```text
:delosdb-tests:runDelosSharedRawStoreHeapMemorySegmentPageBufferTest
```

It proves:

```text
heap segment and byte array are zero-copy aliases
directory positional segment reads and writes preserve the file pointer
virtual-memory storage uses the same segment contract
partial segment ranges transfer exactly
read-only destinations are rejected
native segment ownership is rejected
EOF failure preserves the file pointer
file data survives close and reopen
heap and RawStore-backed MVCC tables survive checkpoint, shutdown, and reopen
named memory databases preserve inherited heap ownership and shared MVCC behavior
```

Permanent structural evidence is:

```text
delosSharedRawStoreHeapMemorySegmentPageBufferStaticAnalysis
```

## No page-format change

The segment never defines a new binary layout. Existing inherited page codecs continue reading and
writing the same byte array. Stage 8.4 therefore requires no format version, migration, compatibility
reader, or recovery branch.

## Non-goals

Stage 8.4 does not add:

```text
native or off-heap page ownership
mapped files or mapped page regions
Arena lifetime management
database memory budgets
page pinning or replacement-policy changes
new page headers or checksums
VarHandle page-codec replacement
asynchronous I/O or io_uring
a second page cache or storage backend
Lucene work
```

## Next stage

Stage 8.5 may evaluate native/off-heap segment ownership only after this heap-backed proof is verified.
That stage must define database-scoped ownership, hard limits, deterministic release, shutdown leak
proofs, fallback behavior, and recovery interaction before native memory can enter a production path.
