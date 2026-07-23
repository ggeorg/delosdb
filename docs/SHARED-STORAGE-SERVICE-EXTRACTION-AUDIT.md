# DelosDB Shared Storage Service Extraction Audit

This audit belongs to Phase O of the engine-depth roadmap. It intentionally does
not extract a new shared service yet. Its purpose is to decide which storage
concerns have enough proof on both the Derby-compatible heap path and the MVCC
path to justify a later extraction without weakening compatibility.

## Extraction rule

A shared storage service may be extracted only when all of the following are
true:

```text
heap proof exists
MVCC proof exists
format/log compatibility boundaries are named
provider semantics are close enough to share behavior, not only terminology
S0 can reject one-provider extraction claims
```

The audit result for this slice is:

```text
No service is extracted in Phase O.
Checksum/torn-write validation is the strongest candidate for the next narrow design slice.
Allocation, cache, recovery, and purge abstractions remain deferred until their semantics converge further.
```

## Candidate matrix

### 1. Checksum and torn-write validation

Decision: `READY_FOR_NARROW_DESIGN`

Heap proof:

```text
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/ChecksumOperation.java
```

The heap/raw-log side already has a durable checksum log operation with a CRC32
algorithm and log-scan validation semantics. This is a raw-log format boundary,
so the checksum payload format itself must not be moved behind a shared DelosDB
codec.

MVCC proof:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/format/MvccPageRecordCodec.java
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccSidecarCodec.java
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/format/MvccDurableLineRecords.java
```

The MVCC side has page-record body checksums, checksum-trailered sidecar files,
and torn-final-line tolerance for append-only durable text logs.

Safe extraction shape:

```text
read-only integrity evidence model
provider-neutral corruption/torn-write diagnostic vocabulary
shared test helper for expected integrity findings
```

Unsafe extraction shape:

```text
shared physical checksum codec
heap LOGOP_CHECKSUM format changes
MVCC page-record header changes
raw-log or sidecar rewrite behavior changes hidden as refactoring
```

### 2. Allocation and free-space management

Decision: `DEFER_SEMANTIC_MISMATCH`

Heap proof:

```text
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/AllocPage.java
```

MVCC proof:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccFreeSpaceMapStore.java
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccReusablePageIndexStore.java
```

Both providers manage allocation/free-space, but they do not yet expose the same
semantics. Heap allocation pages are part of Derby's durable page/container
format. MVCC free-space and reusable-page state live in provider-owned sidecars.
A later shared abstraction may expose diagnostics, but not allocation behavior.

### 3. Page cache and flush discipline

Decision: `DEFER_PROVIDER_SEMANTICS`

Heap proof:

```text
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/StoredPage.java
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/LogToFile.java
```

MVCC proof:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccPageCache.java
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccBufferFlushCoordinator.java
```

Both providers have page caching and flush rules, but the heap side is tied to
Derby's raw-store cache and log-force protocol. MVCC Phase M introduced a
provider-owned coordinator for WAL-before-page-flush and grouped force batching.
The shared shape should remain diagnostic until a compatibility-safe heap proof
exists.

### 4. Recovery and checkpoint coordination

Decision: `DEFER_FORMAT_BOUNDARY`

Heap proof:

```text
delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/LogToFile.java
```

MVCC proof:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/durable/MvccRecoveryReplayEngine.java
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/store/MvccSubsystemRecoveryRecordStore.java
```

Both providers expose recovery/checkpoint concerns, but heap recovery is a Derby
raw-log format boundary and MVCC recovery currently uses provider-owned sidecar
metadata plus replay validation. A shared recovery service would be premature.
A shared recovery diagnostics vocabulary is safe.

### 5. Storage statistics and cost evidence

Decision: `ALREADY_SHARED_DIAGNOSTIC_BOUNDARY`

Heap proof:

```text
delosdb-storage-api/src/main/java/org/apache/derby/iapi/store/types/DelosStorageStatistics.java
delosdb-storage-api/src/main/java/org/apache/derby/iapi/store/types/DelosHeapStorageStatistics.java
```

MVCC proof:

```text
delosdb-storage-api/src/main/java/org/apache/derby/iapi/store/types/DelosMvccStorageStatistics.java
delosdb-storage-mvcc/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccStoreCostController.java
```

This concern already has a shared diagnostic/reporting boundary. Phase K added
an explicit opt-in path from MVCC statistics into Derby's inherited cost-controller
seam. No new extraction is needed in Phase O.

### 6. Purge and maintenance scheduling

Decision: `DEFER_MVCC_ONLY_PROOF`

Heap proof:

```text
delosdb-storage-api/src/main/java/org/apache/derby/iapi/store/types/DelosStorageMaintenance.java
```

MVCC proof:

```text
delosdb-storage-mvcc/src/main/java/io/github/ggeorg/delosdb/storage/mvcc/bridge/MvccPurgeDaemon.java
```

The shared maintenance vocabulary exists, and MVCC now has deterministic purge
scheduling. The heap side has compatibility-sensitive compress/purge behavior,
not the same scheduler semantics. Do not extract a common purge daemon.

## Next safe extraction candidate

The next real implementation slice may design a **read-only storage integrity
evidence model**. That model should collect provider-neutral facts such as:

```text
provider id
storage target
checksum algorithm name, if provider exposes it
validated region or record class
corruption/torn-write finding category
source diagnostic path
```

It must not own physical heap log-record encoding or MVCC page/sidecar encoding.
