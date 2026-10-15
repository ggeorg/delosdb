# Source guide for students and fork authors

DelosDB's teaching subject is the production database. Start with one operation and follow its ownership, then change one algorithm and run its proof. The architectural contracts are in [ARCHITECTURE.md](ARCHITECTURE.md) and the current Gradle/runtime distinction is in [V1-MODULE-ARCHITECTURE.md](V1-MODULE-ARCHITECTURE.md).

## Where to start

| Question | Current owner and entry point |
|---|---|
| Where does embedded SQL enter? | [EmbedStatement](../delosdb-engine/src/main/java/org/apache/derby/impl/jdbc/EmbedStatement.java) in `delosdb-engine` |
| Who orchestrates compilation? | [GenericStatement](../delosdb-engine/src/main/java/org/apache/derby/impl/sql/GenericStatement.java): parse, bind, optimize, generate |
| Where is cost-based optimization? | [OptimizerImpl](../delosdb-engine/src/main/java/org/apache/derby/impl/sql/compile/OptimizerImpl.java); see [OPTIMIZER-AUTHORITY.md](OPTIMIZER-AUTHORITY.md) |
| How does a row insert execute? | [InsertResultSet](../delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java) and [HeapController](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/heap/HeapController.java) |
| Where are catalogs owned? | [DataDictionaryImpl](../delosdb-engine/src/main/java/org/apache/derby/impl/sql/catalog/DataDictionaryImpl.java) |
| Who owns physical page validity and allocation? | [FileContainer](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/FileContainer.java), [AllocationCache](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/AllocationCache.java), [BasePage](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/BasePage.java) |
| Who builds/appends log records? | [FileLogger](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/FileLogger.java) and [LogToFile](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/log/LogToFile.java) |
| Who owns physical transaction outcome/recovery? | [Xact](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/xact/Xact.java), [RawStore](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/RawStore.java), and log recovery in `LogToFile` |
| Who owns logical MVCC state? | [MvccRawStoreRuntime](../delosdb-storage-mvcc/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccRawStoreRuntime.java) and [MvccRawStoreScanController](../delosdb-storage-mvcc/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccRawStoreScanController.java) |
| How do index reads on MVCC tables see only the snapshot's rows? | Query scans open with `OPENMODE_SNAPSHOT_READ`; [BTreeScan](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/btree/BTreeScan.java) checks entries through the base controller's `AccessMethodSnapshotIndexEntries`, and the engine's `impl/sql/catalog/EngineIndexDefinitionSupport` names the base columns an index holds; see [MVCC-SECONDARY-INDEX-VISIBILITY.md](design/MVCC-SECONDARY-INDEX-VISIBILITY.md) |
| Who checks a page's physical invariants? | `Page.checkPhysicalConsistency` in [Page](../delosdb-derby-store-api/src/main/java/org/apache/derby/iapi/store/raw/Page.java), implemented by [StoredPage](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/StoredPage.java) with [StoredPageSanityChecker](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/raw/data/StoredPageSanityChecker.java); `SYSCS_CHECK_TABLE` reaches it through [HeapSanityChecker](../delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/heap/HeapSanityChecker.java) |
| Who adapts store values and row locations to SQL types? | `delosdb-engine/.../impl/sql/storetypes`: [EngineStoreRowLocationBridge](../delosdb-engine/src/main/java/org/apache/derby/impl/sql/storetypes/EngineStoreRowLocationBridge.java) and the two ServiceLoader providers; contract code reaches it through `RowLocationServicesRegistry` and `StoreTypeSupportRegistry` |
| Where are lock/cache implementations? | `delosdb-engine/.../impl/services/{locks,cache}`; generic contracts are in `delosdb-runtime-api` |
| Who names tables and transactions in lock timeout and deadlock reports? | `Deadlock` and `Timeout` call the runtime-api contract `LockReportSupport`; the engine implements it in `impl/sql/conn/EngineLockReportSupport`, which uses `TableNameInfo` |
| Which lock requests take the lock manager's fast paths? | `ConcurrentLockSet` and `AbstractPool` compare requests with the store's lock modes from `LockFastPaths`, which reads the runtime-api contract `LockFastPathSupport`; derby-store-api implements it in `iapi/store/raw/StoreLockFastPathSupport` |
| Which cache entries are kept without the entry mutex? | Those of a cache created with `lockFreeKeeps` (`CacheFactory.newCacheManager`); the store asks for it only for its container cache in `BaseDataFileFactory`, and `ConcurrentCache` passes it to every `CacheEntry` through `CacheEntry.Kind` |
| Who owns forked benchmark JVM execution? | [BenchmarkWorkerProcess](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkWorkerProcess.java) owns launch/wait/timeout/log failure handling; [BenchmarkWorkerCommand](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkWorkerCommand.java) constructs arguments; [BenchmarkConfiguration](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkConfiguration.java) owns defaults, campaign validation and classpaths; [BenchmarkWorkloadRunner](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkWorkloadRunner.java) owns the child case schedule and report lifecycle; [BenchmarkCaseMeasurement](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkCaseMeasurement.java) owns measurement; [BenchmarkConcurrentCase](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkConcurrentCase.java) owns concurrent clients and intervals |
| Where are shared benchmark workloads, targets and result contracts? | [BenchmarkModel](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkModel.java) defines targets/endpoints, workload classification, case shapes, measurement/oracle/protocol records and CSV contracts |
| Who owns per-case benchmark measurement? | [BenchmarkCaseMeasurement](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkCaseMeasurement.java) owns warmup/measured budgets, semantic verification and cleanup; [BenchmarkConcurrentCase](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkConcurrentCase.java) owns concurrent clients and intervals |
| Who owns concurrent benchmark connections and intervals? | [BenchmarkConcurrentCase](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkConcurrentCase.java) owns client/reader connections, prepared statements, barriers, futures, deadlines and cleanup; its verifier connection is borrowed |
| Where are benchmark results verified and fixtures restored? | [BenchmarkCaseVerification](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkCaseVerification.java) verifies expected state and restores reusable intervals using the borrowed verifier and captured case baselines |
| Where are shared benchmark SQL and workload calculations? | [BenchmarkWorkloadSql](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkWorkloadSql.java) owns benchmark statement construction, row selection and expected-value calculations; this is test support, not the database SQL compiler |
| Where are older benchmark experiments? | [BenchmarkJoinExperiments](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkJoinExperiments.java) owns join/costing/access experiments; [BenchmarkRelationalExperiments](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkRelationalExperiments.java) owns projection/group/sort experiments; [BenchmarkExperimentSupport](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkExperimentSupport.java) owns shared explain/profile helpers |
| Who owns parent benchmark containers and commands? | [BenchmarkContainerCampaign](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkContainerCampaign.java) owns container startup/readiness/server profiling; [BenchmarkParentCommand](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkParentCommand.java) owns captured command execution |
| Where are parent host observation and campaign reports? | [BenchmarkHostObservation](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkHostObservation.java) owns host/process sampling and cooldown; [BenchmarkReports](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkReports.java) owns validation, aggregation and published campaign evidence |
| Where are benchmark fixtures built? | [BenchmarkFixtures](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkFixtures.java) owns base/auxiliary table preparation, fixture data, statistics refresh and range-fetch setup |
| Where is per-case benchmark diagnostic evidence collected? | [BenchmarkCaseDiagnostics](../delosdb-tests/src/delosTestSupport/java/io/github/ggeorg/delosdb/benchmark/jdbc/BenchmarkCaseDiagnostics.java) owns counters/snapshots and evidence files; parent sampling is in `BenchmarkHostObservation`; shared explain helpers are in `BenchmarkExperimentSupport` |
| Where are transport and client ownership? | `delosdb-server/.../impl/drda` and `delosdb-client/.../client` |

One database transaction decision and one RawStore persistence/recovery authority serve heap and MVCC access methods. A fork experimenting with MVCC visibility does not need to invent a separate WAL. A fork experimenting with page placement must preserve physical page validity, rollback and recovery.

## Follow a real query

```bash
./gradlew readableEngineDemo --console=plain
```

[READABLE-ENGINE.md](READABLE-ENGINE.md) walks from SQL compilation through generated activation, execution, storage and stable EXPLAIN evidence. The demo uses real runtime jars and a disposable database under `build/`. It is a learning entry point, not an alternate execution engine.

## Understand the build layers

- `settings.gradle` defines Gradle source owners. A project is not necessarily a separately loaded runtime module.
- `module-info.java` defines JPMS identities and exports where present.
- [gradle/delosdb-runtime-artifacts.gradle](../gradle/delosdb-runtime-artifacts.gradle) owns assembled and distributed artifacts. Storage payloads are currently incorporated into the Derby-compatible engine jar.
- [gradle/testing/delos-test-suite-registry.tsv](../gradle/testing/delos-test-suite-registry.tsv) owns permanent test placement. Test provenance records distinguish inherited and Delos-authored proof.
- Benchmarks are opt-in and can launch external engines/processes. Their timing results are not correctness proofs.

Generate an approximate source/import owner inventory with:

```bash
python3 tools/architecture/source_inventory.py \
  --output build/reports/delosdb/architecture-reference/delosdb-source-inventory.json
```

The inventory reports owner-to-owner imports, package cycles within each owner and imports from contract packages into implementation packages. It covers authored source only: it does not resolve generated, reflective or service-loader dependencies, and it is not a build gate. The current findings are in the dependency map in [REFERENCE-CODEBASE-STRUCTURE.md](design/REFERENCE-CODEBASE-STRUCTURE.md); the tool's own check is `python3 -B -m unittest discover -s tools/architecture`.

## A small fork exercise

Choose one responsibility, such as heap insert-page selection, an optimizer estimate, or MVCC visibility. First write down its invariant and owning transaction/lock/page lifecycle. Run the existing proof before editing. Change the implementation without simultaneously renaming packages or changing durability settings. Add a test that fails for the defect or semantic difference, then compare performance with the same workload and flags.

For page selection/validity, start with `HeapInsertLaneTest` and `CachedPageValidityTest` in the permanent concurrency suite:

```bash
./gradlew :delosdb-tests:delosConcurrencyTests \
  --tests '*HeapInsertLaneTest' --tests '*CachedPageValidityTest' \
  -Pdelosdb.sane=false --console=plain
```

To move a package, use the source inventory first, then classify every name the package carries. The checks below come from the migration rules in [REFERENCE-CODEBASE-STRUCTURE.md](design/REFERENCE-CODEBASE-STRUCTURE.md):

- `exports` and `provides` in `module-info.java`;
- `META-INF/services` files;
- `modules.properties`;
- `RegisteredFormatIds`;
- string-literal class names in production code and tests;
- `--add-exports` lines and test-support patches in `delosdb-tests/build.gradle`.
- the recorded SHA-256 of any adapted inherited test the move edits, in `gradle/testing/derby-test-adaptations.tsv` and `derby-test-adaptation-support.tsv`; `delosTestProvenanceStaticAnalysis` checks them.

Update all of them in the same commit, and change nothing else.

Keep engine classes under `org.apache.derby`. Embedding applications may load Derby through a class loader that loads only that prefix itself, and `ClassLoaderBootTest` in the store suite proves that boot still works that way. The move of `impl.services.storetypes` to `impl.sql.storetypes` is the worked example:

```bash
./gradlew s0CloseoutVerification derbyRuntimeSmoke \
  :delosdb-tests:delosTestProvenanceStaticAnalysis \
  :delosdb-tests:derbyStoreTests :delosdb-tests:derbyNetworkTests \
  :delosdb-tests:delosFunctionalTests --tests '*StoreValueOperationsDirectPathTest' \
  --console=plain
```

For the page-invariant contract, `HeapSanityCheckerTest` breaks one invariant in memory and requires the check to report it:

```bash
./gradlew :delosdb-tests:delosFunctionalTests --tests '*HeapSanityCheckerTest' --console=plain
```

The [embedded insert probe](../tools/diagnostics/insert-probe/README.md) provides a narrow throughput/attribution comparison. Retain all runs and settings; a favorable rate does not replace rollback, consistency and crash recovery checks.

The compatibility-preserving restructuring proposal and local reference-database comparison are in [REFERENCE-CODEBASE-STRUCTURE.md](design/REFERENCE-CODEBASE-STRUCTURE.md). Its proposed directories/modules are migration targets, not current source paths.
