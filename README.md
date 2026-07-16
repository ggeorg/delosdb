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

- BLOB and CLOB values are rejected by `delos_mvcc` pending a complete streaming and lifecycle
  design;
- JAVA_OBJECT and Derby UDT values are rejected by `delos_mvcc` durable rows;
- `delos_mvcc` currently maps JDBC `SERIALIZABLE` to a transaction snapshot and therefore does not
  prevent write skew. The v1.0 contract requires early rejection until true serializability exists.

See [`docs/sql-extensions.md`](docs/sql-extensions.md),
[`docs/MVCC-DURABILITY-PROTOCOL.md`](docs/MVCC-DURABILITY-PROTOCOL.md), and
[`docs/DERBY-COMPATIBILITY.md`](docs/DERBY-COMPATIBILITY.md).

## Current program

Phases 1-7 established the storage foundation and concurrent commit pipeline. Phase 8 is the active
v1.0 phase and focuses on product truth, security defaults, stale-surface removal, the Derby debt
ledger, and a frozen performance and resource baseline.

The local `.delosdb-v1/` planning workspace is intentionally ignored by Git. Stable conclusions are
promoted into tracked source, tests, this README, and `docs/` as each slice closes.

See [`docs/CLEANUP-CONSOLIDATION.md`](docs/CLEANUP-CONSOLIDATION.md).

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
