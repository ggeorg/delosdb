# V1 RawStore database-wide MVCC identities

## Status

```text
IMPLEMENTED FOR THE OPT-IN RAWSTORE MVCC FORMAT
```

This milestone replaces the isolated format's provisional runtime transaction token and table-local
publication counter with durable database-wide MVCC identity metadata owned by Derby RawStore.

It does not widen the supported SQL mutation surface. The opt-in remains:

```text
delosdb.mvcc.rawStoreVerticalSlice.enabled=true
```

## Ownership

Each database that uses the RawStore-backed MVCC format owns one database-wide RawStore metadata container.
Its container identifier is recorded through the normal Derby database-property mechanism:

```text
delosdb.mvcc.rawStore.databaseMetadataContainerId
```

The metadata row stores:

```text
format magic and version
next MvccTransactionId
next MvccCommitSequence
committed MvccCommitSequence high-water
```

The container is created lazily through a Derby nested update transaction before the first
RawStore-backed MVCC table lifecycle or access operation needs it. It uses the database's existing
RawStore, WAL, recovery, backup, and StorageFactory implementation. There is no path, sidecar,
separate WAL, or static process-wide allocator.

## Durable transaction IDs

The first write performed by a RawStore-backed MVCC transaction reserves one `MvccTransactionId` in a
RawStore nested top transaction:

```text
open the database metadata container for update
read next MvccTransactionId
write next value
commit the nested top transaction
force the nested RawStore commit record
return the reserved identity
```

The reserved ID is then stored in each uncommitted version row created by that user transaction.
Reservation is independent of the user transaction outcome. A rollback or process failure can consume
an unused number, but reboot cannot reuse it:

```text
numeric gaps are allowed
identity reuse is not
```

No RawStore `XactId` is persisted as the MVCC identity.

## Durable commit sequences

Immediately before the inherited user RawStore commit, the transaction participant holds the existing
commit-publication boundary and consumes one `MvccCommitSequence` from a durably reserved block. The
runtime reserves 64 consecutive sequences at a time through the same nested-top RawStore mechanism,
then performs normal logged updates inside each user transaction:

```text
stamp created version rows with the reserved sequence
stage the database committed high-water to the same sequence
return to RAMTransaction
write the one inherited RawStore commit record
publish the in-memory high-water
```

Each block reservation is independently durable before any value from the block is returned. Unused
values are allowed gaps and are never reused: after reboot the runtime reserves a fresh block starting
at the durable next-unreserved sequence. A failed user commit may likewise leave a consumed sequence
gap. The committed high-water is not advanced unless the user transaction's RawStore commit succeeds.
After reboot, the runtime reconstructs the published high-water from the committed database metadata
row.

The table-local allocator row remains format-compatible for the existing isolated table format, but
its historical committed-high-water field is no longer an authority for visibility or publication.

## Crash boundaries

The executable proof distinguishes allocator durability from transaction outcome:

| Failure boundary | Durable counter state | Durable row state |
| --- | --- | --- |
| user rollback after transaction-ID reservation | transaction-ID gap retained | row absent |
| halt after ID/sequence-block reservation and version stamping, before user RawStore commit | reserved block and transaction-ID gap retained; committed high-water unchanged | row absent |
| halt after user RawStore commit, before in-memory publication | counters and committed high-water retained | row present |

This preserves one database transaction outcome while guaranteeing that durable MVCC identities are
never reused.

## Database and memory scope

The metadata container belongs to one booted database. Two simultaneous databases begin with
independent counters and cannot observe or advance one another's identities.

The same implementation runs under:

```text
jdbc:derby:memory:
```

The metadata row, nested allocations, version stamps, and committed high-water use the inherited
memory RawStore. No disk directory or alternate memory allocator is introduced.

## Current limits

This milestone does not yet add:

```text
UPDATE or DELETE
historical version-chain mutation
multiple RawStore-backed MVCC tables in one transaction
mixed heap/MVCC write transactions
ordered indexes or uniqueness
vacuum or purge
XA or nested update support
final lock granularity
```

Those capabilities must consume the database-wide identities established here rather than recreate
another allocator.

## Permanent evidence

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreDatabaseIdentityTest
```

It proves:

```text
rollback consumes but never reuses MvccTransactionId
reboot preserves transaction-ID and commit-sequence monotonicity
separate tables consume one database-wide identity sequence
process halt before the user RawStore commit preserves reserved gaps
committed version rows contain the reserved durable identities
databases have independent counters
jdbc:derby:memory: uses the same RawStore metadata path
```

Permanent architecture task:

```text
delosMvccRawStoreDatabaseIdentityStaticAnalysis
```

The gate rejects runtime-only transaction-ID allocation, table-local publication authority,
independent filesystem/durability ownership, missing nested-top reservation, missing RawStore force,
or removal of the executable crash and memory proofs.
