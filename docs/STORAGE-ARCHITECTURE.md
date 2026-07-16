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
    -> delosdb-storage-api
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

Each database has its own backup coordinator. Durable MVCC mutations take the shared side of the
boundary; sidecar backup copy takes the exclusive side. Backing up one database does not freeze an
unrelated database in the same JVM.

See [`MVCC-BACKUP-COORDINATION.md`](MVCC-BACKUP-COORDINATION.md).

## Isolation

Current pre-1.0 behavior is:

```text
READ COMMITTED and weaker
    statement snapshot

REPEATABLE READ
    transaction snapshot

SERIALIZABLE
    transaction snapshot compatibility mapping
    no predicate locking, SSI, or write-skew prevention
```

The current behavior is covered by an executable write-skew proof. The v1.0 product contract
requires early rejection of MVCC `SERIALIZABLE` until true serializability is available.

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
MVCC database backup coordination
storage diagnostics and JFR events
```

## Verification

```bash
./gradlew verifyDelosRuntimeStorageProviders
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-mvcc:check
./gradlew s0CloseoutVerification
```
