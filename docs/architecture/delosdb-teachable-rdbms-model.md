# DelosDB teachable RDBMS model

DelosDB should expose a clean theoretical database model while continuing to execute through the
inherited Derby engine and DelosDB storage providers.

The model is not a replacement engine. It is a DelosDB-owned vocabulary for teaching, tracing, and
research.

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

## Derby mapping

The model should be backed by adapters over current Derby/DelosDB objects:

| Teachable concept | Current implementation area |
|---|---|
| SQL entry | `org.apache.derby.impl.jdbc`, `org.apache.derby.impl.db` |
| Compiler / optimizer | `org.apache.derby.impl.sql.compile` |
| Execution tree | `org.apache.derby.impl.sql.execute` |
| Catalog / dictionary | `org.apache.derby.impl.sql.catalog`, `org.apache.derby.iapi.sql.dictionary` |
| SQL values/types | `org.apache.derby.iapi.types` |
| Session / transaction boundary | `org.apache.derby.iapi.sql.conn`, `org.apache.derby.iapi.transaction` |
| Storage access | `delosdb-storage-api`, `delosdb-derby-store-api`, storage bridge, storage providers |

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
How many rows flowed through the executor?
```

## Development rule

Every model object must either:

```text
1. Explain a real RDBMS concept, or
2. Adapt/observe a real Derby/DelosDB execution point.
```

Do not add model classes that are not connected to source facts or planned near-term trace points.

## First implementation pass after this study

The next implementation pass should introduce the minimal model and no-op trace API:

```text
RdbmsStage
RdbmsStatementKind
RdbmsPlanNodeKind
RdbmsStorageProviderKind
RdbmsTraceEvent
RdbmsTraceSink
RdbmsTraceRegistry
```

That pass should still avoid behavior changes. The following pass should wire one SELECT lifecycle
observation path.
