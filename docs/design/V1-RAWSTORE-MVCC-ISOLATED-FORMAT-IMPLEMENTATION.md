# V1 RawStore-backed MVCC isolated-format implementation

## Status

```text
IMPLEMENTED BEHIND AN EXPLICIT OPT-IN
```

The implementation is authorized by the accepted RawStore vertical-slice, stable logical identity,
commit-ordering, and access-transaction lifecycle proofs.

It is intentionally not the default `delos_mvcc` format yet. Enable it at database boot with:

```text
delosdb.mvcc.rawStoreVerticalSlice.enabled=true
```

The property defaults to `false`. A persisted RawStore-backed table is always identified by its
control-row magic. Reopening that table without the opt-in fails with an explicit property error; it
never falls through to the earlier external format.

## Ownership boundary

A newly created opted-in `delos_mvcc` table stores its authoritative state only through the caller's
Derby RawStore transaction:

```text
metadata/directory container
version container
ordered-index container
RawStore slotted pages
RawStore logged insert and field-update operations
RawStore undo
RawStore commit record
RawStore recovery
RawStore file or memory StorageFactory implementation
```

The implementation does not open a filesystem path, create an MVCC page volume, append an MVCC WAL,
write a transaction-outcome sidecar, run a separate recovery pass, or coordinate a second commit. The
transitional transaction registry distinguishes RawStore-owned participation from retained external
writers, so this format proceeds through the inherited commit path without staging a Phase 8 database
decision.

Existing tables created by the earlier independent format are not migrated or dual-written. In a
directory database, opted-in boot first detects the RawStore control-row magic. A nonmatching
conglomerate continues through the earlier implementation so it remains available as a differential
oracle while convergence proceeds. Before a new RawStore table is created, the retained compatibility
runtime reports the highest persisted earlier-format conglomerate identity. The factory reserves the
next MVCC-factory-owned identity above that boundary, preventing an earlier logical conglomerate ID
from aliasing a new RawStore container after reboot. This filesystem inspection belongs only to the
retained compatibility path; the new RawStore table implementation still receives only RawStore
transactions and container identities.

## Physical format

Each newly created opted-in table owns three ordinary RawStore containers.

The primary metadata/directory container uses:

```text
first page slot 0: immutable control row
first page slot 1: logical row/version allocator row (legacy high-water field reserved)
remaining records: MvccRowId -> head MvccVersionId directory entries
```

The version container uses:

```text
first page slot 0: format marker
remaining records: version rows and row payload
```

The ordered-index container uses:

```text
first page slot 0: ordered-index control row
remaining records: physically sorted, version-aware typed key entries
```

Each non-tombstone version contributes one entry per table column. Entries are ordered by column,
SQL typed key, stable row identity, and version identity. They retain creator, begin, and end sequence
fields so transaction-local and historical snapshot visibility match the base version. Index results
are candidate `MvccRowId` values only; the authoritative base version chain is always reread and the
complete SQL qualifiers are reapplied. See `V1-RAWSTORE-MVCC-ORDERED-INDEXES.md`.

A version row contains format-versioned logical identity and visibility fields followed by the normal
RawStore-encoded payload:

```text
MvccRowId
MvccVersionId
creating MvccTransactionId
begin MvccCommitSequence
end MvccCommitSequence
previous MvccVersionId
flags
payload columns
```

`MvccRowId` and `MvccVersionId` are durable identities. Directory rows may carry the current head's
RawStore page/record locator and version rows may carry the predecessor's locator as optional trailing
fields. These locators are validated hints only: row shape, row kind, `MvccRowId`, and `MvccVersionId`
must match before decoding or commit stamping uses them. A missing, stale, reused, or mismatched hint
falls back to authoritative logical-ID lookup. Older shorter rows remain valid without migration. See
`V1-RAWSTORE-MVCC-LOOKUP-HINTS.md`.

The opt-in format now uses one database-wide RawStore metadata container for durable
`MvccTransactionId`, durable `MvccCommitSequence`, and the committed publication high-water. The first
write in a user transaction reserves its transaction identity through a nested top RawStore
transaction. Precommit reserves the commit sequence the same way, stamps the version rows, and stages
the committed high-water inside the user RawStore transaction. The nested allocator commit record is
forced before the identity is returned: numeric gaps are allowed but identity reuse is not.

The table allocator row remains format-compatible for logical row and version allocation. Its earlier
committed-high-water field is reserved and is no longer the visibility or publication authority. See
`V1-RAWSTORE-MVCC-DATABASE-WIDE-IDENTITIES.md`.

One access transaction may now mutate multiple RawStore-backed MVCC tables. The transaction-local
participant retains one database-wide `MvccTransactionId`, one pending-version list whose entries own
their table descriptor, and one transaction-wide snapshot. Precommit stamps all surviving table
versions with one `MvccCommitSequence` before the single inherited RawStore commit. See
`V1-RAWSTORE-MVCC-MULTI-TABLE-TRANSACTIONS.md`.

## CREATE and INSERT

CREATE uses `Transaction.addContainer()` three times and writes the metadata control, allocator,
version marker, and ordered-index control rows through the same RawStore transaction as the catalog
operation. Rollback therefore removes all three containers through normal RawStore undo.

INSERT performs its physical work during statement execution:

```text
1. ensure the table has a transactional ordered-index container
2. update the allocator row for a new MvccRowId and MvccVersionId
3. insert an uncommitted version row
4. insert the stable-row directory entry
5. rewrite the version-aware index entries in physical SQL typed order
6. retain only logical pending-version state and validated RecordHandle hints
```

Inserted directory and version records request `Page.INSERT_UNDO_WITH_PURGE`; row payloads may use
RawStore overflow support. There is no custom `Loggable` and no deferred external write at commit.

## Locking and access order

The isolated format uses inherited serializable container locking as a conservative correctness
boundary. It is deliberately stronger than the final intended MVCC lock granularity.

Operations that need all table structures acquire the metadata container before the version container
and the ordered-index container last.
Update locks remain owned by the RawStore transaction until transaction completion. This prevents
allocator races, prevents an abort from overwriting another transaction's allocator progress, and
gives create/drop/read/write a single conservative container order. A scan truthfully reports that it
is table-locked while this proof policy is active.

Page latches protect individual physical operations. Visibility remains an MVCC decision based on
logical transaction and commit-sequence fields; container locks are not treated as version identity or
visibility metadata.

## Point read and scan

The direct conglomerate fetch path resolves a stable `MvccRowLocation` by:

```text
scan directory records for MvccRowId
read the authoritative head MvccVersionId
try the optional validated RawStore page/record hint
fall back to logical MvccVersionId lookup on any mismatch
follow previousVersionId with the same hint/fallback rule
apply current-transaction or committed-snapshot visibility
return the decoded payload
```

For safe single-column equality and range qualifiers, the scan controller traverses the physically
sorted ordered-index container, de-duplicates stable row candidates, rereads each candidate through
the same authoritative version-chain lookup, and reapplies all qualifiers. Unsupported predicate
shapes use the linear directory scan. Version-chain navigation avoids a full version-container scan
when a validated hint is current. This is still not the final optimizer cost model.

## Commit ordering

One database-scoped commit-publication lock coordinates local commit finalization and snapshot
capture.

For a transaction with inserted versions:

```text
1. acquire the publication lock
2. reserve the next database-wide MvccCommitSequence through a forced nested-top RawStore commit
3. stamp all pending base-version begin sequences and ordered-index entry begin sequences
4. stamp predecessor base-version and ordered-index entry end sequences
5. update the database-wide RawStore-owned committed high-water in the user transaction
6. let RAMTransaction commit the inherited RawStore transaction
7. publish the in-memory high-water only after RawStore reports success
8. release the publication lock
```

Snapshot capture reads only the already published in-memory high-water while holding the publication
lock and is cached for the duration of the access transaction. It does not open a RawStore container
while acquiring that lock. On conglomerate reopen, the
factory reconstructs the in-memory high-water from the committed database metadata row before the table is
returned. This preserves the required ordering without introducing a publication-lock/container-lock
inversion.

Rollback and rollback-to-savepoint remain RawStore-first. After RawStore completes the physical undo,
the access-method lifecycle participant reconciles its pending handles against the surviving RawStore
version rows and then trims savepoint markers. This also covers a savepoint created before the MVCC
participant was first registered in that SQL transaction.

## Recovery and memory databases

The permanent crash proof covers both decisive sides of the inherited commit record:

| Halt point | Recovered result |
| --- | --- |
| after version stamps and high-water update, before RawStore commit | inserted row is absent |
| after RawStore commit, before in-memory publication | inserted row is present and visible |

No MVCC-specific recovery code runs after restart. RawStore undo/redo reconstructs the rows and the
factory reconstructs publication state from committed RawStore metadata.

The exact same table implementation runs for `jdbc:derby:memory:`. The focused proof creates a memory
database, inserts and commits, reads from a second connection, rolls back another insert, drops the
table, shuts down through the inherited lifecycle, and verifies that no filesystem database appeared.

## Compatibility and fail-closed limits

The implementation currently supports only the isolated capability:

```text
CREATE
INSERT
stable-row point fetch
visible table scan
COMMIT and commitNoSync ordering
ROLLBACK
RawStore savepoint trimming
DROP
clean reopen
process-halt recovery
file and memory databases
multiple RawStore-backed MVCC tables in one transaction
one transaction-wide snapshot across those tables
UPDATE by replacement-version append
DELETE by tombstone append
historical version-chain traversal
validated optional physical lookup hints with logical fallback
pre-hint shorter-row compatibility
version-aware physically sorted ordered-index entries
safe equality/range candidate lookup with authoritative base-row recheck
pre-index shorter-control compatibility and transactional lazy rebuild
persisted primary-key and unique-constraint metadata
RawStore-native uniqueness enforcement over the authoritative version chain
composite keys and SQL duplicate-null semantics
mixed inherited heap and RawStore-backed MVCC transactions
```

The following capabilities remain outside this transitional implementation contract. XA participation
and nested updates fail closed. UPDATE and DELETE are now implemented through RawStore version-chain
mutation, but the remaining items are not yet claimed as supported:

```text
SQL secondary-index DDL lifecycle
deferrable unique constraints
retroactive boot-time discovery of pre-existing catalog uniqueness
vacuum, purge, compression, and relocation
XA participation
nested update transactions
final lock granularity and final completed table binary format
default routing or migration of existing tables
```

A transaction cannot combine RawStore-owned MVCC mutation or DDL with mutation or DDL from the retained
external format; the second storage authority is rejected before it can mutate.
XA and nested update callbacks are rejected before MVCC mutation. Read-only nested transactions remain
available to inherited query preparation.

## Permanent executable evidence

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreVerticalSliceTest
:delosdb-tests:runDelosMvccRawStoreMultiTableTransactionTest
:delosdb-tests:runDelosMvccRawStoreUpdateDeleteTest
:delosdb-tests:runDelosMvccRawStoreMixedHeapTransactionTest
:delosdb-tests:runDelosMvccRawStoreLookupHintTest
:delosdb-tests:runDelosMvccRawStoreOrderedIndexTest
:delosdb-tests:runDelosMvccRawStoreUniqueConstraintTest
:delosdb-tests:runDelosMvccRawStoreUniqueLifecycleTest
```

It covers:

```text
same supported workload as the retained earlier-format oracle
opted-in reopen creates a new RawStore table before opening the retained table, without conglomerate-ID aliasing
existing earlier-format tables remain readable and writable while new tables use RawStore
CREATE rollback
statement-time own-write visibility
commit and second-connection visibility
INSERT rollback followed by a later committed insert
savepoint rollback created before MVCC participant registration
multiple RawStore-backed tables sharing one transaction outcome
RawStore-owned commit bypass of the retained external decision coordinator
old/new MVCC storage-authority mixing rejected in both registration orders
clean shutdown and reopen
absence of earlier-format sidecar files
file-backed and memory-backed RawStore operation
crash before the RawStore commit record
crash after the RawStore commit record before in-memory publication
rollback/reboot/crash non-reuse of database-wide MvccTransactionId and MvccCommitSequence
database-scoped and memory-backed identity metadata
replacement-version and tombstone chains
historical snapshot traversal across committed UPDATE and DELETE
stale-snapshot writer rejection
UPDATE/DELETE rollback, savepoint, crash, reopen, multi-table, and memory behavior
mixed heap/MVCC commit, rollback, savepoint, crash, reopen, and memory behavior
heap-only commits do not consume MVCC identities
persisted directory-head and predecessor hints match physical RawStore records
stale or reused physical hints fall back for current and historical snapshots
pre-hint shorter rows remain readable and upgrade on later mutation
lookup hints survive reopen, both RawStore crash boundaries, and memory operation
physically sorted SQL typed ordered-index entries across multiple RawStore pages
equality/range candidate scans with base-chain and full-qualifier revalidation
transaction-local and historical ordered-index visibility
pre-index compatibility and transactional lazy rebuild
ordered-index reopen, both RawStore crash boundaries, and memory operation
persisted primary-key and unique-constraint metadata
RawStore-native duplicate rejection even through direct base-conglomerate access
single-column and composite uniqueness with duplicate-null semantics
UPDATE, DELETE/reuse, savepoint, reopen, concurrent-writer, crash, and memory uniqueness proofs
ALTER TABLE and CREATE/DROP UNIQUE INDEX native metadata lifecycle
logical-definition reference counts, DDL rollback, recovery, and memory lifecycle proofs
```

Permanent architecture task:

```text
delosMvccRawStoreVerticalSliceStaticAnalysis
delosMvccRawStoreMultiTableTransactionStaticAnalysis
delosMvccRawStoreUpdateDeleteStaticAnalysis
delosMvccRawStoreMixedHeapTransactionStaticAnalysis
delosMvccRawStoreLookupHintStaticAnalysis
delosMvccRawStoreOrderedIndexStaticAnalysis
delosMvccRawStoreUniqueConstraintStaticAnalysis
delosMvccRawStoreUniqueLifecycleStaticAnalysis
```

The gate fixes the ownership boundary, ordinary RawStore operation set, conservative locking order,
transaction-lifecycle ordering, opt-in compatibility route, retained/new-format identity separation,
recovery tests, memory proof, differential oracle proof, and absence of filesystem/external-durability
dependencies from the new RawStore production path. Database-wide identity allocation and publication are additionally protected by `delosMvccRawStoreDatabaseIdentityStaticAnalysis`. Optional physical lookup fields and mandatory logical fallback are protected by `delosMvccRawStoreLookupHintStaticAnalysis`. The third RawStore container, physical typed ordering, version-aware visibility, candidate revalidation, compatibility rebuild, and recovery/memory proofs are protected by `delosMvccRawStoreOrderedIndexStaticAnalysis`. Persisted primary-key and
unique-constraint metadata, latest-committed conflict checks, authoritative base-chain revalidation,
duplicate-null semantics, and direct-access/concurrency/recovery proofs are protected by
`delosMvccRawStoreUniqueConstraintStaticAnalysis`.


Native unique metadata now follows later SQL DDL as well as inline CREATE TABLE. ADD validates existing authoritative MVCC rows before publishing one logical definition; DROP removes one matching definition in the same RawStore transaction as Derby catalog and backing-index changes. Shared logical definitions act as reference counts, and tables with no native metadata remain compatible with inherited DROP lifecycle. Permanent evidence is `:delosdb-tests:runDelosMvccRawStoreUniqueLifecycleTest` and `delosMvccRawStoreUniqueLifecycleStaticAnalysis`.
