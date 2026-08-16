# DelosDB storage architecture

## Purpose

DelosDB provides two storage modes behind one Derby-compatible SQL, catalog, transaction, JDBC, and
DRDA engine. Both storage modes use one Derby RawStore as the physical persistence and recovery
authority.

```text
SQL and catalog
    -> optimizer and generated execution
    -> TransactionController
    -> Derby access-method contract
       -> inherited heap
       -> delos_mvcc
    -> one Derby RawStore
```

Storage selection is persisted in table and conglomerate metadata. Production execution does not
depend on development-only routing properties, hidden verification routes, or a second storage runtime.

## Derby heap

The inherited heap remains the default and the durable compatibility anchor:

```sql
CREATE TABLE t (id INTEGER PRIMARY KEY, value VARCHAR(100));
```

It preserves Derby heap/raw-store formats, row locations, logging, locking, recovery, catalog
behavior, and compatibility with supported existing databases.

## `delos_mvcc`

The MVCC access method is selected explicitly:

```sql
CREATE TABLE t (id INTEGER PRIMARY KEY, value VARCHAR(100)) USING delos_mvcc;
```

The current integration path is:

```text
Derby statement and result-set execution
    -> MVCC conglomerate and scan/controller integration
    -> delosdb-derby-store-api
    -> MvccRawStore* access-method implementation
    -> Derby RawStore containers, pages, logging, recovery, and backup
```

There is no external `delos_mvcc` directory, page-volume store, second WAL, independent checkpoint
stack, sidecar recovery authority, or dual-write persistence path.

### Logical MVCC authority

The MVCC access method owns logical concurrency/version semantics, including:

```text
transaction identity
commit sequence
statement and transaction snapshots
row and version identity
visibility rules
write-conflict detection
version chains
retained-reader horizons
maintenance and vacuum decisions
```

Logical MVCC records and indexes are persisted through RawStore-backed conglomerates. Their physical
durability is therefore governed by the same RawStore transaction/recovery authority as the rest of
the database.

### Physical persistence and recovery authority

RawStore owns:

```text
containers and pages
logging and undo
physical transaction durability
checkpoint and restart recovery
backup and restore
database boot/shutdown lifecycle
file and named-memory database storage
```

The detailed transaction-decision and failure contract is described in
[`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md).

### Physical page-I/O contract

RawStore keeps one authoritative `byte[]` page image for cache, codec, and physical I/O. Directory
storage performs explicit positional `FileChannel` reads and writes through the inherited random-access
contract; DelosDB does not maintain a second native or mapped page image. Shared I/O diagnostics observe
that same path, and deterministic fault injection is restricted to verification code rather than exposed
as an application storage mode.

Named `memory:` databases use the same RawStore ownership and access-method semantics through Derby's
virtual storage factory. They do not create a separate MVCC memory engine.

### Commit and transaction outcome

Supported heap-only, MVCC-only, and accepted mixed heap/MVCC transaction shapes participate in the
Derby transaction boundary. MVCC publication and visibility state must never become a second durable
commit authority. Failure handling derives from the RawStore transaction decision and the durable
MVCC records stored inside that authority.

See [`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md).

### Maintenance

One database-owned service coordinates MVCC maintenance and vacuum with bounded work and reader-horizon
checks. Maintenance operates on RawStore-backed MVCC structures; it does not own an independent page
cache or checkpoint system.

See [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md).

### Backup and restore

Derby RawStore is the only backup and restore authority. Current MVCC rows, indexes, transaction
metadata, and recovery-relevant records are ordinary RawStore state and require no separate copy
protocol. Retired pre-convergence MVCC persistence artifacts are not part of the current storage
architecture.

## Isolation

Current runtime behavior is:

```text
READ COMMITTED and weaker
    statement snapshot

REPEATABLE READ
    transaction snapshot

SERIALIZABLE
    rejected with SQLState 0A000 before an MVCC scan or write opens
```

Heap `SERIALIZABLE` remains Derby-compatible. The MVCC rejection is a truthful current implementation
boundary; true MVCC `SERIALIZABLE` remains part of the intended v1 contract and must not be documented
as permanently post-v1.

## Consistency and diagnostics

Heap, B-tree, and MVCC implementations expose provider-neutral consistency reports. Diagnostics are
read-only and must not become repair, cleanup, or hidden execution-routing mechanisms.

The shared lifecycle view is represented by `DelosStorageLifecycleConsistencySnapshot` and
`DelosStorageLifecycleConsistencyReport`. `DelosStorageDiagnosticsRegistry.lifecycleConsistencySnapshot(...)`
and `lifecycleConsistencyReport(...)` aggregate existing checkpoint, purge/vacuum, analyze/statistics,
backup-marker, and consistency signals without creating another storage authority. The report accepts
`DelosStorageConsistencyTarget`, so heap and MVCC targets can be inspected through one read-only shape.

`StorageLifecycleConsistencyReportTest` verifies a mixed heap/MVCC database through lifecycle changes,
shutdown, and reopen while preserving SQL results.

## Protected boundaries

```text
Derby heap/raw-store durable compatibility
Derby catalog semantics
Derby optimizer authority
Derby remainder-predicate evaluation
JDBC behavior
DRDA wire compatibility
```

## DelosDB-owned boundaries

```text
delos_mvcc logical transaction/version semantics
MVCC RawStore access-method structures and indexes
MVCC visibility, conflicts, retention, maintenance, and vacuum
provider-neutral storage diagnostics and JFR events
```

RawStore remains the sole physical persistence, WAL/logging, checkpoint, recovery, backup, and restore
authority.

## Verification

```bash
./gradlew verifyDelosRuntimeStorageProviders
./gradlew :delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests
./gradlew :delosdb-storage-mvcc:check
./gradlew s0CloseoutVerification
```

## Table rebuild DDL and storage-provider truth

Derby's inherited offline table-rebuild paths create replacement base conglomerates. DelosDB does
not permit those operations to change a table's persisted storage provider implicitly. Until
provider-preserving rebuild semantics satisfy the required failure-atomic contract, MVCC tables
reject unsupported rebuild operations before catalog or table mutation with SQLState `0A000`.

Provider-preserving in-place maintenance remains available where supported.
