# V1 JDK 25 shared positional I/O

## Decision

DelosDB's inherited directory storage uses one explicit positional random-access contract for page
reads and writes:

```text
StorageRandomAccessFile.readFullyAt(...)
StorageRandomAccessFile.writeAt(...)
StorageRandomAccessFile.force(metadata)
```

The directory implementation maps these methods to JDK `FileChannel` positional I/O and force
operations. Heap and RawStore-backed MVCC continue to share the same RawStore containers, page cache,
log, recovery, and database storage factory.

No second storage namespace, page format, WAL, or MVCC-specific backend is introduced.

## Previous state

The inherited Java-1.4-era storage code had two overlapping models:

```text
RAFContainer
    -> synchronized seek + readFully/write

RAFContainer4
    -> direct FileChannel positional reads/writes
    -> independent readFull/writeFull loops

StorageRandomAccessFile
    -> pointer-based contract only
```

The normal directory database selected `RAFContainer4`, but positional behavior leaked into the
container implementation instead of belonging to the shared storage boundary. The fallback container,
virtual memory implementation, corruption wrappers, and direct storage tests had no common positional
contract. Output-stream synchronization also ignored the caller's metadata requirement.

## Shared contract

`StorageRandomAccessFile` now defines pointer-stable positional methods.

The default implementation:

```text
capture current pointer
seek to absolute position
perform complete read or write
restore the original pointer
preserve the primary failure and suppress restore failure
```

This compatibility path keeps alternate and virtual storage implementations valid. It is not the
production directory fast path. Pointer-based operations and these compatibility defaults require
external serialization on one instance. A storage implementation which overrides the methods with
true positional I/O may execute independent positional ranges concurrently; directory storage does so
under `RAFContainer4`'s existing container recovery coordination.

The contract also defines:

```java
force(boolean metadata)
```

`sync()` remains the compatibility form of a full contents-and-metadata force.

## Directory implementation

`DirRandomAccessFile` owns one `FileChannel` obtained from its inherited `RandomAccessFile` and
implements:

```text
readFullyAt
    -> repeat FileChannel.read(buffer, absolutePosition)
    -> fail with EOFException before returning a short page

writeAt
    -> repeat FileChannel.write(buffer, absolutePosition)
    -> complete the requested byte range

force
    -> FileChannel.force(metadata)
```

Positional operations do not mutate the `RandomAccessFile`/channel current position. Independent
clones continue to own independent channels and file pointers.

The inherited closed-on-interrupt compensation remains in force. If an interrupted transfer closes
the channel after moving bytes, the operation raises `ClosedByInterruptException` so
`RAFContainer4` can execute its existing channel-reopen and retry protocol.

## RawStore page path

Both inherited container implementations now call the shared positional contract:

```text
RAFContainer.readPage
    -> readFullyAt(pageOffset, page)

RAFContainer.writePage
    -> writeAt(pageOffset, page)
    -> compatibility padding only after an alternate implementation rejects growth

RAFContainer4.readPage0/writePage0
    -> same contract
    -> existing concurrent-I/O and channel-recovery coordination retained
```

`RAFContainer4` no longer owns a duplicate full-page read loop. The remaining direct channel helper is
limited to the inherited embryonic-header path whose retry logic is coupled to container reopen.

Page bytes, encryption handling, checksums, allocation pages, cache ownership, and physical format are
unchanged.

## Force semantics

Durability requirements are stated at call sites:

```text
normal positional page force
    -> force(false)

fallback allocation/header path requiring full persistence
    -> force(true)

StorageRandomAccessFile.sync()
    -> compatibility full force

DirStorageFactory.sync(output, metadata)
    -> output.getChannel().force(metadata)
```

The `metadata` flag controls whether non-content metadata is explicitly requested. JDK-required
metadata needed to retrieve forced file contents remains covered by the `FileChannel.force` contract.

## Memory databases

`VirtualRandomAccessFile` uses the pointer-preserving default positional implementation. Its force
operation remains a no-op because the inherited virtual database does not survive shutdown.

This keeps the file and memory storage behavior under one contract without pretending that transient
storage has physical-media durability.

## Verification

The focused test exercises both `DirStorageFactory` and `VFMemoryStorageFactory`:

```text
positional write beyond the current length
file-pointer preservation after write
positional full read
file-pointer preservation after read
explicit content-only and metadata force
EOF failure with pointer restoration
close and reopen verification
```

Permanent gates:

```text
:delosdb-tests:runDelosSharedStoragePositionalIoTest
delosSharedStoragePositionalIoStaticAnalysis
```

The normal heap/MVCC DRDA, crash-recovery, reopen, memory-database, and S0 lanes remain the
integration authority.

## Deliberately deferred

This slice does not add:

```text
mapped MemorySegment regions
native or off-heap page ownership
new buffer replacement policy
asynchronous I/O
io_uring
backend selection
new page format
deterministic I/O fault injection
per-operation event recording
```

Stage 8.2 adds bounded shared I/O diagnostics and container-handle accounting at this boundary.
Stage 8.3 adds deterministic fault points, Stage 8.4 adds the heap-segment proof, Stage 8.5 adds a
bounded native mirror, and Stage 8.6 rejects mapped RawStore I/O for v1. All remain inside the one
inherited RawStore authority.


## Stage 8.7.2 final representation decision

Stages 8.4 and 8.5 verified heap-segment and bounded native-mirror experiments. Stage 8.7.2
removes both from the v1 runtime after benchmark and code-size review. The production contract is
again the direct byte-array positional path:

```text
StorageRandomAccessFile byte[] positional methods
    -> ByteBuffer.wrap(byte[])
    -> one complete FileChannel read loop
    -> one complete FileChannel write loop
```

This keeps the inherited page array authoritative, avoids a second page wrapper and native copies,
and preserves the explicit force, interrupt-recovery, file, and memory-storage behavior proved by
Stage 8.1. The experiments remain in test-only decision evidence.
