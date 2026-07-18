# DelosDB

DelosDB is a Java 25 relational database management system derived from Apache Derby 10.17.1.0.
It preserves Derby-compatible SQL, JDBC, catalog, heap, database-format, and DRDA behavior while
modernizing selected internals through explicit, tested boundaries.

DelosDB is currently a pre-1.0 project. The inherited Derby heap remains the default compatibility
engine. The DelosDB MVCC engine is an explicit table-level alternative.

## Strategic commitments

DelosDB v1.0 is defined by two complementary commitments.

### Derby continuity and modernization

- Existing Derby applications and databases remain viable on Java 25.
- The Derby heap and raw store remain the durable compatibility foundation.
- Inherited defects are corrected with source evidence and regression tests.
- Modern storage capabilities are introduced without requiring existing applications to migrate.

### A comprehensible, research-capable RDBMS

- The complete path from SQL text to result delivery and durable state is documented.
- Optimizer, execution, transaction, storage, recovery, and protocol decisions are observable.
- Students work with a real database engine rather than a simplified teaching implementation.
- Researchers can run reproducible experiments without creating permanent duplicate engines.

The supported product must also remain understandable and releasable by a highly skilled database
engineer with broad database-systems expertise.

## Architecture

DelosDB uses one SQL and execution engine for embedded JDBC and network clients:

```text
Embedded JDBC or DRDA
    -> parse and bind
    -> optimize
    -> generate executable activations
    -> execute result-set operators
    -> coordinate transactions
    -> access Derby heap or delos_mvcc storage
    -> return JDBC or DRDA results
```

Storage selection does not create a second SQL engine. Heap and MVCC share the parser, catalog,
optimizer, execution framework, transaction boundary, JDBC behavior, and DRDA server.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
[`docs/STORAGE-ARCHITECTURE.md`](docs/STORAGE-ARCHITECTURE.md).

## Storage modes

### Derby-compatible heap

The inherited heap is the default:

```sql
CREATE TABLE account (
    id INTEGER PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL
);
```

This path preserves Derby-compatible durable formats and behavior. DelosDB may improve diagnostics,
security, build tooling, and correctness around the heap, but does not silently change its disk
format.

### `delos_mvcc`

The MVCC engine is selected explicitly:

```sql
CREATE TABLE account (
    id INTEGER PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL
) USING delos_mvcc;
```

The current MVCC implementation includes:

- statement and transaction snapshots;
- insert, update, delete, commit, rollback, and savepoints;
- primary, unique, and secondary indexes;
- durable page-backed row versions and overflow values;
- WAL, transaction-status, outcome, checkpoint, and recovery state;
- bounded commit grouping and concurrent immutable commit preparation;
- database-owned maintenance and vacuum scheduling;
- database-scoped online-backup coordination;
- consistency checks, crash tests, JFR events, and durability metrics.

Current pre-1.0 limitations include:

- JAVA_OBJECT and Derby UDT columns reject with SQLState `0A000` before an MVCC table is created;
- access to `delos_mvcc` at JDBC `SERIALIZABLE` rejects with SQLState `0A000` before a scan or
  write opens;
- MVCC XA writes remain unsupported.

See [`docs/sql-extensions.md`](docs/sql-extensions.md),
[`docs/MVCC-DURABILITY-PROTOCOL.md`](docs/MVCC-DURABILITY-PROTOCOL.md), and
[`docs/DERBY-COMPATIBILITY.md`](docs/DERBY-COMPATIBILITY.md).

## Security defaults

DRDA retains Derby-compatible mode names, but their meaning is explicit: `basic` is TLS encryption
without peer identity verification, while `peerAuthentication` uses certificate-authenticated TLS.
DRDA and import Java-object deserialization fail closed by default, replication accepts only its
fixed protocol shapes, and heap `JAVA_OBJECT` reads use a separate resource-bounded compatibility
policy. Explicit allow-lists and narrowly scoped compatibility switches are available for trusted
legacy data. XML transformation paths use centralized secure factories.

See [`docs/SECURITY.md`](docs/SECURITY.md).

## Current program

Phases 1-7 established the storage foundation and concurrent commit pipeline. Phase 8 is the active
v1.0 phase. Ownership, transaction authority, deterministic failure replay, isolation/type truth,
and focused security corrections are complete; the current work is capturing the post-correction
performance and resource baseline.

See [`docs/CLEANUP-CONSOLIDATION.md`](docs/CLEANUP-CONSOLIDATION.md) and
[`docs/V1-BASELINE.md`](docs/V1-BASELINE.md).

## Build requirements

- JDK 25
- the Gradle Wrapper included in this repository

Use the wrapper rather than a system Gradle installation:

```bash
./gradlew --version
```

The inherited Ant workflow is not a supported DelosDB build path.

## Primary verification gates

Focused runtime-provider verification:

```bash
./gradlew verifyDelosRuntimeStorageProviders
```

MVCC SQL integration:

```bash
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
```

Derby language compatibility:

```bash
./gradlew :delosdb-tests:runDerbyLangSuite
```

Stable static and repository gates:

```bash
./gradlew s0CloseoutVerification
```

Opt-in production-closeout evidence capture:

```bash
./gradlew :delosdb-tests:captureDelosV1Baseline --console=plain
```

The capture includes a real jlink/JPMS DRDA lane and separate raw decision-force and MVCC
participant-publication timing. It is emitted as `CAPTURED_NOT_ACCEPTED`; the historical accepted
v1 bundle remains immutable and is verified by S0 without rerunning machine-specific measurements.

Full storage-module verification:

```bash
./gradlew \
  :delosdb-storage-api:check \
  :delosdb-storage-derby:check \
  :delosdb-storage-bridge:check \
  :delosdb-storage-mvcc:check
```

See [`docs/BUILDING.md`](docs/BUILDING.md) for the complete build and validation workflow.

## Runtime artifacts

DelosDB currently retains Derby-compatible artifact names:

```text
derby.jar
derbyclient.jar
derbynet.jar
derbyoptionaltools.jar
derbyrun.jar
derbyshared.jar
derbytools.jar
osgi-framework-stub.jar
```

Runtime jars are written to `build/libs/`.

## Repository map

| Area | Responsibility |
|---|---|
| `delosdb-engine` | SQL compilation, execution, catalogs, JDBC engine integration |
| `delosdb-client` | Derby-compatible network client |
| `delosdb-server` | Derby-compatible DRDA server |
| `delosdb-storage-derby` | inherited heap and raw-store implementation |
| `delosdb-storage-api` | provider-neutral storage contracts and diagnostics |
| `delosdb-storage-bridge` | Derby access-method integration for DelosDB storage |
| `delosdb-storage-mvcc` | page-backed MVCC engine |
| `delosdb-tools` | command-line and administrative tools |
| `delosdb-tests` | inherited compatibility and DelosDB integration tests |
| `benchmarks/jmh` | opt-in public-JDBC and storage benchmarks |
| `docs` | tracked architecture, compatibility, operations, and historical evidence |

A complete documentation index is available at [`docs/README.md`](docs/README.md).

## Relationship to Apache Derby

DelosDB is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache
Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software
Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See [`NOTICE-FORK.md`](NOTICE-FORK.md) for
fork attribution.
