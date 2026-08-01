# DelosDB storage architecture

## Purpose

DelosDB provides two storage modes behind one Derby-compatible SQL, catalog, transaction, JDBC, and
DRDA engine.

```text
SQL and catalog
    -> optimizer and generated execution
    -> TransactionController
    -> Derby access-method contract
       -> heap/raw store
       -> delos_mvcc
```

Storage selection is persisted in table and conglomerate metadata. Production execution does not
depend on phase-named system properties or hidden proof routes.

## Derby heap

The heap remains the default and the durable compatibility anchor:

```sql
CREATE TABLE t (id INTEGER PRIMARY KEY, value VARCHAR(100));
```

It preserves Derby page formats, row locations, raw logging, locking, recovery, catalog behavior,
and compatibility with existing databases.

DelosDB extends the heap path only through explicit, tested improvements such as:

- real `SYSCS_UTIL.SYSCS_CHECK_TABLE` heap validation;
- object-deserialization filter integration;
- safer file-copy durability;
- corrected inherited defects;
- provider-neutral diagnostics.

## `delos_mvcc`

The MVCC engine is explicit:

```sql
CREATE TABLE t (id INTEGER PRIMARY KEY, value VARCHAR(100)) USING delos_mvcc;
```

The integration path is:

```text
Derby statement and result-set execution
    -> MVCC conglomerate and scan/controller bridge
    -> delosdb-derby-store-api
    -> MvccInheritedTable
    -> PageBackedMvccTable and page-volume stores
```

### Transaction and visibility state

The MVCC engine owns:

- monotonic transaction identities;
- statement and transaction read views;
- creating and deleting transaction metadata;
- commit sequences and transaction outcomes;
- retained-reader horizons;
- write-conflict detection;
- savepoint rollback.

### Durable state

The page-backed engine uses:

```text
page-volume WAL
prepared page-mutation batches
transaction-status log
local outcome mirror
row directory
ordered-index pages
free-space and visibility metadata
checkpoint state
purge queue
backup manifest
```

The authoritative ordering and failure behavior are defined in
[`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md).

### Commit publication

Transactions prepare immutable payloads before entering the bounded commit coordinator. One group
publishes a shared forced transaction-status batch and one final ordered-index rebuild. Page WAL,
local outcome, page materialization, recovery records, and checkpoint publication remain
transaction-owned.

See [`MVCC-GROUP-COMMIT.md`](MVCC-GROUP-COMMIT.md).

### Maintenance

One database-owned service schedules table maintenance with bounded workers, periodic scans,
commit-triggered wakeups, visibility-debt priority, reader-horizon checks, and strict shutdown.

See [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md).

### Backup

Derby RawStore is the only backup and restore authority. Current MVCC rows, indexes, decisions, and
recovery records are ordinary RawStore state and require no separate copy protocol. Any retired
`delos_mvcc` directory, manifest, or in-progress marker rejects database boot, backup, and restore
with SQLState `0A000` rather than being transported or silently discarded.

## Isolation

Current behavior is:

```text
READ COMMITTED and weaker
    statement snapshot

REPEATABLE READ
    transaction snapshot

SERIALIZABLE
    rejected with SQLState 0A000 before an MVCC scan or write opens
```

Heap SERIALIZABLE remains Derby-compatible. MVCC SERIALIZABLE stays unavailable until a real
predicate-locking or SSI architecture can satisfy the JDBC contract.

## Consistency and diagnostics

Heap, B-tree, and MVCC implementations expose provider-neutral consistency reports. Diagnostics are
read-only and must not become repair, cleanup, or hidden execution-routing mechanisms.

## Protected boundaries

```text
Derby heap page and raw-log formats
Derby catalog semantics
Derby optimizer authority
Derby remainder-predicate evaluation
JDBC behavior
DRDA wire compatibility
```

## DelosDB-owned boundaries

```text
delos_mvcc durable formats
MVCC visibility and transaction state
MVCC page WAL and recovery
MVCC ordered-index authority
MVCC maintenance and vacuum
RawStore backup/restore compatibility
storage diagnostics and JFR events
```

## Verification

```bash
./gradlew verifyDelosRuntimeStorageProviders
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-mvcc:check
./gradlew s0CloseoutVerification
```

## Table rebuild DDL and storage-provider truth

Derby's inherited offline table-rebuild paths create replacement base conglomerates. The original
implementation hard-coded the replacement as `heap`, which could silently change a table declared
with `USING delos_mvcc` into a Derby heap after `ALTER TABLE ... COMPRESS`, `TRUNCATE TABLE`, or a
column-drop rebuild.

DelosDB does not permit an operation to change a table's persisted storage provider implicitly.
Until provider-preserving rebuilds participate in the database-level failure-atomic transaction
protocol, MVCC tables reject these operations before catalog or table mutation with SQLState
`0A000`:

```text
ALTER TABLE ... COMPRESS
TRUNCATE TABLE
ALTER TABLE ... DROP COLUMN
```

Provider-preserving in-place maintenance remains available where supported. In particular,
`SYSCS_UTIL.SYSCS_INPLACE_COMPRESS_TABLE` with the MVCC-supported purge path invokes MVCC vacuum
without rebuilding the base conglomerate or changing storage identity.

## Bounded database-decision retention

Mixed heap/MVCC commits use the Derby raw-store log as the transaction authority. After raw-store
commit, DelosDB forces the same committed outcome into the database MVCC transaction-status journal
before retiring the temporary raw-store decision marker.

```text
raw-store decision marker
    -> force database MVCC status mirror
    -> retire marker
    -> publish or recover participant-local outcomes
```

The marker directory therefore contains only decisions which have not yet been mirrored. On reopen,
committed markers are mirrored first and then removed. The database status journal is compacted
atomically after scanning all table mutation and local-outcome logs. Compaction retains:

- every database transaction still referenced by a complete prepared mutation without a local
  outcome;
- the exact highest transaction-id status;
- the exact highest committed sequence status.

The last two records preserve allocation watermarks without retaining lifetime transaction history.
A failed atomic compaction leaves the previous complete journal in place and does not change the
transaction outcome.

The database-level MVCC decision journal is removed when the final MVCC table drops and no retained
recovery artifact remains. Opening an empty MVCC runtime does not create an empty journal.
