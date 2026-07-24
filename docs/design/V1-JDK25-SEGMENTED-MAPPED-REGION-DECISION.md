# DelosDB v1 JDK 25 segmented mapped-region experiment and decision

Status: Stage 8.6 verified.

## Decision

```text
NO_GO_FOR_V1_RAWSTORE
```

DelosDB v1 does not promote mapped `MemorySegment` regions into the production RawStore page path.
The Stage 8.1 positional `FileChannel` contract, Stage 8.4 heap segment aliases, and optional Stage 8.5
bounded native physical-I/O mirrors remain the production design.

The experiment is retained as executable evidence. It is not a dormant backend selector and is not
packaged into a runtime artifact.

## Experiment boundary

The focused proof uses JDK 25:

```text
FileChannel.map(MapMode.READ_WRITE, offset, size, Arena)
MemorySegment.isMapped()
MemorySegment.force()
Arena.close()
```

It evaluates fixed-size regions over one ordinary file. Pages are aligned so one page never crosses a
region boundary. Regions are mapped lazily and closed together through one explicit arena.

The proof covers:

```text
absolute page writes across multiple regions
unchanged FileChannel position
exact page-image verification through positional reads
fixed mapping bounds after file growth
new mapping required for the grown range
mapped-segment invalidation after arena close
file replacement and deletion after close
mapped force persistence
representative positional and mapped page workloads
identical SHA-256 final file state
```

Timing is recorded for diagnosis only. No timing ratio is a pass/fail condition because filesystem,
operating-system cache state, storage hardware, and runtime warmup are environment dependent.

## Why mapped RawStore page I/O is rejected for v1

### Durability contract mismatch

RawStore explicitly distinguishes:

```text
force(false)    content durability
force(true)     content plus metadata durability
```

Mapped `MemorySegment.force()` exposes one no-argument persistence operation. Promoting it would either
lose the explicit metadata choice or require a second durability path beside the mapping.

### Fault and diagnostics boundary mismatch

Stages 8.2 and 8.3 observe exact completed positional reads, writes, force operations, and before/after
fault points. Mapped access changes memory directly and operating-system write-back can occur outside
the Java page-write call. Preserving the existing evidence model would require a separate mapped dirty
tracking and force protocol rather than reusing the shared positional seam.

### File-growth and region lifecycle cost

A mapping has fixed spatial bounds. Extending a container does not extend an existing region. A
production implementation would require:

```text
region lookup
map-on-demand
file-growth coordination
region eviction
pinning against concurrent close
channel reopen integration
address-space accounting
platform-sensitive replace/truncate coordination
```

That is a new resource manager, not a small `StorageRandomAccessFile` optimization.

### No page-authority simplification

The inherited `byte[]` remains the page-cache, codec, checksum, encryption, and recovery authority.
A mapped RawStore path would still copy between the page image and the mapped region. Removing that copy
would require replacing inherited page ownership and is outside v1.

### Duplicate residency

The inherited page cache remains required. Mapped regions add operating-system virtual-memory residency
and address-space pressure beside it. Stage 8.5 already provides a bounded, explicitly accounted native
experiment without changing the file write-back model.

## Production invariants

```text
no FileChannel.map call in production RawStore code
no mapped segment in CachedPage or DelosHeapPageBuffer
no mapped-region registry or eviction policy
no mapped diagnostics schema
no mapped fault schedule
no SQL, JDBC, property, or service selector
no page-format, WAL, recovery, locking, or transaction change
```

## Future reconsideration

Mapped regions may be reconsidered only for an immutable or rebuildable read-mostly subsystem with its
own lifecycle and durability contract. A future Lucene directory may evaluate read-only mapping after
Stage 9 establishes search ownership, but this Stage 8.6 decision does not authorize that work.

Any RawStore reconsideration requires new cross-platform recovery, file replacement, address-space,
resource-limit, and benchmark evidence. It must not silently relax this v1 decision.

## Executable evidence

```text
:delosdb-tests:runDelosSharedRawStoreMappedRegionExperimentTest
delosSharedRawStoreMappedRegionDecisionStaticAnalysis
```

The focused task writes:

```text
build/reports/delosdb/stage8.6-mapped-region-experiment.txt
```

The report contains the fixed workload parameters, region count, diagnostic timings, and one common
SHA-256 final-state digest.
