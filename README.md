# DelosDB

DelosDB is a Java 25, Gradle-only fork of Apache Derby 10.17.1.0. The project keeps Derby's embedded SQL/JDBC and DRDA compatibility surfaces intact while modernizing selected internals through small executable proofs.

DelosDB is not a production-ready database release yet. The current project is a compatibility-preserving database-kernel fork with an opt-in `delos_mvcc` storage engine and a cleaned-up Derby-compatible network server.

## Project direction

DelosDB's rule is simple:

```text
Preserve Derby compatibility at the public boundaries.
Modernize internals only behind small, verified seams.
Do not replace working Derby behavior with broad rewrites.
```

Important compatibility boundaries:

```text
Derby heap/raw-store format
  existing Derby-compatible storage path; default remains unchanged

DRDA/JDBC wire protocol
  existing network-client compatibility boundary; do not replace with protobuf,
  gRPC, JSON, or a new protocol inside delosdb-server

SQL/JDBC surface
  inherited Derby behavior remains the default unless a DelosDB extension is
  explicitly requested
```

## Current status

### Default Derby-compatible path

The normal heap-backed Derby-compatible path remains the default:

```sql
CREATE TABLE t (id int);
```

No global default-store flip has been made.

### Opt-in MVCC path

The `delos_mvcc` engine is explicit and opt-in:

```sql
CREATE TABLE t (id int, value varchar(100)) USING delos_mvcc;
```

The MVCC storage path is now a serious Derby-integrated storage-engine path:

```text
SQL
  -> Derby language / transaction layer
  -> Derby access/store conglomerate bridge
  -> delosdb-storage-api
  -> delosdb-storage-mvcc
```

Current green MVCC capabilities include:

```text
CREATE TABLE ... USING delos_mvcc
INSERT / UPDATE / DELETE
commit / rollback
savepoint rollback for insert/update/delete/key reuse
same-transaction read-your-own-write behavior
shutdown / reopen
process-halt crash-boundary reopen proof
mixed heap + MVCC transactions
multiple MVCC tables in one transaction
cross-connection visibility
READ COMMITTED and REPEATABLE READ behavior
primary key, secondary index, unique index behavior
write/write conflict mapping through the public SPI
DROP TABLE cleanup
SQL vacuum/compress
active-snapshot protection during vacuum
vacuum cleanup after snapshot release
complex-workload durable consistency checking
crash/vacuum/checkpoint stale-metadata recovery
vacuum chain rebasing
Derby typed DataValueDescriptor row codec
normal SQL type coverage
MVCC page checksums / torn-write detection
long VARCHAR payloads through overflow pages
overflow lifecycle through rollback/update/delete/vacuum/reopen
explicit JAVA_OBJECT / Derby UDT object rejection
explicit BLOB/CLOB boundary rejection
whole-page reuse with reusable-page index, recovery, and stale-entry protection
MVCC page cache lifecycle and bounded eviction gate
MVCC page-record headers, consistency validation, and slot accounting
MVCC page-scan and diagnostics consolidation
MVCC storage/server static closeout gates
```

Current MVCC boundary decisions:

```text
JAVA_OBJECT / SQL_USERTYPE / SERIALIZABLE_FORMAT_ID
  rejected in delos_mvcc durable rows

BLOB / CLOB
  rejected in delos_mvcc durable rows until a deliberate LOB lifecycle design exists

Derby heap format
  preserved; do not retrofit MVCC row-format changes into heap

Protobuf
  not used on DRDA/JDBC wire and not the first choice for MVCC durable rows
```

### Storage closeout baseline

The current storage baseline includes four completed hardening lanes:

```text
Derby heap consistency checking through SYSCS_UTIL.SYSCS_CHECK_TABLE
explicit MVCC isolation read-view policy
opt-in Derby heap object deserialization filtering
provider-neutral cross-engine consistency reporting
```

The runtime artifact model also verifies `delos_mvcc` provider discovery before the focused SQL integration gate runs. This prevents the MVCC engine from compiling successfully while being absent from the Derby-compatible runtime jar set.

See `docs/STORAGE-ARCHITECTURE.md` for the storage architecture and closeout baseline.
See `docs/CLEANUP-CONSOLIDATION.md` for the cleanup/consolidation phase.
See `docs/STORAGE-ROADMAP.md` for the closed checkpoint cycles and current fork-diff classification phase.

### Network server path

`delosdb-server` remains a Derby-compatible DRDA server. The current modernization slice is compatibility-safe:

```text
unused legacy server compile dependencies removed
server static gates added
runtimeinfo no longer forces a JVM GC
DRDA session scheduler isolated behind DrdaSessionScheduler
scheduler behavior gate added
optional DRDA virtual-thread worker mode added
large EXTDTA values spool to temp storage above threshold
DelosDB-owned DRDA server configuration centralized
```

See `docs/DELOSDB-SERVER.md` for server details.

## Build requirements

- JDK 25
- Gradle Wrapper from this repository

Use the wrapper, not a system Gradle command:

```sh
./gradlew --version
```

The inherited Ant workflow is not part of the supported DelosDB workflow.

## Main verification gates

Runtime provider gate:

```sh
./gradlew verifyDelosRuntimeStorageProviders
```

Focused MVCC SQL gate:

```sh
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
```

Focused server gate:

```sh
./gradlew :delosdb-tests:runDelosServerSchedulerTest :delosdb-server:compileJava delosServerStaticAnalysis
```

Full verification:

```sh
./gradlew clean fullVerification :delosdb-storage-mvcc:check
```

Static closeout gate:

```sh
./gradlew s0CloseoutVerification
```

Broader Derby compatibility checks remain useful before a release-style push:

```sh
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```

## Useful Gradle tasks

```sh
./gradlew build
./gradlew fullVerification
./gradlew derbyRuntimeSmoke
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-tests:runDelosServerSchedulerTest
./gradlew verifyDelosRuntimeStorageProviders
./gradlew delosStorageStaticAnalysis
./gradlew delosHeapObjectDeserializationFilterStaticAnalysis
./gradlew delosCrossEngineConsistencyFrameworkStaticAnalysis
./gradlew delosRuntimeArtifactModelStaticAnalysis
./gradlew delosServerStaticAnalysis
./gradlew s0CloseoutVerification
./gradlew dist
```

## Gradle subprojects

| Subproject | Responsibility | Runtime artifact |
|---|---|---|
| `:delosdb-osgi-stub` | inherited OSGi stub compatibility | `osgi-framework-stub.jar` |
| `:delosdb-commons` | shared runtime classes | `derbyshared.jar` |
| `:delosdb-runtime-api` | DelosDB runtime API support | packaged as needed |
| `:delosdb-engine` | embedded SQL engine | `derby.jar` |
| `:delosdb-client` | Derby-compatible network client | `derbyclient.jar` |
| `:delosdb-tools` | command-line and admin tools | `derbytools.jar` |
| `:delosdb-runner` | inherited command launcher | `derbyrun.jar` |
| `:delosdb-optionaltools` | optional tool integrations | `derbyoptionaltools.jar` |
| `:delosdb-server` | Derby-compatible DRDA network server | `derbynet.jar` |
| `:delosdb-storage-api` | DelosDB storage diagnostics/API seam | development module |
| `:delosdb-storage-bridge` | Derby access-method bridge for Delos storage | development module |
| `:delosdb-storage-mvcc` | opt-in MVCC storage engine | development module |
| `:delosdb-storage-derby` | inherited Derby-compatible storage implementation | packaged into Derby-compatible runtime |
| `:delosdb-derby-store-api` | inherited Derby store API split | development module |
| `:delosdb-storage-io` | storage IO helpers | development module |
| `:delosdb-storeless` | compiler/optimizer boot without storage | development module |
| `:delosdb-tests` | inherited and DelosDB-focused test gates | test module |
| `:delosdb-pptesting` | package-private inherited tests | test module |
| `:delosdb-buildtools` | build-time generators/scanners | build tooling |
| `:delosdb-locales` | generated locale verification | verification module |
| `:delosdb-demos` | local demos | development module |

## Runtime artifacts

Runtime jars are written to `build/libs/` and intentionally keep Derby-compatible file names during this preview phase:

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

## Documentation

Maintained project documentation:

```text
docs/BUILDING.md
docs/DERBY-COMPATIBILITY.md
docs/STORAGE-ARCHITECTURE.md
docs/CLEANUP-CONSOLIDATION.md
docs/DELOSDB-SERVER.md
docs/sql-extensions.md
```

Root-level project documents:

```text
README.md
CONTRIBUTING.md
GOVERNANCE.md
SECURITY.md
NOTICE-FORK.md
```

## Relationship to Apache Derby

DelosDB is based on Apache Derby 10.17.1.0 source code. Apache Derby was developed by the Apache Software Foundation and distributed under the Apache License, Version 2.0.

DelosDB is not an Apache Software Foundation project and is not endorsed by the Apache Software Foundation. Apache, Apache Derby, and Derby are trademarks of the Apache Software Foundation.

The original `LICENSE` and `NOTICE` files are preserved. See `NOTICE-FORK.md` for additional fork attribution.
