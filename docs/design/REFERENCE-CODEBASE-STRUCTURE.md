# DelosDB as an educational reference codebase

**Decision direction:** preserve Derby-compatible databases, JDBC entry points and DRDA; reorganize internals incrementally. **Status:** comparison and migration design, with the first build-script extraction implemented. This is not the current module manifest. The current architecture remains in [V1-MODULE-ARCHITECTURE.md](../V1-MODULE-ARCHITECTURE.md).

## Purpose

A fork author should be able to follow a real query, identify the owner of an algorithm, change that algorithm, and run its correctness and performance proofs. A student should encounter the production database, including its recovery and concurrency obligations. Organization should make these obligations visible.

The criteria are ownership, comprehensible paths through the system, a manageable build, and executable extension boundaries. A lower module count or a new package prefix alone does not meet those criteria.

## Sources inspected

This comparison uses the local snapshots in the OpenSource folder, not claims about the newest upstream releases. PostgreSQL, MariaDB, DuckDB, SQLite, HerdDB, MapDB and Neo4j are ZIP archives; Firebird and Calcite are tar archives; H2 and Derby are unpacked. Calcite is a query-planning framework and MapDB a storage library, so their organization is useful without treating them as equivalent SQL databases.

Selected source files and hashes are recorded in `build/reports/delosdb/architecture-reference/local-reference-sources.json`. Selected archive members are copied under that report's `sources/` directory for inspection; none becomes DelosDB production code.

| Local source | Structure observed | Lesson for DelosDB | Limit of the analogy |
|---|---|---|---|
| PostgreSQL, `postgres-master.zip` | `src/backend/{parser,optimizer,executor,catalog,access,storage}`; corresponding headers; backend build assembles these into one server; `src/include/access/tableam.h` defines table access callbacks | Name responsibilities clearly; put access contracts beside their invariants; teach a query across actual subsystems | Source directories are not independent runtime modules; its process and extension model differs from Java/DRDA |
| MariaDB, `server-main.zip` | SQL server under `sql/`; storage engines under `storage/`; `sql/handler.h` is the server/engine boundary; InnoDB has `btr`, `buf`, `dict`, `lock`, `log`, `row`, `trx` | Distinguish SQL, access methods, page/buffer management, transaction semantics and WAL ownership | The large SQL directory and abbreviated legacy names are not a readability target; independent storage engines are not DelosDB's shared-RawStore model |
| DuckDB, `duckdb-main.zip` | `src/{parser,planner,optimizer,execution,catalog,storage,transaction}`; `src/README.md` explains the pipeline | A source map and full subsystem names are excellent learning aids; make database ownership explicit | Its execution architecture is not a replacement for generated Derby activations |
| H2, `h2database-master` | `org.h2.{command,engine,expression,index,mvstore,result,schema,store,table,value}`; database/session integration is visible in `engine/Database.java` | Clear domain packages can be valuable within one coherent engine artifact | Compact integration does not imply every class or dependency is easy to separate |
| HerdDB, `herddb-master.zip` | Maven modules for core, JDBC, network, services, utilities and benchmarks; core packages include `sql`, `core`, `storage`, `log`, `index`, `model` | Separate transport, runtime and benchmarks; keep useful data/operation models discoverable | Its distributed lifecycle should not be imported into DelosDB |
| Neo4j, `neo4j-2026.07.zip` | `community/{kernel,kernel-api,io,lock,record-storage-engine,bolt,cypher,...}` with module-local build files | Separate a contract from an implementation when the boundary is real; module-local tests and transport ownership help forks | Its large module graph and graph-query architecture are not a template to reproduce |
| SQLite, `sqlite-master.zip` | Named source files for parser, VDBE execution, B-tree, pager, WAL and OS interfaces; tests and source generators are separate | Few artifacts can still expose strong conceptual boundaries; distinguish authored source from generated deliverables | An amalgamation is a distribution strategy, not the teaching source tree |
| Firebird, `Firebird-5.0.4.1812-0-source.tar.xz` | `src/{dsql,jrd,remote,common,plugins,utilities,...}` with engine/transaction interfaces in `jrd` | Separate dynamic SQL, engine ownership, remote transport and utilities | Its historical subsystem names and language/runtime architecture differ |
| MapDB, `mapdb-master.zip` | `org.mapdb.{store,io,ser,record,db,...}` | Make storage, serialization and I/O responsibilities findable | It has no comparable complete SQL compiler/executor to copy |
| Calcite, `apache-calcite-1.42.0-src.tar.gz` | `core`, `linq4j`, adapters, `testkit`, benchmarks; core owns planning contracts | Shared planning models and reusable test support are useful extraction boundaries | It does not supply DelosDB's transaction, persistence or recovery authority |
| Apache Derby, unpacked `db-derby-10.17.1.0-src` | JPMS/source roots for engine, shared, client, server, tools and tests; engine contains `iapi` and `impl` layers | Preserve executable compatibility and upstream provenance while explaining inherited ownership | An inherited layout is a starting point, not a permanent naming requirement |

## Measured DelosDB structure

Snapshot: `e1fc6c89` plus the existing working-tree fixes/diagnostics. `settings.gradle` contains 20 active projects. The source inventory scans the 16 owners with `src/main/java`; it excludes generated sources, tests, JPMS descriptors, demos in other source roots and locale resources. Lines include comments and blank lines and are not a maintainability ranking across languages.

| Source owner | Java files | Java lines | Packages |
|---|---:|---:|---:|
| engine | 1,031 | 401,802 | 59 |
| storage-derby | 245 | 117,161 | 16 |
| derby-store-api | 176 | 28,717 | 10 |
| client | 145 | 69,801 | 6 |
| runtime-api | 133 | 24,096 | 19 |
| server | 50 | 32,695 | 5 |
| storage-mvcc | 32 | 16,977 | 1 |

Before the first extraction, `delosdb-tests/build.gradle` had 24,801 lines and the cross-engine coordinator had 14,578. The root build had 767 lines. The dominant build problem is the test/benchmark script and coordinator, not simply the number of projects.

The inventory found no duplicate Java source package ownership across the scanned production projects. Actual runtime packaging still incorporates store contracts, RawStore and the MVCC provider into the Derby-compatible engine jar. Gradle source ownership, JPMS ownership and distributed artifacts must therefore be described separately.

Engine package imports reveal real coupling: `impl.sql.compile` imports `impl.sql.execute` 30 times, and execution imports compilation 8 times. Several SQL contract packages also import implementations. These are explicit-import counts, not a full call graph; reflective and generated dependencies require separate inspection. They are enough to rule out a purely mechanical compiler/executor module split.

Regenerate the read-only inventory:

```bash
python3 tools/architecture/source_inventory.py \
  --output build/reports/delosdb/architecture-reference/delosdb-source-inventory.json
```

## What should change first

1. **Build and benchmark ownership.** Extract task families into convention scripts with explicit inputs. Isolate benchmark dependencies, data cleanup and host/process management from ordinary tests. Separate benchmark coordinator, worker lifecycle, workload definitions, result recording and profiling by responsibility. Do not split one giant file into arbitrary numbered fragments.
2. **A navigable source anatomy.** Give readers an end-to-end source guide and an owner map for SQL, services, access methods, physical storage, MVCC, transport and compatibility. Keep historical experiment names in evidence, not as the first explanation of a production subsystem.
3. **Internal packages.** Organize SQL and storage concepts inside their existing build owners first. Extract shared models and remove reverse imports before promoting those packages to separate modules.
4. **Gradle/module boundaries.** Split only where source dependencies and ownership allow an independent compilation and test boundary. Keep a single SQL compiler/executor owner while its AST, generated-activation and execution contracts are coupled.
5. **Namespace migration.** Move Delos-owned internals gradually to the existing `io.github.ggeorg.delosdb` namespace. Preserve supported external Derby names and names referenced by persisted state, generated descriptors, provider metadata or module discovery until their compatibility mapping is proven.

## Proposed source anatomy

This is the intended organization, not a directory move already performed. Gradle task/project identities can remain stable during folder moves using `projectDir` mappings. Before a move, replace hard-coded source/build paths with owner-provided artifacts and directories.

```text
api/            shared runtime and store/access-method contracts
core/
  services/     contexts, cache, locks, lifecycle, security services
  sql/          parse, bind, optimize, generate, execute, catalog, embedded JDBC
  rawstore/     pages, allocation, WAL, undo, checkpoint, recovery, backup
access/
  heap/         heap, physical B-tree indexes, sort, access-method coordination
  mvcc/         logical MVCC rows/versions/indexes over the same RawStore
adapters/       DRDA client and server
compat/         preserved Derby entry points, discovery and artifact assembly
verification/   component tests, compatibility tests, integration and recovery
benchmarks/     workload definitions, workers, profiles and report analysis
examples/       executable teaching examples using the real runtime
build-logic/    build conventions and verification tooling
```

Embedded JDBC initially stays with SQL: a separate adapter module is justified only when its implementation dependencies are disentangled. Compatibility assembly is packaging, not a second engine.

## Candidate build owners

The existing module identities remain authoritative until each migration is verified and the structured manifest is updated. These candidate names describe responsibility, not a promised fixed module count.

| Current owner | Candidate destination | Prerequisite |
|---|---|---|
| commons, annotations, runtime-api | foundation/runtime contracts, with optional annotations merged only if compatibility packaging permits | Classify shared messages, errors, serialization, contexts and service APIs; retain module/jar facades where supported |
| derby-store-api | the engine's `iapi.store` packages (merged 2026-10-15) | Preserve neutral page/access/transaction/type contracts and their public binary names |
| engine service implementations | packages of the engine (see the consolidation below; `delosdb-kernel` is withdrawn) | Remove SQL implementation dependencies; classify storage-specific lock/cache fast paths rather than assuming they are generic |
| engine SQL/catalog/types/JDBC | `delosdb-sql` | Keep compiler and executor together initially; extract neutral models before separate compiler/executor modules |
| storage-derby's `impl.store.raw` | the engine's `impl.store.raw` packages (merged 2026-10-15) | Preserve one persistence authority and lifecycle; separate cross-access helpers into real contracts |
| storage-derby's `impl.store.access` | the engine's `impl.store.access` packages (merged 2026-10-15) | Remove direct implementation coupling to helpers such as `RawStorePreparedRow` and `HeapPageReadImageAccess`; retain transaction integration |
| storage-mvcc | merged into the engine as `impl.store.access.mvcc` (2026-10-15) | Preserve peer access-method status, provider discovery and shared RawStore ownership |
| client/server | transport owners with clear DRDA names | Preserve wire protocol, security, module exports and independent deployment |
| tests and benchmark support | component-local tests, shared compatibility/integration tests, dedicated benchmarks | Preserve suite registry, adaptation provenance, crash-worker classpaths and external-engine isolation |
| tools, runner, optional tools, demos, locales, OSGi/build tooling | tooling, examples, resources and compatibility assembly | Classify active capabilities before retirement or consolidation; preserve distributions and documented entry points |

Do not recreate the retired storage-api/storage-bridge/storage-io implementations under a new name. `store-contracts` would be a rename/refinement of the current neutral contract owner, not another persistence layer. Do not introduce a module for each lock, latch, page, optimizer rule or diagnostic flag.

## Package migration rules

Prefer responsibility names such as `sql.parse`, `sql.bind`, `sql.optimize`, `sql.execute`, `storage.page`, `storage.wal`, `storage.recovery`, `access.heap`, `access.btree`, `access.mvcc`, `transaction`, and `services.lock`. The package prefix alone does not establish an ownership boundary.

Before moving any class, classify:

- supported public JDBC/API names and qualified JPMS exports;
- stored format ids, class-name serialization and catalog/provider identity;
- generated activation descriptors, reflective factories and service registrations;
- package-private collaborators and implementation imports;
- security/deserialization allowlists and resource lookup paths;
- upstream adaptation provenance and test-support patch modules.

A compatibility facade delegates to the same semantic authority. It must not duplicate transaction decisions, locking, WAL, recovery or SQL compilation. Package moves and algorithm/performance changes belong in different commits.

## Migration slices and acceptance

| Slice | Concrete result | Evidence required |
|---|---|---|
| 0: owner inventory and reading guide | Current owner/import inventory and source guide; record compatibility-preserving direction | Source paths exist; findings distinguish declarations from runtime packaging |
| 1: build organization | Extract benchmark registration families; later extract test fixture/compilation and stable-suite conventions | Same task names, inputs, flags and lazy classpaths; task contract preflight and permanent closeout |
| 2: benchmark code | Split coordinator, forked worker/process lifecycle, workload fixture and results/profiling | Same inserted-row budgets, barriers, engine isolation, cleanup boundaries, report schemas and durability modes |
| 3: source/package ownership | Move one cohesive internal package family and update resources/contracts | Compilation, class/module-path boot, SQL/JDBC/DRDA parity and relevant component tests |
| 4: kernel/store boundaries | Extract services and RawStore/access ownership only after reverse dependencies are removed | Declared artifact graph, neutral contract tests, storage provider discovery, concurrency/rollback/crash/backup proofs |
| 5: compatibility assembly and module consolidation | Make source owners and runtime distribution explainable; keep supported facades | Existing database upgrade/reopen, packaged embedded/server/client smoke, module-path and distribution verification |
| 6: fork/teaching acceptance | A clean checkout can build, trace a query and modify a documented algorithm with focused proof | Executable example, contribution recipe, small component test lane and matched benchmark |

For every slice, preserve pending unrelated changes. Keep performance experiments and diagnostic flags out of structural acceptance. Use existing boundary/integrity/compatibility gates; do not create gates that parse Markdown or enforce this proposal's wording.

## Current execution plan

The objective is an educational reference database that can be understood and forked while preserving existing Derby-compatible databases, public JDBC entry points and DRDA clients. The assessment and coding-style documents guide evidence and implementation discipline; the restructuring scope comes from the user's request.

1. **Checkpoint the completed groundwork.** Save the allocation-page correction, default-off occupancy diagnostics, benchmark ownership extraction, source guide and this plan in reviewable commits. Preserve the distinction between a correctness fix, diagnostic feature and structural changes. The benchmark checks are structural evidence, not a throughput improvement claim.
2. **Map the core query and storage paths.** Trace JDBC request → parse/bind/optimize → execution → transaction/locking → heap or MVCC access → shared RawStore/WAL/recovery. Record callers, actual source/artifact owners, reverse dependencies and compatibility-sensitive names. Select the first cohesive core slice from that evidence.
3. **Extract one internal responsibility at a time.** Start with a component whose dependencies permit a real ownership boundary. Keep one SQL/transaction authority and one persistence/recovery authority; heap and MVCC remain peer access methods. Move package-private collaborators, service/resource lookups and tests together. Separate behavioral/performance changes from relocation.
4. **Change Gradle modules only after the source boundary is sound.** Verify compile imports, runtime jar assembly, JPMS exports/qualified exports and provider discovery. Do not force the mutually dependent compiler/executor packages into separate modules or choose a target module count merely for appearance.
5. **Preserve and prove compatibility.** For each relevant core slice, run focused SQL/storage/concurrency/recovery tests and packaged embedded/DRDA checks. Use reopen/upgrade and generated-activation/resource/security checks when that boundary is affected. Run matched performance comparisons when execution could change; keep workload budgets, durability and measurement conditions explicit and feasible on the laptop.
6. **Make forks teachable.** Maintain the reading guide and add an executable query trace, a component change recipe and focused build/test commands. A new contributor should know the algorithm's owner, its contracts and the evidence required to modify it.

Completed so far: local reference-engine/source-owner assessment; source guide and compatibility-preserving target structure; occupancy Gradle registration extraction; worker process/command/configuration; shared benchmark model; worker and per-case lifecycles; fixtures/diagnostics; concurrent SQL execution and verification/restoration; legacy experiment families; parent container/host/command/report owners. The core query/storage dependency map and compatibility-sensitive name counts are recorded below. Two Delos-authored package families have moved within `org.apache.derby`: `impl.services.storetypes` and `impl.store.access.provider`. The lock manager reaches the SQL layer only through a runtime-api contract. Gradle module boundaries are unchanged.

The lock manager now reaches neither SQL nor the store contracts: lock-object names come through `LockReportSupport` and the store's fast-path lock modes through `LockFastPathSupport`. The cache no longer imports them either: the store asks for lock-free keeps when it creates its container cache. The cipher factory takes its property names from its own contract. No `impl.services` package now imports the SQL layer or the store contracts. A separate `delosdb-kernel` project is no longer proposed: it would be one more project compiled into the engine module, which adds build machinery without JPMS isolation. Step 4 now follows the engine build consolidation below; its first action is the package-boundary check. Larger historical experiment families can be refined when a concrete maintenance or teaching need warrants it. No mass namespace rename, storage-format change, second transaction/WAL authority or broad compiler/executor split is authorized by this plan.

Step 6, teachable forks (2026-10-15): besides the read trace (`readableEngineDemo`), `readableWriteDemo` follows one transaction's insert through locks, logged page changes, commit and recovery, observed through Derby's diagnostic tables, with the owners in READABLE-ENGINE.md. SOURCE-GUIDE.md describes the consolidated build (one engine module, package groups and their check), separates benchmark tooling from the core owners, and has a component table: owner, contract to keep, focused proof and broader proof for the optimizer, heap page placement, B-tree indexes, MVCC visibility and uniqueness, the lock manager, the cache, the log and recovery, and the package boundaries.

## Engine build consolidation (proposed)

Status: implemented 2026-10-15: the package-boundary check and the `storage-mvcc`, `storage-derby`, `derby-store-api` and `runtime-api` merges.

**Why.** Derby 10.17.1.0 compiles SQL, transactions, locks, access methods and RawStore together into one JPMS module, `org.apache.derby.engine`. DelosDB keeps `derby-store-api`, `storage-derby` and `storage-mvcc` as separate Gradle projects and then patches their classes into that same module: `derby-store-api` through `enginePatch`, the two storage projects through `derbyRuntimePatchElements`, all passed to `--patch-module` when the engine compiles. JPMS cannot isolate packages inside one module, so these projects add build concepts (patch configurations, per-project class directories, test-support patch lists) without runtime isolation. What they do give is compile-time enforcement: each storage project compiles only against `commons`, `runtime-api`, `annotations` and `derby-store-api`.

**Target.** One engine Gradle project, matching the one engine module, with ownership expressed by packages and enforced by an executable package-boundary check. Projects that are real deployment or JPMS boundaries stay: `commons`, `client`, `server`, `tools`, `runner`, `optionaltools`, `spi`, `annotations`. `runtime-api` was a real JPMS module (`io.github.ggeorg.delosdb.runtime.api`); after its review below it was merged too.

**Projects merged into the engine project.**

| Project | Java files | Packages | Resources | Artifact today |
|---|---|---|---|---|
| `derby-store-api` | 180 | `iapi.store.**` | none | `delosdb-derby-store-api.jar`, an assembled support artifact |
| `storage-derby` | 247 | `impl.store.**` except `impl.store.access.mvcc` | `META-INF/services/...StoreRowLocationFactory` | `delosdb-storage-derby.jar`, build-only |
| `storage-mvcc` | 32 | `impl.store.access.mvcc` | `META-INF/services/...ExternalAccessMethodProvider`, `...DelosStorageDiagnostics` | `delosdb-storage-mvcc.jar`, a required provider artifact, also merged into `derby.jar` |

Source files move with `git mv` to the same package paths under `delosdb-engine/src/main/java`, so binary names, stored format ids, `modules.properties` entries and provider class names do not change. Service files with the same path are merged into one file listing every provider; today the `derby.jar` assembly merges them, afterwards the source tree holds the merged file. `storage-derby`'s single test (`AccessMethodTransactionLifecycleTest`) moves to the engine project's tests. Generated code (the SQL parser, messages, product info) is already produced by the engine project and is unaffected.

**Package-boundary check (first, before any move).** The check replaces what the Gradle projects enforce today, and it runs on the current tree first, where it must pass by construction. It extends the existing `delosModuleDependencyBoundaryStaticAnalysis` audit with a checked manifest of package groups and allowed imports:

| Group | Packages | May import, besides `commons`, `annotations`, `spi` and the JDK |
|---|---|---|
| kernel contracts | `org.apache.derby.io`, `iapi.util`, `iapi.xml` and 16 `iapi.services` packages (formerly `runtime-api`) | nothing else in the engine |
| store contracts | `iapi.store.**` (today's `derby-store-api`) | kernel contracts |
| RawStore and heap | `impl.store.**` except `impl.store.access.mvcc`: RawStore, log, transactions, replication, and the heap, B-tree, sort and conglomerate access packages (today's `storage-derby`) | kernel contracts, store contracts |
| MVCC | `impl.store.access.mvcc` | kernel contracts, store contracts |
| services | `impl.services.**` | kernel contracts, and the engine-owned packages listed in the step 4 evidence (`catalog.UUID`, `iapi.security`, `iapi.services.jmx`, `mbeans`) until each is resolved |
| SQL and the rest of the engine | everything else | kernel contracts, store contracts, services; never RawStore, heap or MVCC implementation |

The current forbidden project edges (engine → MVCC, MVCC → engine implementation, `storage-derby` → MVCC) become forbidden group edges. The rows are derived from today's project dependencies and from the measured imports; the check, not this table, is the authority.

**Published artifacts.** `derby.jar` keeps exactly its current class list, which the consolidation must show by comparing the class lists before and after. `delosdb-storage-derby.jar` (build-only) and `delosdb-derby-store-api.jar` (assembled support) disappear unless a consumer is found. `delosdb-storage-mvcc.jar` is retired (decided 2026-10-15). The target manifest listed it as a required provider artifact and `verifyDelosRuntimeStorageProviders` checked it, yet its classes are already in `derby.jar` and the two are never on one classpath. The `ExternalAccessMethodProvider` discovery mechanism stays, for the deferred `delosdb-search-lucene` provider; the provider check verifies MVCC's registration inside `derby.jar` instead.

**What changes with the move.** `settings.gradle`; the engine build (the patch configurations go); `delosdb-runtime-artifacts.gradle` and `verifyDelosRuntimeStorageProviders`; the v1 target manifest and its forbidden edges; `module-dependency-tree.py` ownership; the path-keyed inventories (about 340 lines for `storage-derby`, 260 for `storage-mvcc` and 190 for `derby-store-api`, in the frozen Derby baseline, the modified-inherited and authored lists and the provenance files); the test-support patch lists in `delosdb-tests/build.gradle`; and the documents that name the projects (`V1-MODULE-ARCHITECTURE.md`, `ARCHITECTURE.md`, `STORAGE-ARCHITECTURE.md`, `BUILDING.md`, `CONTRIBUTING.md`, `GOVERNANCE.md`, `README.md`). The move is a relocation only: no behaviour, algorithm or format change in the same commit.

**Acceptance.** The package-boundary check passes before and after. `derby.jar`'s class list and service files are unchanged. Classpath and module-path boot, `verifyDelosRuntimeStorageProviders` (as revised), `derbyRuntimeSmoke` and `s0CloseoutVerification` pass. JDBC and DRDA (`derbyNetworkTests`), recovery (`delosRecoveryTests`), reopening existing heap and MVCC databases, and the full gates pass, with the known failures unchanged.

**Acceptance results (2026-10-15).** After the last merge, on the module path, `ij` creates and reads heap and MVCC tables embedded, and the network server serves an MVCC table to the client. Databases created by the jars of `93dc0687`, before the consolidation, with heap and MVCC tables, primary keys, unique constraints, secondary indexes, updates, deletes and a reused key, open with today's jars: every row reads correctly through each index, uniqueness is enforced, new rows are written, and the heap table passes `SYSCS_CHECK_TABLE` after a restart. Two failures occur identically with the old and the new jars on identical databases, so they predate the consolidation: `SYSCS_CHECK_TABLE` on an MVCC table with indexes throws `IllegalArgumentException` ("Bit position 1 is outside the legal range"), and in that session `SYSCS_INPLACE_COMPRESS_TABLE` reports that RawStore-backed MVCC nested update transactions are not implemented, for the heap table too.

**Order.**
1. Add the package-boundary check and its manifest; it passes on today's tree. Done: `gradle/static-analysis/delosdb-engine-package-boundaries.txt`, checked by `delosModuleDependencyBoundaryStaticAnalysis`.
2. Merge `storage-mvcc` into the engine project and retire `delosdb-storage-mvcc.jar`. Done: the 32 classes and every service file in `derby.jar` are byte-identical before and after; only the generated message files differ, by their timestamp header.
3. Merge `storage-derby`. Done, with the same byte-identical `derby.jar` result; its one unit test, `AccessMethodTransactionLifecycleTest`, now runs from the engine project's tests.
4. Merge `derby-store-api`. Done: `derby.jar`, `derbynet.jar`, `derbyoptionaltools.jar` and `derbytools.jar` are byte-identical apart from the message timestamps; `server` and `optionaltools` no longer patch the engine module when they compile. It went last: the engine's `module-info` provides heap and MVCC classes, so the engine compiles with the storage classes patched in, and those compile against `derby-store-api`. Moving it into the engine first would make each storage project and the engine need the other to compile.
5. Update the module documents and the target manifest. Done with each merge.
6. List `runtime-api`'s consumers and decide whether it stays a separate module. Done: reviewed below and merged into the engine.

### `runtime-api` review (2026-10-15)

`delosdb-runtime-api` is a real JPMS module, `io.github.ggeorg.delosdb.runtime.api`, with 138 source files in 21 packages: `org.apache.derby.io`, `iapi.util`, `iapi.xml` and 18 `iapi.services.*` packages (io, context, monitor, loader, locks, cache, compiler, property, crypto, daemon, diag, memory, security, timer, uuid, classfile), plus DelosDB's kernel contracts such as `LockReportSupport` and `LockFastPathSupport`.

- **Consumers, by imports in production code:** the engine (2,375), the tests (229), `optionaltools` (31), `server` (17), `storeless` (14), `tools` (5, a static module dependency), `buildtools` (1) and `pptesting` (1). `client` and `runner` do not use it. Its original reason, giving the storage projects contracts without the engine, ended with the consolidation.
- **Derby keeps all of these packages in `org.apache.derby.engine`.** It exports `iapi.services.io`, `iapi.services.loader` and `iapi.util` to everyone, `iapi.services.monitor` only to server, tools, optionaltools and tests, and `org.apache.derby.io` only to optionaltools and tests; the rest are not exported.
- **DelosDB exports all 21 packages to every module**, a wider surface than Derby: module-path code can reach the monitor, contexts, lock and cache contracts.
- **`derby.jar` does not contain these classes.** The engine module `requires` `runtime.api`, `annotations` and `spi`, so a Derby-compatible deployment needs `delosdb-runtime-api.jar` (and those two jars) besides `derby.jar` and `derbyshared.jar`, where Derby's `derby.jar` needs only `derbyshared.jar`.

Decision (2026-10-15): merged as recommended. `derby.jar` now holds its 1,798 previous entries plus the 144 `runtime-api` classes, byte-identical; only the `module-info` classes of `derby.jar`, `derbynet.jar`, `derbyoptionaltools.jar`, `derbyshared.jar` and `derbytools.jar` changed. The engine exports the kernel contracts with Derby's set, plus `iapi.xml` to `tools`, a DelosDB package Derby does not have; the tests reach `iapi.services.classfile` and `iapi.services.compiler` through test-only `--add-exports`. The package-boundary manifest gained a kernel-contracts group so the merged packages keep their former isolation.

Recommendation: merge `runtime-api` into the engine project and module, as Derby has it, with Derby's export set for the inherited packages and DelosDB's own contracts unexported. `derby.jar` then carries its kernel contracts again, the public module surface shrinks to Derby's, and one module concept goes. Costs: the module name `io.github.ggeorg.delosdb.runtime.api` disappears (only `server`, `tools` and `optionaltools` require it, and `commons` exports six packages to it); the generated `ClassSizeCatalogImpl` moves to the engine; `buildtools` and `storeless` compile against engine classes; module-path code that used the wider exports loses them, which matches Derby. Whether `annotations` and `spi` should stay separate runtime jars is a separate question: `spi` is a supported public extension API.

## Core query and storage dependency map

Snapshot: `e9099f63`. The inventory command above regenerates the evidence. It also reports package strongly connected components for each source owner, and imports from contract packages into implementation packages. A contract package is one without an `.impl.` segment, following the inherited Derby `iapi`/`impl` convention. These are import edges only. ServiceLoader providers, class-name lookups such as the `RowLocationServicesRegistry` fallback, generated activations and stored format ids are not counted. Each of them is a compatibility-sensitive name in its own right.

| Step of one statement | Source owner and package | Entry class | Next boundary |
|---|---|---|---|
| Embedded JDBC | engine, `impl.jdbc` | `EmbedStatement` | language connection context |
| DRDA request | server, `impl.drda` | `DRDAConnThread` | embedded JDBC (26 server → engine imports) |
| Compile: parse, bind, optimize, generate | engine, `impl.sql`, `impl.sql.compile`, `impl.services.bytecode.classfile` | `GenericStatement`, `OptimizerImpl`, `ClassFileJava`; the parser is generated from `sqlgrammar.jj` | generated activation class |
| Execute | engine, `impl.sql.execute`, `impl.sql.conn` | `BaseActivation`, `InsertResultSet`, `IndexChanger`, `GenericLanguageConnectionContext` | store contracts |
| Store contracts | engine, `iapi.store.access`, `iapi.store.raw` | `TransactionController`, `ConglomerateController` | access method chosen by conglomerate factory |
| Access methods | engine, `impl.store.access{,.heap,.btree,.mvcc}` | `RAMTransaction`, `HeapController`, `BTreeController`, `MvccRawStoreRuntime` | RawStore contracts |
| RawStore | engine, `impl.store.raw.{xact,data,log}` | `Xact`, `FileContainer`, `StoredPage`, `FileLogger`, `LogToFile` | files and the log |

Findings:

1. **The source-owner graph is acyclic and keeps the architectural constraints.** The engine reaches storage only through derby-store-api and runtime-api. It has no source edge to storage-derby or storage-mvcc, and the two storage owners share only the store contracts. Runtime packaging still merges them into the engine jar, so this is a source boundary, not a runtime module boundary.
2. **Package cycles.** The engine has one cycle of 30 packages: the SQL contracts (`iapi.sql.*`, `iapi.types`, `iapi.db`, `iapi.jdbc`, `iapi.security`), `catalog`, the SQL implementations (`impl.sql.*`, `impl.jdbc`, `impl.db`, `impl.load`), three `impl.services` packages, `vti` and the Delos `engine.extension.sql`. The smaller cycles are derby-store-api (7 access and raw contract packages), commons (4), client (3), tools (3), server (2) and the Delos `engine.extension`/`extension.cost` (2: one descriptor factory, below the review threshold of more than 3 packages). storage-derby and storage-mvcc have no package cycles.
3. **Contract-to-implementation imports in the engine.** All but two are inherited from Derby 10.17.1.0. Several are compatibility entry points that cannot be moved: `catalog.SystemProcedures` is stored by class name in the catalogs, and `iapi.jdbc.AutoloadedDriver` is the registered `java.sql.Driver`. The others are `GetProcedureColumns`, `CompilerContext` → `ParameterNode`, `LanguageConnectionContext` → `DeferredConstraintsMemory`, `OptimizerTrace` → `DefaultOptTrace`, and the public `diag` table functions. The two Delos-introduced edges were `ConsistencyChecker` and `ExecRowBuilder` importing `impl.services.storetypes.EngineStoreRowLocationBridge`.
4. **The 30-package cycle is two cycles joined by entry points.** Without the contract-to-implementation imports it would split into a 13-package contract cycle (`catalog`, `iapi.sql.*`, `iapi.types`, `iapi.db`, `iapi.jdbc`, `iapi.security`) and a 10-package implementation cycle (`impl.sql.*`, `impl.jdbc`, `impl.db`). The split needs the `SystemProcedures`, JDBC driver and three small `iapi` edges removed together, and the first two are fixed compatibility names. The `iapi`/`impl` names therefore do not describe a contract layer that can be separated by moving packages. Restructuring has to work inside the two inner cycles, starting with `impl.sql.compile` ↔ `impl.sql.execute` (30 and 8 imports).
5. **storage-derby access methods reach into RawStore internals.** There are six imports from `impl.store.access` into `impl.store.raw.data`, all Delos-introduced and all in the insert and read experiments: `RawStorePreparedRow` (RCM-1 prepared insert), `RawStoreOccupancy` (the default-off ledger), `HeapPageReadImageAccess` (experimental read image) and `StoredPageSanityChecker` (sane-build page checks). [F08-CRITICAL-PATH-OCCUPANCY.md](F08-CRITICAL-PATH-OCCUPANCY.md) freezes RCM-1 and the ledger until calibration passes. The sanity check is now behind the contract: `HeapSanityChecker` calls `Page.checkPhysicalConsistency`, which `StoredPage` implements with the now package-private `StoredPageSanityChecker`. The remaining five imports are boundary debt: two experiments and the occupancy ledger. Each needs either a store contract or retirement, and that decision waits for calibration.
6. **The engine adapts store-native row locations through two entry points.** One is the contract `iapi.types.RowLocationServicesRegistry`, which uses ServiceLoader with a class-name fallback. The other is direct calls to `EngineStoreRowLocationBridge`, at 48 call sites in 18 `impl` files. Both reach the same adapter.

**First core slice: remove the Delos-introduced contract-to-implementation imports.** `ConsistencyChecker` and `ExecRowBuilder` now adapt row locations through `RowLocationServicesRegistry`, the contract that already serves `DataValueFactoryImpl` and `SQLRef`. The adapter and its behaviour are unchanged. This slice was chosen because it is Delos-authored, has no inherited compatibility name, uses an existing contract and lies outside the frozen insert path. Rejected alternatives:

- the access-to-RawStore shortcuts, which are frozen by F08;
- the extension/cost cycle, which is below the review threshold;
- the inherited entry points, which have fixed names.

**Result.** Delos-introduced contract-to-implementation imports in the engine are now zero. The engine cycle is still 30 packages, because `impl.services.storetypes` is reached from `impl.sql.execute` and depends on `iapi.types`, and the inherited edges close that loop. The 48 implementation-side call sites keep using the bridge directly: implementation packages may depend on implementations, and rewriting them would be churn without a boundary gain.

**Compiler and executor imports, by type.** The 30 imports from `impl.sql.compile` into `impl.sql.execute` are mostly plan descriptors that the compiler builds and the executor runs: `ColumnInfo`, `ConstraintInfo`, `FKInfo`, `TriggerInfo`, the `PrivilegeInfo` family, `AggregatorInfo` and `IndexColumnOrder`. The rest are constant-action and execution factories. These descriptors are `Formatable`, and stored plans carry their format ids. The 8 imports in the other direction, all inherited, fall into three kinds:

- **Encoding constants.** `ConstraintDefinitionNode` characteristic defaults, `ColumnDefinitionNode` autoincrement modes and `IntersectOrExceptNode` operator codes.
- **The `TableName` node as a runtime value.** `SET CONSTRAINTS` and `CREATE SEQUENCE` keep it at execution time.
- **Real compilation during execution.** `AlterTableConstantAction` re-parses and re-binds trigger SQL when a column is dropped.

The third kind is a real dependency: DDL execution compiles SQL. Moving the constants would therefore not break the cycle, and it would only churn inherited files. This confirms the rule above to keep one SQL compiler and executor owner, so no slice was taken here.

**Engine services that depend on SQL.** These are the prerequisite for the candidate `delosdb-kernel`. `impl.services.cache`, `monitor`, `uuid`, `jce` and `jmx` import no SQL package. Each of the others imports SQL for a specific reason:

- `daemon` held the index-statistics daemon, which is SQL work by nature. `IndexStatisticsDaemonImpl` now lives in `impl.sql.catalog`, next to `DataDictionaryImpl`, which creates it; the generic daemon stays in `impl.services.daemon`.
- `storetypes` adapts store values to SQL types by design.
- `reflect` used one `CodeGeneration` constant, the generated-class package. That constant is now defined by `ClassFactory` in runtime-api, and `CodeGeneration` keeps it as an alias.
- `locks` was the only general kernel service that depended on SQL. `Deadlock` and `Timeout` take the `LanguageConnectionContext` from the context stack to get the current `TransactionController`, and they build `TableNameInfo`, which reads the data dictionary, to name the tables, indexes and statements in deadlock and timeout reports. `diag.LockTable` reuses `TableNameInfo`. The lock algorithm itself has no SQL dependency.

The lock manager now names lock objects through the runtime-api `LockReportSupport` contract, described below. With that and the moves of `storetypes`, the generated-class constant and the index-statistics daemon, no `impl.services` package imports the SQL layer. `uuid` uses the public `catalog.UUID` interface, a fixed Derby name. This meets the source prerequisite for a candidate `delosdb-kernel`; step 4 still needs the runtime jar, JPMS and provider checks before any Gradle module changes.

**Compatibility-sensitive names by package.** Step 2 asks for these before any move. They were counted for every package of engine, storage-derby, storage-mvcc, derby-store-api and runtime-api, from:

- the `exports` and `provides` clauses in `module-info.java`;
- the `META-INF/services` registrations;
- `modules.properties`;
- `RegisteredFormatIds`;
- string-literal class names elsewhere in production source;
- test-support patches compiled into the engine module (`delosdb-tests/build.gradle`).

A file counts as Delos-authored when it does not exist at the same path in Derby 10.17.1.0. These are the families that could be moved:

| Package | Files (Delos-authored) | Exported | Service / string / format-id names | Test patches | Persisted names |
|---|---|---|---|---|---|
| inherited SQL and RawStore packages | all inherited | most `iapi`, `catalog`, `jdbc` | many; `catalog.types`, `impl.sql.execute` and `impl.store.raw.data` carry 13-25 format ids each | `raw.data` 8, `services.locks` 6 | format ids and catalog class names |
| `impl.store.raw.data`, Delos files | 17 of 93 | no | the archived update operations carry log format ids | 8 | log records |
| `impl.store.access.mvcc` | 32 (all) | no | 5 / 2 / 2, including the `MvccRowLocation` class-name check | 5 | MVCC format ids |
| `iapi.store.types` | 82 (all) | yes | 3 / 0 / 0 | 0 | none, but it is public contract surface |
| `impl.store.access.provider` | 3 (all) | no | 4 / 0 / 0 | 0 | none |
| `impl.services.storetypes` | 3 (all) | no | 4 / 2 / 0 | 0 | none |

Inherited families carry format ids, catalog names or `modules.properties` entries, so moving them needs the compatibility mapping that step 5 of "What should change first" defers. The Delos files in `impl.store.raw.data` need package-private `StoredPage` access, and some carry log format ids.

**The first family moved is `impl.services.storetypes`.** It has the smallest compatibility surface: 3 Delos-authored files, not exported, and no persisted names. Its names appear only in ServiceLoader registrations, `module-info` `provides`, two registry class-name fallbacks, two reflective test lookups and the tests' `--add-exports`. It is also misplaced. It sits among the kernel services, but its job is adapting store-native values to SQL types (12 imports from `iapi.types`), so it moves to `org.apache.derby.impl.sql.storetypes` on the SQL side. The move is a relocation only: class names and behaviour are unchanged.

**Class-loader isolation is a compatibility-sensitive name.** A first attempt moved the package to `io.github.ggeorg.delosdb.engine.sql.storetypes`, and `ClassLoaderBootTest` failed at boot. That test models embedding applications whose class loaders load `org.apache.derby.*` themselves and delegate every other package to the parent. The parent therefore loaded the moved provider, and ServiceLoader rejected it as "not a subtype" of the child loader's `StoreTypeSupport`. In that setup, every engine class outside `org.apache.derby` comes from the parent loader, so it cannot exchange Derby types with the child's classes. Optional providers already outside it, such as `StoreCostControllerBridge`, are skipped in that setup by design (`StoreCostControllerWrappers`). Step 5 of "What should change first", the namespace migration, therefore needs this isolation contract redesigned first, or must be limited to classes shown not to exchange Derby types.

**The second family moved is `impl.store.access.provider`.** Its 3 Delos-authored files are the heap access method's provider adapters: `DerbyStoreRowLocationFactory` creates `HeapRowLocation`s, `DerbyHeapStorageDiagnostics` observes heap container files, and the package-private `DerbyHeapContainerFiles` supports it. "Provider" names a mechanism, not a responsibility, so they move to `org.apache.derby.impl.store.access.heap`. The MVCC access method already keeps its equivalent, `MvccStorageDiagnostics`, in `access.mvcc`. Their names appear only in two `module-info` `provides` clauses, two ServiceLoader registrations, the provider list checked by `delosdb-runtime-artifacts.gradle` and the static-analysis inventories. No test names them, the `heap` package already has test `--add-exports`, and the moved class names do not collide with inherited heap files. The move is a relocation only.

**The lock manager no longer imports SQL.** `Deadlock` and `Timeout` now name lock objects through `LockReportSupport`, a runtime-api contract in `iapi.services.locks`. It finds conglomerate and container ids, returns `LockObjectNames` for the table, index and table type, and lists `LockTransactionInfo` records (user and statement per transaction). The lock manager gets the language connection context by its `ContextId` and passes it as an opaque object, the pattern `InterruptStatus` already uses. The engine provides `EngineLockReportSupport` through ServiceLoader, with a class-name fallback. `TableNameInfo` moves unchanged to `impl.sql.conn` and implements `LockObjectNames`; `diag.LockTable`, which is SQL-side, keeps using it directly. Failure handling is unchanged: a report without names when the dictionary cannot be read, and no transaction details when only those fail. The report text is unchanged, and `ErrorMessageTest` and `LockTableVtiTest` check it. `impl.services.locks` then still depended on the store contracts in derby-store-api (`ContainerKey`, `ContainerLock`, `RowLock`), used only by DelosDB's lock fast paths.

**The lock manager no longer imports the store.** The container intent-shared fast path, the record-read fast path and the held intent-exclusive regrant compare a request with the store's lock modes. They now get those modes from `LockFastPathSupport`, a runtime-api contract that returns the container lockable class and the `CIS`, `CIX` and `RS2` qualifiers. `StoreLockFastPathSupport`, next to `ContainerLock` and `RowLock` in derby-store-api, provides it through ServiceLoader with a class-name fallback, and `LockFastPaths` reads it once into static finals, so the comparisons stay identity checks. Without a provider the qualifiers match no request and every lock takes the ordinary path. The lock algorithm and its diagnostics are unchanged; the diagnostics still name `ContainerKey` and `RecordId` by class-name string, which is no source edge. `ContainerIntentSharedFastPathTest`, `RecordReadFastPathTest` and `HeldIntentRegrantTest` check that the fast paths still engage.

**The cache no longer imports the store.** `CacheEntry` let entries whose identity was a `ContainerKey` be kept and released without the entry mutex, and it labelled its diagnostics by the identity's class (`PageKey`, `ContainerKey`, `Long`). Only the container cache holds `ContainerKey` entries, so the store now says so when it creates that cache: `CacheFactory.newCacheManager` takes a `lockFreeKeeps` flag, with a default that creates an ordinary cache, and `BaseDataFileFactory` passes it for the container cache only. `ConcurrentCache` gives its entries a shared `CacheEntry.Kind` with the flag and the diagnostics of the cache's name, so the diagnostics report the same rows. The keep/release algorithm is unchanged. `ContainerCacheFastPinTest` checks that a booted database's container cache keeps lock-free and its page cache does not.

**The cipher factory no longer imports the store.** `JCECipherFactory` read three service-property names from `RawStoreFactory`: the encrypted key (`encryptedBootPassword`) and the data and log encryption algorithm versions. It writes and reads those properties itself, so they are now defined on its runtime-api contract, `CipherFactory`. `RawStoreFactory` keeps its names as aliases with the same values, so stored databases and store code are unaffected.

## First implemented slices

`runDelosF08OccupancyCampaign` registration now lives in `gradle/benchmarks/rawstore-occupancy.gradle`. The test project passes its benchmark classes, Delos runtime artifacts and upstream Derby configuration explicitly. The task name, flags, output destinations, budgets and lazy external-engine resolution are retained. This is a small ownership extraction; it does not make the remaining test build or benchmark coordinator clean yet.

The parent-side forked JVM execution is now owned by package-private `BenchmarkWorkerProcess`: process creation, working-directory/output routing, bounded/unbounded wait, graceful/forced timeout termination and failure-log evidence. The coordinator supplies optional observation and performs the quiet-host entry guard. Package-private `BenchmarkWorkerCommand` now constructs JVM arguments, target-specific flags, worker properties and profiling paths. Package-private `BenchmarkConfiguration` owns the former options record, parsing/defaults, campaign constraints, transaction budgets, baseline path mapping, target cohorts and classpath selection. Shared configuration helpers move with those rules; the property names and defaults remain unchanged. Package-private `BenchmarkModel` now owns the shared target/workload/spec/endpoint types, result/evidence records, case shape keys, report headers and protocol counter definitions. Configuration and command construction no longer import coordinator-owned types. No public JDBC, storage or benchmark entry point changed.

Three process unit tests use real child JVMs and cover successful bounded/unbounded execution, argument/property forwarding, working directory, merged output, nonzero exit with a 40-line failure tail, graceful timeout and a blocking shutdown hook requiring forced termination. A small three-engine smoke exercised 12 isolated workers and 48 measurement records at 1/2 clients, with matching budgets and semantic fingerprints. These are orchestration checks, not throughput evidence. Existing interruption/observer-start failure policy was retained; DRDA/container variants and live host sampling were outside this slice's runtime checks.

Five additional command/configuration tests cover validation, transaction budgets, target classpaths and flag isolation; one runs a real child JVM to verify endpoint/property values with spaces and JFR/compilation artifacts. A disposable compiled comparison matched 480 before/after commands, 10 configuration records and 14 validation outcomes. The three-engine smoke was rerun with all 48 budgets/fingerprints matching both across engines and against the previous slice. Repository closeout and three occupancy registration preflights passed. These are structural acceptance checks, not new performance findings.

Package-private `BenchmarkWorkloadRunner` now owns child case ordering, progress output, result collection and worker CSV/oracle/protocol report writing. The runner now calls package-private `BenchmarkCaseMeasurement`. It owns fixed/adaptive warmup and measurement intervals, final oracle capture, semantic checks, accounting, database shutdown and cleanup. `BenchmarkFixtures` owns table/data construction, statistics refresh, fixture naming/constants and range-fetch setup. `BenchmarkCaseDiagnostics` owns per-case reset/snapshot operations, protocol timing/counter accumulation and evidence writers. The existing reset/capture/write ordering and primary/suppressed failure handling are preserved. No new callback framework or public API was introduced.

Package-private `BenchmarkConcurrentCase` now owns concurrent clients/readers, prepared statements, interval barriers/futures/deadlines and resource cleanup. It borrows the measurement verifier connection. Package-private `BenchmarkCaseVerification` captures the existing case-baseline references and owns verification/restoration; it neither owns nor closes the verifier. Package-private `BenchmarkWorkloadSql` owns the existing shared benchmark SQL/identifier/expected-value calculations. Execution and verification no longer import coordinator implementation helpers.

Shared F04/legacy explain, catalog and capture helpers now reside in `BenchmarkExperimentSupport`. Older experiments are grouped in `BenchmarkJoinExperiments` and `BenchmarkRelationalExperiments`, with shared experiment value contracts in `BenchmarkExperimentModel`. Their property prefixes, mode names, experiment bodies and report paths are preserved.

Parent container lifecycle/readiness/server profiling is in `BenchmarkContainerCampaign`; captured CLI command execution is in `BenchmarkParentCommand`; host/process observation and cooldown are in `BenchmarkHostObservation`; campaign validation, aggregation, manifests and evidence publication are in `BenchmarkReports`. The benchmark entry point now retains CLI routing, campaign ordering, worker launch sequencing and the calls that publish reports. It no longer owns the legacy experiment or container/report implementations. The two historical experiment families and report owner remain substantial bodies; this is responsibility separation, not a claim that every experiment is already decomposed into small components.

Three lifecycle/model tests add orthogonal ordering, evidence completeness/CSV round-trip checks and an actual failing JDBC case that leaves no completed worker reports. All 11 focused tests, the 12-worker/48-case smoke, compiled command/configuration comparisons, closeout gates and occupancy preflights passed after this extraction. Smoke budgets/fingerprints match the preceding slice.

Four additional embedded case tests qualify fixed/adaptive budgets with oracle capture, duration-cap cleanup, eight fixture variants and diagnostic math/files. All 15 focused tests passed, along with six existing concurrency architecture proofs. The 48-case smoke retains matching budgets/fingerprints across engines and against the prior slice; compiled equivalence, final closeout gates and three occupancy preflights also passed. Positive diagnostic runtime coverage in this slice is page-latch evidence; other migrated diagnostics and actual network/container/server-plan experiments were not all exercised.

Four more execution/restoration tests run repeated real intervals for insert, update, delete/reinsert, bank/order, mixed read/write and disjoint long-reader/writer workloads. Complete base/auxiliary state returns to its initial snapshot, interval fingerprints/oracles repeat, borrowed verifier ownership is retained, and a real blocked-writer timeout leaves no client threads after cleanup. All 19 focused tests plus six existing architecture proofs passed. The compiled comparisons, 48-record three-engine smoke with unchanged budgets/fingerprints, closeout gates and occupancy preflights passed again. These tests do not claim timeout restores partially committed work or qualify every optional workload mode.

Five parent/experiment tests add real captured-command timeout/output, all 16 legacy mode preflights, report completeness/semantic rejection and real EXPLAIN/catalog/capture execution. All 24 focused tests plus six architecture proofs passed. Compiled equivalence and the 48-record smoke match the prior slice, including five CSV schemas. Closeout and occupancy preflights passed. Full historical experiment matrices, actual container/network/server profiling and live host observation were not all rerun.

The benchmark/build restructuring is checkpointed, and core package moves are recorded under the dependency map. Further historical experiment subcomponents should be extracted when a concrete change or teaching need warrants them, rather than by line count. Broad package renaming should follow that baseline rather than obscure the current correctness/performance work.
