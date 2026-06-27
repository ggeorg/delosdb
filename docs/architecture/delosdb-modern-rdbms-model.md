# DelosDB teachable modern RDBMS model

DelosDB should expose a clear modern database model while continuing to execute through inherited
Derby engine code and DelosDB storage providers.  The model is not a replacement engine.  It is a
DelosDB-owned vocabulary for education, tracing, research, and future project-layout decisions.

The strategic direction is:

```text
Design a teachable modern RDBMS model,
prove it against real Derby/DelosDB execution,
then use that model to decide the future project layout.
```

## Mission

DelosDB should be education- and research-friendly without becoming a simplified toy database.  It
should make real database mechanisms visible while remaining a serious, fully capable, modern RDBMS.

For students, the system should explain the classical SQL pipeline:

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

For research, the same model must expose modern implementation mechanisms:

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

The first implementation should live inside `delosdb-engine`, not in a new Gradle module:

```text
io.github.ggeorg.delosdb.engine.rdbms.model
io.github.ggeorg.delosdb.engine.rdbms.pipeline
io.github.ggeorg.delosdb.engine.rdbms.plan
io.github.ggeorg.delosdb.engine.rdbms.catalog
io.github.ggeorg.delosdb.engine.rdbms.types
io.github.ggeorg.delosdb.engine.rdbms.execution
io.github.ggeorg.delosdb.engine.rdbms.storage
io.github.ggeorg.delosdb.engine.rdbms.transaction
io.github.ggeorg.delosdb.engine.rdbms.recovery
io.github.ggeorg.delosdb.engine.rdbms.trace
io.github.ggeorg.delosdb.engine.rdbms.derby
```

Do not create separate Gradle modules for these packages yet.  The first goal is explanation and
observation, not replacement.

## Core model concepts

### Pipeline stages

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

These concepts do not all need complete implementation in the first pass.  They remain part of the
model so DelosDB does not stop at a classical SQL pipeline.

## Mapping to the current system

The model should be backed by adapters over current Derby/DelosDB objects:

| Teachable modern concept | Current implementation area |
|---|---|
| SQL entry | `org.apache.derby.impl.jdbc`, `org.apache.derby.impl.db` |
| Compiler / optimizer | `org.apache.derby.impl.sql.compile` |
| Execution tree | `org.apache.derby.impl.sql.execute` |
| Catalog / dictionary | `org.apache.derby.impl.sql.catalog`, `org.apache.derby.iapi.sql.dictionary` |
| SQL values and types | `org.apache.derby.iapi.types` |
| Session / transaction boundary | `org.apache.derby.iapi.sql.conn`, `org.apache.derby.iapi.transaction` |
| Storage access | `delosdb-storage-api`, `delosdb-derby-store-api`, storage bridge, storage providers |
| MVCC / visibility | `delosdb-storage-mvcc` and storage diagnostics |
| WAL / checkpoint / vacuum | Native MVCC diagnostics and storage recovery code where available |

## Research seams

The first research seam should be observation, not replacement:

```text
RdbmsTraceEvent
RdbmsTraceSink
RdbmsTraceRegistry
```

A SELECT trace should eventually answer:

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

## Development rules

Every model object must either:

```text
1. Explain a real modern RDBMS concept, or
2. Adapt or observe a real Derby/DelosDB execution point.
```

Do not add model classes that are not connected to source facts or planned near-term trace points.
Do not use the model as a reason to create empty future modules.
