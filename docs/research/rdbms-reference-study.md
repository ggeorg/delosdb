# RDBMS reference study for DelosDB

This note records the reference architectures used to design DelosDB's teachable RDBMS model.
It is a design study, not an implementation mandate, and it does not treat any reference project
as source to copy.

## Purpose

DelosDB should become easy to read as a database system while still preserving its inherited Derby
execution behavior. The goal is to build a DelosDB-native educational model over real code instead
of renaming inherited Derby packages prematurely.

The reference projects are used this way:

| Project | What it contributes to DelosDB |
|---|---|
| PostgreSQL | Classical end-to-end RDBMS decomposition: parser, rewriter, optimizer, executor, catalog, access methods, storage, transaction/runtime utilities. |
| Apache Calcite | Clean SQL-to-relational-algebra model: SQL AST, validation, `RelNode`, `RexNode`, planner rules, traits, adapters, interpreter/runtime separation. |
| HerdDB | Compact Java database shape: model objects, planner operations, table managers, scanners, commit log, storage manager, indexes, SQL translation through Calcite. |

## PostgreSQL lesson

PostgreSQL is useful because its source tree makes the whole system visible as large database
subsystems:

```text
src/backend/parser
src/backend/rewrite
src/backend/optimizer
src/backend/executor
src/backend/catalog
src/backend/access
src/backend/storage
src/backend/tcop
src/backend/nodes
src/backend/utils
```

The key educational idea is not the C code. The key idea is the separation of internal trees and
stages:

```text
SQL text
  -> parse tree
  -> analyzed query tree
  -> rewritten query tree
  -> path/search space
  -> physical plan tree
  -> executor state tree
```

DelosDB should adopt the stage clarity, not PostgreSQL's implementation.

## Calcite lesson

Calcite is useful because it separates SQL syntax, relational algebra, planner rules, and adapters.
The relevant package groups are:

```text
org.apache.calcite.sql
org.apache.calcite.sql2rel
org.apache.calcite.rel
org.apache.calcite.rex
org.apache.calcite.plan
org.apache.calcite.schema
org.apache.calcite.adapter
org.apache.calcite.interpreter
org.apache.calcite.runtime
```

The key lesson for DelosDB:

```text
SQL syntax is not the logical plan.
The logical plan is not the physical plan.
The physical plan is not the storage engine.
Storage providers are adapters/access methods, not the SQL engine itself.
```

This fits DelosDB because the storage split already creates a real provider seam between inherited
Derby storage and native MVCC storage.

## HerdDB lesson

HerdDB is useful because it is Java and compact. Its source shows a small database model without the
full weight of Derby's inherited package history:

```text
herddb.model
herddb.model.planner
herddb.core
herddb.storage
herddb.log
herddb.index
herddb.sql
```

The useful idea is to keep clean model concepts even when the implementation has multiple managers
and adapters underneath:

```text
Statement
ExecutionPlan
PlannerOp
TableManager
DataScanner
CommitLog
StorageManager
IndexManager
```

DelosDB should use that lesson by introducing small DelosDB-owned model and trace objects first.

## What DelosDB should adopt

DelosDB should adopt these concepts:

```text
1. Clear query lifecycle stages.
2. Explicit logical-plan and physical-plan vocabulary.
3. Catalog/type/storage concepts visible as teachable database objects.
4. Storage provider/access-method vocabulary.
5. Trace/observation hooks for research.
6. Adapter classes that map inherited Derby objects into DelosDB concepts.
```

## What DelosDB should not copy

DelosDB should not copy these approaches blindly:

```text
1. Do not rewrite Derby into PostgreSQL-style directories.
2. Do not replace Derby's compiler with Calcite now.
3. Do not turn the model into a large interface hierarchy before it observes real execution.
4. Do not create empty modules for future concepts.
5. Do not lose Derby package traceability until a subsystem is genuinely DelosDB-owned.
```

## First DelosDB proof

The first executable proof should be a SELECT lifecycle trace:

```text
SQL text received
statement kind identified
compilation/optimization observed
physical table scan observed
storage provider/access method observed
rows produced
execution finished
```

That proof should be implemented using real Derby/DelosDB execution points. The model must explain
real behavior from the beginning.
