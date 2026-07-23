# DelosDB Page Codec Algorithm Audit

This is an audit artifact, not a behavior change.

## Scope

This document inventories DelosDB-owned page, record, key, map, overflow, sidecar, WAL, and recovery-record codecs. It does not authorize a storage-format replacement.

Guardrails:

* No Java runtime behavior change.
* No storage format change.
* No page format replacement.
* No heap raw-store format dependency.
* No object serialization authority.
* No Derby heap page or raw log rewrite.
* No candidate-index authority restoration.
* No MapDB/HerdDB/Calcite runtime dependency.

## Current codec families

### Shared binary format helper

`MvccBinaryFormat` owns the shared big-endian binary-format helper used by small MVCC durable formats. It validates magic, format version, header size, exact length, and minimum length boundaries. It is a DelosDB-owned algorithm and a JDK25 modernization candidate only for future owned-code experiments.

### Page record codec

`MvccPageRecordCodec` wraps version records with an MVCC page-record header, body length, and checksum-region discipline. Legacy raw `MvccVersionRecordCodec` payloads still decode so reopen compatibility is preserved.

### Version record codec

`MvccVersionRecordCodec` owns the durable version-chain payload shape. It records tuple header state, transaction ids, previous-version ids, tombstone state, and payload bytes. This is an MVCC authority codec and must not be replaced without a storage-format plan.

### Row payload codecs

`MvccRowPayloadCodec` owns low-level binary row payloads. `MvccInheritedRowCodec` owns the Derby-storable typed row bridge and the authoritative SQL-facing durable encoding. The retired text-envelope proof codec has been removed.

### Index tuple codec

`MvccIndexTupleCodec` owns durable ordered-index tuple encoding and typed logical-key decoding. It is the current anchor for typed Derby value boundary, duplicate-key behavior, and future binary-searchable key area work.

### Overflow codecs

`MvccOverflowPayloadCodec` owns descriptor and chunk encoding. `MvccOverflowPayloadReferenceCodec` owns small inline references to overflow payloads. `MvccAttributeOverflowRowPayloadCodec` owns attribute-level overflow row references.

### Sidecar and map codecs

`MvccSidecarCodec` owns checksum-trailer sidecar payload IO. `MvccFreeSpaceMapStore` and `MvccVisibilityMapStore` own small durable sidecar map payloads. These should remain independent from inherited Derby heap page and raw log formats.

### WAL and recovery-record codecs

`PageVolumeMvccWriteAheadLog` owns the MVCC page-volume WAL text record envelope and batching order. `MvccSubsystemRecoveryRecordStore` owns subsystem-specific recovery-record lines. These are recovery-ordering algorithms, not generic serialization utilities.

## Reference models

MapDB Serializer and GroupSerializer are useful reference models for compact typed serializers, packed row-id lists, delta encodings, and binary-searchable key arrays. They are reference models only.

PostgreSQL and InnoDB page-layout discipline are reference models for page headers, record flags, checksum regions, recovery ordering, and page-local mutation boundaries.

H2 is a reference model for compact Java storage and inspector-friendly persistent structures.

JDK 25 MemorySegment/VarHandle remains a candidate for owned DelosDB page-codec work. Stage 8.4
provides the compatibility plan for one narrower use: a heap-backed segment alias over the existing
inherited RawStore byte array. It does not alter the heap/raw-store format or authorize VarHandle
codec replacement, native ownership, or mapped pages.

## Known modernization candidates

* typed Derby value boundary audit for `MvccIndexTupleCodec` and `MvccInheritedRowCodec`;
* binary-searchable key area for ordered MVCC index pages;
* packed row-id lists for duplicate keys and range scans;
* checksum-region discipline for all binary page/sidecar codecs;
* compact overflow descriptor/chunk layout review;
* free-space and visibility-map encoding density review;
* recovery record idempotence and ordering proof;
* JFR/JMH instrumentation for codec encode/decode cost.

## Current position

Default behavior remains unchanged. This audit does not replace any codec, does not change on-disk format, and does not create a shared heap/MVCC page codec. Future implementation work must start with proof paths and explicit compatibility gates.

Stage 8.5 does not change the codec. A native segment may mirror the complete inherited page image only
for a physical directory read or write. Heap-to-native copying occurs after encoding and before write;
native-to-heap copying occurs after read and before decoding. No native buffer becomes a page-format
authority.
