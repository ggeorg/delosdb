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

The first write performed by a RawStore-backed MVCC transaction takes one `MvccTransactionId` from a
small database-runtime reservation block. When that in-memory block is empty, DelosDB refills it in one
RawStore nested top transaction:

```text
open the database metadata container for update
read next unreserved MvccTransactionId
advance it by the bounded reservation-block size
commit the nested top transaction
force the nested RawStore commit record
return identities from the now-durable block
```

The default block contains 64 identities and is tunable only for focused validation through
`delosdb.mvcc.rawStoreIdentityReservationBlockSize`. Every identity is therefore durably reserved before
it can be stored in an uncommitted version row, while the force cost is amortized across the block.
Reservation remains independent of the user transaction outcome. A rollback, shutdown, or process
failure can leave unused numbers in a reserved block, but reboot cannot reuse them:

```text
numeric gaps are allowed
identity reuse is not
```

No RawStore `XactId` is persisted as the MVCC identity.

## Durable commit sequences

Immediately before the inherited user RawStore commit, the transaction participant holds the existing
commit-publication boundary and takes one `MvccCommitSequence` from an independently durable reservation
block. An empty block is refilled with the same forced nested-top RawStore mechanism before any sequence
from that block can be returned. It then performs normal logged updates inside the user transaction:

```text
stamp created version rows with the reserved sequence
stage the database committed high-water to the same sequence
return to RAMTransaction
write the one inherited RawStore commit record
publish the in-memory high-water
```

The allocator block advance is independently durable, so a failed user commit or unused tail of a
reserved block may leave sequence gaps. The committed high-water is not advanced unless the user
transaction's RawStore commit succeeds.
After reboot, the runtime reconstructs the published high-water from the committed database metadata
row.

The table-local allocator row remains format-compatible for the existing isolated table format, but
its historical committed-high-water field is no longer an authority for visibility or publication.

## Crash boundaries

The executable proof distinguishes allocator durability from transaction outcome:

| Failure boundary | Durable counter state | Durable row state |
| --- | --- | --- |
| user rollback after transaction-ID reservation | transaction-ID gap retained | row absent |
| halt after ID/sequence reservation and version stamping, before user RawStore commit | both gaps retained; committed high-water unchanged | row absent |
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
