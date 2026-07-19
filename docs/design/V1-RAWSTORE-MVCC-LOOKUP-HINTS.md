# V1 RawStore MVCC validated lookup hints

## Status

```text
IMPLEMENTED BEHIND THE RAWSTORE MVCC OPT-IN
```

This milestone optimizes RawStore-backed MVCC version-chain lookup without changing the authority
model. The logical row and version identities remain authoritative. A RawStore page number and record
identifier are only a validated hint; every missing, stale, reused, or mismatched locator takes the
logical fallback path.

## Persisted hint shape

The existing directory and version rows now permit optional trailing fields:

```text
stable-row directory entry
    MvccRowId
    head MvccVersionId
    head version page hint
    head version record-ID hint

version entry
    ... existing logical identity and visibility fields ...
    previous MvccVersionId
    predecessor page hint
    predecessor record-ID hint
```

The physical locator uses RawStore's stable record identifier, not a mutable slot number. The format
version is unchanged because these fields are non-authoritative optional trailing fields. Rows written
before this milestone retain the shorter valid shape and continue through logical lookup.

## Lookup protocol

A directory head or predecessor link is resolved in this order:

```text
1. read the authoritative MvccVersionId
2. attempt the optional page/record hint
3. validate row shape, row kind, MvccRowId, and MvccVersionId
4. decode only after validation succeeds
5. otherwise scan by the authoritative logical identity
```

A locator can therefore become stale because of rollback, purge, record reuse, later relocation, or
manual fault injection without changing query correctness. The implementation never converts a
physical RawStore handle into permanent MVCC identity.

The same rule applies during commit stamping. The transaction-local handle and persisted predecessor
hint are fast paths only; begin- and end-sequence updates verify logical identity and fall back when a
hint no longer names the intended record.

## RawStore ownership

Hint fields are inserted and updated as normal RawStore row data. Their lifecycle is inherited:

```text
RawStore logging
RawStore undo
savepoint rollback
one RawStore commit record
RawStore crash recovery
file and memory StorageFactory implementations
```

No path, sidecar index, second page cache, custom WAL, or independent recovery mechanism is introduced.

## Compatibility

The executable proof rewrites rows to the pre-hint shorter layout, closes and reopens the database,
and verifies that reads still succeed. A later mutation writes the current hinted shape. There is no
mandatory rewrite or boot-time migration.

The proof also deliberately corrupts both the directory-head hint and a predecessor hint while leaving
logical IDs intact. Current and historical snapshots still return the correct rows through logical
fallback. A later committed mutation restores valid hints for the newly written chain head.

## Recovery and memory evidence

Both inherited RawStore crash boundaries are covered:

| Halt point | Recovered chain |
| --- | --- |
| after MVCC stamping, before RawStore commit | old committed chain and hints |
| after RawStore commit, before in-memory publication | new committed chain and hints |

The same implementation and validation rules run under `jdbc:derby:memory:`. No filesystem-specific
lookup code exists in the MVCC path.

## Deliberate limits

This milestone does not make physical locators authoritative and does not introduce a secondary index.
The stable-row directory itself is still found by a conservative linear RawStore scan. A stale hint is
not repaired merely by reading it; a later mutation naturally writes a current hint. Ordered indexes,
unique constraints, vacuum/purge, relocation policy, and the final cost model remain separate slices.

## Permanent evidence

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreLookupHintTest
```

Permanent architecture gate:

```text
delosMvccRawStoreLookupHintStaticAnalysis
```

The test proves persisted topology, reopen, stale-hint logical fallback for current and historical
snapshots, old shorter-row compatibility, crash recovery, and `jdbc:derby:memory:` behavior.
