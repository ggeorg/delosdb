# DelosDB teachable modern RDBMS model

DelosDB should expose a clean modern database model while continuing to execute through the
inherited Derby engine and DelosDB storage providers.

The model is not a replacement engine. It is a DelosDB-owned vocabulary for teaching, tracing,
research, and future project-layout decisions.

The strategic direction is:

```text
Design a teachable modern RDBMS model,
prove it against real Derby/DelosDB execution,
then use that model to decide the future project layout.
```

## Target mental model

```text
SQL client / JDBC
  -> SQL text
  -> parse
  -> bind / validate
  -> optimize
  -> physical plan
  -> execution tree
  -> storage access
  -> rows / update counts
```

That classical pipeline is necessary but not sufficient. DelosDB should also expose modern database
mechanisms:

```text
MVCC and snapshot visibility
WAL and durable recovery
checkpointing
vacuum / garbage-collection horizon
storage-provider selection
access methods and indexes
predicate pushdown and leftover predicates
cost-based planning
transaction isolation
catalog metadata
SQL type conversion, comparison, and null semantics
runtime diagnostics and tracing
```

## Initial package shape

The first implementation should live inside `delosdb-engine`, not in a new module:

```text
io.github.ggeorg.delosdb.engine.rdbms.model
io.github.ggeorg.delosdb.engine.rdbms.pipeline
io.github.ggeorg.delosdb.engine.rdbms.plan
io.github.ggeorg.delosdb.engine.rdbms.catalog
io.github.ggeorg.delosdb.engine.rdbms.types
io.github.ggeorg.delosdb.engine.rdbms.execution
io.github.ggeorg.delosdb.engine.rdbms.storage
io.github.ggeorg.delosdb.engine.rdbms.transaction
io.github.ggeorg.delosdb.engine.rdbms.trace
io.github.ggeorg.delosdb.engine.rdbms.derby
```

Do not create separate Gradle modules for these packages yet.

## Core concepts

### Pipeline

```text
SQL_TEXT_RECEIVED
PARSED
BOUND
OPTIMIZED
PHYSICAL_PLAN_CREATED
EXECUTION_STARTED
STORAGE_ACCESSED
ROWS_PRODUCED
EXECUTION_FINISHED
```

### Statement kinds

```text
SELECT
INSERT
UPDATE
DELETE
CREATE_TABLE
DROP_TABLE
UNKNOWN
```

### Plan node kinds

```text
TABLE_SCAN
INDEX_SCAN
FILTER
PROJECT
JOIN
SORT
AGGREGATE
VALUES
INSERT
UPDATE
DELETE
```

### Storage provider kinds

```text
DERBY_HEAP
DERBY_BTREE
DELOS_MVCC
STORELESS
UNKNOWN
```

### Modern transaction and recovery concepts

```text
TRANSACTION
SNAPSHOT
VISIBILITY_CHECK
WAL_POSITION
CHECKPOINT
VACUUM_HORIZON
```

These do not all need full implementation in the first pass. They must, however, remain part of the
model's direction so the system does not stop at a classical SQL pipeline.

## Derby mapping

The model should be backed by adapters over current Derby/DelosDB objects:

| Teachable modern concept | Current implementation area |
|---|---|
| SQL entry | `org.apache.derby.impl.jdbc`, `org.apache.derby.impl.db` |
| Compiler / optimizer | `org.apache.derby.impl.sql.compile` |
| Execution tree | `org.apache.derby.impl.sql.execute` |
| Catalog / dictionary | `org.apache.derby.impl.sql.catalog`, `org.apache.derby.iapi.sql.dictionary` |
| SQL values/types | `org.apache.derby.iapi.types` |
| Session / transaction boundary | `org.apache.derby.iapi.sql.conn`, `org.apache.derby.iapi.transaction` |
| Storage access | `delosdb-storage-api`, `delosdb-derby-store-api`, storage bridge, storage providers |
| MVCC / visibility | `delosdb-storage-mvcc` and storage diagnostics |
| WAL / checkpoint / vacuum | native MVCC diagnostics and storage recovery code where available |

## Research seams

The first research seam should be observation, not replacement:

```text
RdbmsTraceEvent
RdbmsTraceSink
RdbmsTraceRegistry
```

A later SELECT trace should answer:

```text
What SQL statement was executed?
What kind of statement was it?
What tables were involved?
What physical access was chosen?
Which storage provider handled the access?
Were predicates pushed down or evaluated as leftovers?
How many rows flowed through the executor?
Which transaction or visibility boundary applied?
```

## Development rule

Every model object must either:

```text
1. Explain a real modern RDBMS concept, or
2. Adapt/observe a real Derby/DelosDB execution point.
```

Do not add model classes that are not connected to source facts or planned near-term trace points.
Do not use the model as a reason to create empty future modules.

## First implementation pass after this study

The next implementation pass should introduce the minimal model and no-op trace API:

```text
RdbmsStage
RdbmsStatementKind
RdbmsPlanNodeKind
RdbmsStorageProviderKind
RdbmsStorageAccessKind
RdbmsTraceEvent
RdbmsTraceSink
RdbmsTraceRegistry
```

That pass should still avoid behavior changes. The following pass should wire one SELECT lifecycle
observation path.
