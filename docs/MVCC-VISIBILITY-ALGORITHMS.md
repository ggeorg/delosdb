# MVCC Visibility Algorithms

## Purpose

This document describes the current visibility rules for RawStore-backed `delos_mvcc` tables.
Physical persistence and recovery remain Derby RawStore responsibilities; the MVCC access method owns
logical version identity, snapshots, visibility, and retained-reader safety.

## Current authority

The main implementation boundaries are:

```text
MvccRawStoreRuntime
MvccRawStoreTransactionContext
MvccRawStoreTable
MvccRawStoreRowDirectory
MvccRawStoreVersionReader
MvccRawStoreVersionRows
MvccRawStoreVacuum
```

The access method does not use the retired external MVCC runtime, page-volume store, transaction
outcome log, visibility-map sidecar, or purge daemon.

## Snapshot frontier

A snapshot is a published database-wide commit-sequence frontier.

`MvccRawStoreTransactionContext` captures the snapshot lazily on first use. The runtime obtains the
current published sequence and opens a snapshot lease at that sequence.

```text
transaction needs snapshot
    -> MvccRawStoreRuntime.openSnapshotLease()
    -> capture published commit-sequence frontier
    -> retain that sequence while the snapshot is live
```

For isolation levels that retain one transaction snapshot, the same sequence is reused for the
transaction. Statement-snapshot isolation obtains the appropriate statement boundary through the
transaction lifecycle integration.

## Version visibility

`MvccRawStoreVersionReader` applies the core rule directly to a decoded version record.

An uncommitted version is visible only to its creating transaction:

```text
beginSequence == UNCOMMITTED
    -> creatorTransactionId == currentTransactionId
```

A committed version is visible when:

```text
beginSequence <= snapshotSequence < endSequence
```

This gives read-your-own-writes without exposing another transaction's uncommitted version and keeps
committed visibility bounded by the captured snapshot frontier.

## Version-chain traversal

The row directory identifies the current logical head. `MvccRawStoreVersionReader` follows the durable
version chain until it finds the first version visible to the current transaction and snapshot.

The traversal validates row/version identity and detects cycles. A missing required version is an
integrity failure rather than an instruction to silently return an older or unrelated row.

Record handles and cached physical locations are lookup hints. Logical row/version identity remains
authoritative.

## Current-row acceleration

`MvccRawStoreRuntime` maintains bounded current-row anchors and immutable current-version read images
for resident read acceleration.

These structures are not visibility authorities:

```text
cache hit
    -> validate table, row, version, begin sequence, flags, and physical hint
    -> use the validated current version

cache miss or validation failure
    -> fall back to RawStore row-directory and version lookup
```

Publication order ensures that a newly published commit sequence cannot expose an older cached head as
though it were current.

## Snapshot leases and vacuum horizon

Live snapshots protect history through snapshot leases. The runtime uses a bounded slot registry for
the normal path and a locked retained-snapshot registry as a correctness-preserving fallback when the
bounded slots are exhausted.

The vacuum horizon is the minimum of:

```text
current published commit sequence
all live bounded snapshot leases
all live fallback retained snapshots
```

`MvccRawStoreVacuum` may reclaim obsolete history only below the safe horizon and subject to the
access method's row/version integrity rules.

## Commit publication interaction

A transaction reserves a commit sequence before RawStore commit when it has MVCC changes. Pending
versions are stamped before commit, but the sequence is not published to new snapshots until the
RawStore transaction has committed.

The relevant order is:

```text
stage MVCC changes in the RawStore transaction
    -> reserve commit sequence
    -> stamp pending versions
    -> RawStore commit
    -> publish current-row anchors
    -> retire writer transaction identity
    -> publish commit sequence to new snapshots
```

This ordering prevents a snapshot from admitting a committed sequence while still treating the
corresponding writer as active or observing an older cached row head.

RawStore remains the durable transaction-decision authority. Commit-sequence publication is logical
MVCC visibility state, not a second persistence mechanism.

## Maintenance boundary

Normal reads enter a table read boundary. Vacuum enters the corresponding maintenance boundary
exclusively so that physical history cleanup cannot race an active table read through the same
structure.

Maintenance operates on RawStore-backed rows and indexes and does not own an independent page cache,
checkpoint stream, or recovery log.

## Current isolation boundary

For `delos_mvcc`:

```text
READ COMMITTED and weaker
    statement snapshot

REPEATABLE READ
    transaction snapshot

SERIALIZABLE
    currently rejected with SQLState 0A000 before MVCC scan/write execution
```

The current `SERIALIZABLE` rejection is an implementation boundary, not a statement that true MVCC
serializability is permanently outside the intended v1 contract.

## Invariants

```text
foreign uncommitted versions are never visible
read-your-own-writes is preserved
committed visibility is bounded by a captured commit-sequence frontier
publication cannot precede RawStore commit
retained snapshots prevent unsafe history reclamation
cached current-row state is advisory and validated
RawStore remains the sole physical persistence and recovery authority
```
