
# DuckDB and DelosDB: Source-Architecture Comparison

## Scope

This comparison is based on the inspected DuckDB and DelosDB source trees. It identifies lessons
for DelosDB v1.0; it does not define feature parity with DuckDB.

## Product positions

| Area | DuckDB | DelosDB |
|---|---|---|
| Primary workload | in-process analytical SQL | Derby-compatible general RDBMS |
| Execution | vectorized operators and pipelines | generated activations and result-set trees |
| Storage | one principal database storage architecture | Derby heap plus optional MVCC |
| Access | embedded APIs and integrations | embedded JDBC and Derby-compatible DRDA |
| Compatibility | DuckDB formats and APIs | Derby applications, heap formats, JDBC, catalogs, DRDA |
| Research value | modern analytical execution and extensibility | end-to-end compatibility, transactions, storage, recovery, and inspection |

## Most important lesson: explicit database ownership

DuckDB's `AttachedDatabase` owns its catalog, storage manager, transaction manager, validity, and
close lifecycle. DelosDB must provide the same clarity for MVCC runtime state.

The DelosDB v1 target is one `MvccDatabaseRuntime` per database. It owns the storage registry,
maintenance, backup coordination, transaction coordination, diagnostics, and shutdown. Mutable
process-global database identity is prohibited.

## Transaction lesson

DuckDB allows a transaction to read several attached databases but rejects writes to more than one
non-temporary attached database. Within one modified database, one transaction manager owns all
table changes.

DelosDB currently has a different product requirement: supported mixed heap/MVCC writes must be
failure-atomic. Until one database-level decision protocol exists, unsafe combinations must reject
before mutation.

## SQL pipeline and observability

DuckDB presents explicit parser, planner, optimizer, physical-plan, executor, transaction, and
storage source areas. `EXPLAIN` and profiling are first-class structures with deterministic
renderers.

DelosDB retains Derby's compiler, optimizer, generated activation, and result-set execution. It
should adopt an equally explicit read-only plan and profiling model without creating a second
optimizer or executor.

## Source organization

DuckDB assembles subsystem-owned source libraries into one principal library. This demonstrates
that readable source anatomy does not require one distribution artifact or runtime module per
conceptual subsystem.

DelosDB therefore maintains separate artifact, Gradle ownership, and JPMS graphs. Source projects
are split only where dependency analysis proves a stable ownership boundary.

## Verification

DuckDB's SQLLogicTest corpus, fuzzing, backward-compatibility tests, and statement round-trip
verification are useful reference practices. DelosDB should strengthen declarative SQL tests,
metamorphic testing, format compatibility, and structured plan/diagnostic verification while
preserving its existing crash, concurrency, heap/MVCC differential, and DRDA lanes.

## Deliberate non-adoption

DelosDB v1 does not adopt:

```text
vectorized analytical execution
parallel OLAP pipeline architecture
DuckDB SQL-dialect breadth
single-file storage redesign
broad extension ecosystem
analytical file-format specialization
```

The comparison strengthens DelosDB's existing position: a complete compatibility-oriented
relational system designed for end-to-end inspection, controlled experimentation, and durable
correctness.
