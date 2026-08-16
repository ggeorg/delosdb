# DelosDB

DelosDB is a Java 25 relational database management system derived from Apache Derby 10.17.1.0.
It preserves Derby-compatible SQL, JDBC, catalogs, heap behavior, database formats, and DRDA while
modernizing selected internals through explicit, tested boundaries.

DelosDB is pre-1.0. The current repository includes RawStore-converged MVCC, JDK 25 generated-class
modernization, repository-integrity consolidation, stable selected-plan modeling, deterministic `EXPLAIN`,
storage-aware `EXPLAIN ANALYZE`, and an executable end-to-end query trace. Public documentation is
organized around the implemented product architecture, supported behavior, and current limitations.

## Architecture

DelosDB has one SQL, transaction, and durable-storage authority:

```text
Embedded JDBC or DRDA
    -> parse and bind
    -> optimize
    -> generate activation classes
    -> execute result-set operators
    -> coordinate Derby transactions
    -> access one Derby RawStore
         -> inherited heap access method
         -> DelosDB MVCC access method
    -> return JDBC or DRDA results
```

The heap and `delos_mvcc` are access methods over the same Derby RawStore authority. `delos_mvcc`
does not own a second file store, WAL, checkpoint, recovery, backup, or runtime selector.

Generated SQL activation classes use one production backend:

```text
JavaFactory / ClassBuilder / MethodBuilder / LocalField
    -> ClassFileJava
    -> JDK 25 java.lang.classfile
```

There is no external ASM dependency, fallback backend, or runtime backend selector.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md),
[`docs/READABLE-ENGINE.md`](docs/READABLE-ENGINE.md),
[`docs/STORAGE-ARCHITECTURE.md`](docs/STORAGE-ARCHITECTURE.md), and
[`docs/design/V1-GENERATED-CLASS-ARCHITECTURE.md`](docs/design/V1-GENERATED-CLASS-ARCHITECTURE.md).

## Storage modes

### Derby-compatible heap

The inherited heap remains the default compatibility path:

```sql
CREATE TABLE account (
    id INTEGER PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL
);
```

### `delos_mvcc`

MVCC is selected explicitly:

```sql
CREATE TABLE account (
    id INTEGER PRIMARY KEY,
    balance DECIMAL(19, 2) NOT NULL
) USING delos_mvcc;
```

The current MVCC path includes transaction snapshots, insert/update/delete, commit/rollback,
savepoints, primary/unique/secondary indexes, RawStore-backed row versions, decision and recovery
records, checkpoint/recovery integration, maintenance, vacuum, backup coordination, memory-database
support, DRDA execution, and deterministic failure testing.

Current pre-1.0 limitations include:

- `JAVA_OBJECT` and Derby UDT values are rejected for `delos_mvcc` with SQLState `0A000`;
- access to a `delos_mvcc` table at JDBC `SERIALIZABLE` is rejected with SQLState `0A000`;
- MVCC XA writes remain unsupported.

See [`docs/sql-extensions.md`](docs/sql-extensions.md) and
[`docs/DERBY-COMPATIBILITY.md`](docs/DERBY-COMPATIBILITY.md).

## Current status

Completed foundations include:

- RawStore-backed MVCC convergence with no parallel persistence runtime;
- JDK 25 Class-File API activation generation with a frozen 52-method contract;
- module and runtime-provider convergence;
- repository-wide dead-code, duplicate, catch, complexity, and verification consolidation;
- permanent repository-verification authorities based on executable or structural evidence.

The readable-engine foundation is implemented: DelosDB has one stable selected-plan model, deterministic
`EXPLAIN`, bounded query-only `EXPLAIN ANALYZE` with operator timing/cardinality, estimate comparison,
MVCC read-path diagnostics and exact scan snapshot identity, plus one executable end-to-end trace shared
by public documentation and teaching material. Remaining pre-1.0 work is described as product capabilities,
limitations, and validation work.

See [`docs/PROJECT-STATUS.md`](docs/PROJECT-STATUS.md).

## Build requirements

- JDK 25
- the checked-in Gradle Wrapper

```bash
./gradlew --version
./gradlew build
```

The inherited Ant workflow is not a supported DelosDB build path.

## Verification

Fast permanent verification:

```bash
./gradlew s0CloseoutVerification --console=plain
```

Focused generated-class acceptance:

```bash
./gradlew :delosdb-tests:runDelosGeneratedClassProductionAcceptance --console=plain
```

MVCC SQL integration:

```bash
./gradlew :delosdb-tests:delosFunctionalTests :delosdb-tests:delosConcurrencyTests :delosdb-tests:delosRecoveryTests --console=plain
```

Readable-engine demonstration:

```bash
./gradlew readableEngineDemo --console=plain
```

The inherited Derby language suite is intentionally expensive and should be run at meaningful
major verification boundaries rather than after every focused change:

```bash
./gradlew :delosdb-tests:derbyLanguageTests --console=plain
```

See [`docs/BUILDING.md`](docs/BUILDING.md) and
[`docs/STATIC-GATE-POLICY.md`](docs/STATIC-GATE-POLICY.md).

## Repository map

| Area | Responsibility |
|---|---|
| `delosdb-engine` | SQL compilation, execution, catalogs, JDBC engine integration |
| `delosdb-client` | Derby-compatible network client |
| `delosdb-server` | Derby-compatible DRDA server |
| `delosdb-derby-store-api` | neutral Derby storage contracts shared by access methods |
| `delosdb-storage-derby` | inherited heap and RawStore implementation |
| `delosdb-storage-mvcc` | RawStore-backed MVCC access method and provider integration |
| `delosdb-tools` | command-line and administrative tools |
| `delosdb-tests` | inherited compatibility and DelosDB integration tests |
| `benchmarks` | opt-in reproducible benchmark and validation evidence |
| `docs` | public product, architecture, operations, and contributor documentation |

A complete public documentation index is available at [`docs/README.md`](docs/README.md).

## Documentation authority

Documentation explains the implementation; it does not determine whether the build passes.
Permanent verification uses Java structure, module/dependency metadata, runtime providers, bytecode, tests,
and checked structural manifests. Comments, Markdown wording, planning status, and exact report prose
are not executable authority.

## Relationship to Apache Derby

DelosDB is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache
Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software
Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See [`NOTICE-FORK.md`](NOTICE-FORK.md).
