# V1 JDK 25 shared RawStore I/O diagnostics

## Decision

DelosDB exposes one bounded, database-owned observation model for the inherited RawStore page-I/O
path used by heap and RawStore-backed MVCC.

```text
BaseDataFileFactory
    -> owns one DelosRawStoreIoMetrics instance
    -> binds it to the canonical file: or memory: database identity

RAFContainer / RAFContainer4
    -> publish page transfer, force, recovery, and container-handle evidence

heap diagnostics / MVCC diagnostics
    -> expose the same immutable DelosRawStoreIoSnapshot
```

This is observation of the existing physical authority. It is not a storage backend, an event log, a
second cache, or a second database-lifecycle registry. No second storage namespace, page authority,
WAL, or durability path is introduced.

## Why this follows positional I/O

The shared positional-I/O contract established explicit force semantics. Before page memory
or mapping ownership changes, the common path must provide evidence about transfer volume, durability
requests, recovery, concurrency, and resource lifetime. Otherwise later performance or failure work
would have no stable baseline and could hide leaks or retry regressions.

## Ownership and identity

`BaseDataFileFactory` owns the mutable counters because it already owns the database storage factory
and every inherited RawStore container. The object is passed to the MVCC runtime through the existing
`DataFactory` seam; MVCC does not create a second counter set.

The observation identity is explicit:

```text
file database
    -> file:<canonical normalized database path>

memory database
    -> memory:<canonical VFMemoryStorageFactory database identity>
```

`DelosRawStoreIoDiagnosticsDirectory` is a weak, non-owning lookup used only to connect public heap
diagnostics to the database-owned object. MVCC diagnostics retain their existing database-owned
runtime reference. Registration does not own or prolong a database runtime.

## Snapshot contract

`DelosRawStoreIoSnapshot` is immutable and schema-versioned. It reports:

```text
successful page read operations and bytes
successful page write operations and bytes
content-only force operations
content-plus-metadata force operations
page read, page write, and force failures
closed-channel detections
channel-recovery attempts
successful and failed channel reopens
current and peak in-flight page I/O
current and peak open container random-access handles
unclosed container handles observed at storage-factory shutdown
runtime-active and memory-database state
```

Counters are monotonic except for current gauges. There is no unbounded event history, per-page key,
SQL text, row value, or file-content capture.

A successful transfer is counted only after the complete page operation returns. A failed transfer is
counted separately and never contributes successful bytes. `force(false)` and `force(true)` remain
separate because they express different durability requirements.

## Concurrent observation

All mutable fields use bounded atomic counters. A snapshot is weakly consistent, which is appropriate
for diagnostics, but always structurally valid. Current gauges are read together with peaks and the
reported peak is clamped to at least the observed current value. Diagnostic reads therefore remain
safe while positional page operations are entering or leaving the path.

The metrics object never locks a RawStore container and never participates in transaction ordering,
page latching, channel-recovery coordination, or cache replacement.

## Resource lifetime

A long-lived container random-access handle is counted when installed in `RAFContainer` and released
when its reference is cleared, including close, failed open cleanup, and `RAFContainer4` reopen after a
closed-on-interrupt channel. Replacing a closed handle decrements the old count before incrementing the
new one, so a reopen does not manufacture a leak.

At storage-factory shutdown the metrics object records the number of container handles still open and
marks the runtime inactive. The weak active registration is then replaced by an immutable terminal
snapshot. A fixed 64-entry access-ordered bound preserves recent shutdown evidence without retaining a
database runtime or creating unbounded lifecycle history. Rebooting the same identity replaces its
terminal observation with the new active weak registration.

Short-lived backup, stub, and embryonic-header files are outside the ordinary page-handle gauge. Their
existing local `try/finally` ownership remains unchanged.

## File and memory behavior

Directory databases exercise both container variants and report positional channel transfers,
content-only forces, metadata forces, and closed-channel recovery evidence.

Named memory databases publish the same snapshot shape. Their positional operations are counted, and
metadata-force requests remain visible even though the virtual storage implementation correctly has
no persistent-media durability.

A freshly created memory database may satisfy every logical SQL read from the page cache.
Zero physical page reads is therefore valid until RawStore actually invokes the positional read path.
The executable proof must not manufacture cache eviction merely to obtain a positive read counter.
Cache replacement policy is outside this diagnostics contract.

## Public diagnostics

The existing diagnostics registry exposes explicit helpers for both providers and both database
kinds:

```text
heapDatabaseRawStoreIoSnapshot(Path)
heapMemoryDatabaseRawStoreIoSnapshot(String)
mvccDatabaseRawStoreIoSnapshot(Path)
mvccMemoryDatabaseRawStoreIoSnapshot(String)
```

For a booted database containing both heap and MVCC tables, the heap and MVCC calls return observations
from the same `DelosRawStoreIoMetrics` instance.

## Verification

The focused executable lane proves:

```text
exact successful operation and byte counts
separate read, write, and force failure counts
separate content-only and metadata force counts
closed-channel and reopen evidence
current and peak in-flight accounting
current and peak container-handle accounting
explicit shutdown leak capture through the public diagnostics registry
bounded terminal snapshot retention
concurrent snapshot safety and exact final totals
one shared heap/MVCC snapshot for a file database
one shared heap/MVCC snapshot for a named memory database
two simultaneously booted file databases keep isolated counters
diagnostic reads do not mutate the counters
```

Permanent evidence:

```text
:delosdb-tests:runDelosSharedRawStoreIoDiagnosticsTest
delosSharedRawStoreIoDiagnosticsStaticAnalysis
```

Normal RawStore crash, reopen, memory, DRDA, module, and closeout gates remain authoritative for integration.

## Deliberately deferred

The diagnostics contract does not add:

```text
deterministic I/O fault injection
per-operation event recording
histograms or latency sampling
JFR events
mapped MemorySegment regions
native or off-heap page ownership
asynchronous I/O or io_uring
buffer replacement changes
new page format, WAL, cache, or storage namespace
Lucene work
```

Deterministic database-scoped fault injection and replay build on this observable shared boundary.


## Schema version 3

Stages 8.4 and 8.5 temporarily extended the page path and diagnostics for heap-segment and native
mirror experiments. The final representation decision removes those production representations and advances
`DelosRawStoreIoSnapshot` to schema version 3.

Schema version 3 contains only production operational evidence:

```text
page read/write operations and bytes
content-only and metadata force operations
read/write/force failures
closed-channel and reopen evidence
in-flight page I/O
open container handles
terminal handle-leak evidence
```

Native-memory limits, leases, fallback counts, native page counters, and native shutdown fields are
removed because no native page-I/O feature remains in the v1 runtime.
