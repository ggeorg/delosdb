# V1 RawStore database-wide MVCC identities

## Status

```text
IMPLEMENTED
```

This design uses durable database-wide MVCC identity metadata owned by Derby RawStore rather than a
runtime-only transaction token or table-local publication counter. RawStore-backed MVCC is the production authority; identity allocation is database-owned and does not
depend on a runtime routing switch.

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

Immediately before the inherited user RawStore commit, the transaction participant consumes one
`MvccCommitSequence` from a durably reserved database-wide block. The runtime reserves 64 consecutive
sequences at a time through a nested-top RawStore transaction. The reservation also advances the
durable recovery publication ceiling to the end of that block.

The expensive user finalization work is then concurrent:

```text
stamp surviving version rows with the reserved sequence
publish transaction-private ordered-index replacements
stage allocator high-waters
perform the one inherited user RawStore commit
mark the sequence terminal
advance the contiguous in-memory publication frontier
return commit only after that frontier reaches this sequence
```

Unused values are safe gaps and are never reused. After reboot the runtime starts from the durable
next-unreserved sequence. RawStore recovery removes uncommitted stamped changes, while successfully
committed stamped versions survive. The durable recovery ceiling can therefore be used to reconstruct
the initial publication frontier after reopen without introducing another transaction-status
authority.

The metadata field historically called committed high-water now represents this recovery publication
ceiling. Live snapshot visibility is controlled by the in-memory contiguous published frontier, not by
the durable ceiling while the database is running.

## Crash boundaries

The executable proof distinguishes allocator durability from transaction outcome:

| Failure boundary | Durable counter state | Durable row state |
| --- | --- | --- |
| user rollback before commit-sequence reservation | transaction-ID gap may remain; no sequence block required | row absent |
| halt after sequence-block reservation and stamping, before user RawStore commit | next-unreserved sequence and recovery ceiling remain advanced | row absent after RawStore recovery |
| halt after user RawStore commit, before live publication | reservation/ceiling retained | row present after RawStore recovery |

This preserves one RawStore transaction outcome while guaranteeing that durable MVCC identities are
never reused and that live snapshots never pass an unfinished earlier commit sequence.

## Database and memory scope

The metadata container belongs to one booted database. Two simultaneous databases begin with
independent counters and cannot observe or advance one another's identities.

The same implementation runs under:

```text
jdbc:derby:memory:
```

The metadata row, nested allocations, version stamps, and committed high-water use the inherited
memory RawStore. No disk directory or alternate memory allocator is introduced.

## Current boundaries

Database-wide identities are consumed by UPDATE/DELETE, multi-table and mixed heap/MVCC transactions,
ordered indexes, uniqueness, vacuum, and logical/physical locking. Those mechanisms share this allocator
rather than recreating transaction or commit-sequence authority. MVCC XA writes and nested update
transactions remain fail-closed boundaries.

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
