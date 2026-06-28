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

## Package shape

The first implementation lives inside `delosdb-engine`, not in a new Gradle module:

```text
io.github.ggeorg.delosdb.engine.trace
```

This is engine-owned internal trace vocabulary, not a public observability API and not provider SPI.
The package may describe observed storage, plan, execution, and transaction facts from the engine's
point of view, but storage modules must not depend on `delosdb-engine` in order to emit trace
events.

The package follows the DelosDB package naming strategy in
`docs/architecture/delosdb-package-naming-strategy.md`.

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


## First implementation slice

MODULE21A introduces the minimal source vocabulary only. It does not wire Derby execution to the
model yet and does not create a new Gradle module. The first classes are:

```text
io.github.ggeorg.delosdb.engine.trace.RdbmsStatementKind
io.github.ggeorg.delosdb.engine.trace.RdbmsLifecycleStage
io.github.ggeorg.delosdb.engine.trace.RdbmsPlanNodeKind
io.github.ggeorg.delosdb.engine.trace.RdbmsExecutionNodeKind
io.github.ggeorg.delosdb.engine.trace.RdbmsStorageAccessKind
io.github.ggeorg.delosdb.engine.trace.RdbmsStorageProviderKind
io.github.ggeorg.delosdb.engine.trace.RdbmsTransactionConcept
io.github.ggeorg.delosdb.engine.trace.RdbmsTraceEvent
io.github.ggeorg.delosdb.engine.trace.RdbmsTraceSink
io.github.ggeorg.delosdb.engine.trace.RdbmsTraceRegistry
```

The trace registry defaults to a no-op sink. That keeps the model behaviorally inert unless a
focused test or diagnostic tool installs a sink.

## First execution-wired proof

MODULE21B connects the model to a narrow inherited Derby SELECT path. It observes real execution
without changing planning, optimization, storage access, locking, or row production behavior.

The first wired Derby adapter is:

```text
io.github.ggeorg.delosdb.engine.trace.DerbyRdbmsTrace
```

The first real execution points are:

```text
org.apache.derby.impl.sql.GenericPreparedStatement
  emits statement lifecycle observations

org.apache.derby.impl.sql.execute.TableScanResultSet
  emits table-scan plan, storage-access, row-flow, and finish observations
```

The focused proof is:

```text
:delosdb-tests:runModernRdbmsSelectLifecycleTraceTest
```

The proof executes a normal SQL SELECT through Derby JDBC and asserts that the model observes:

```text
SQL_TEXT_RECEIVED
EXECUTION_STARTED
PHYSICAL_PLAN_CREATED
STORAGE_ACCESSED
ROWS_PRODUCED
EXECUTION_FINISHED
```


## Storage-provider and access-method proof

MODULE21C keeps the model observational and adds a focused proof for storage-provider and
access-method facts. The Derby adapter now reports storage access using the DelosDB model enums:

```text
provider      DERBY_HEAP | DERBY_BTREE | DELOS_MVCC | STORELESS | UNKNOWN
accessKind    HEAP_SCAN | BTREE_INDEX_SCAN | BTREE_KEYED_LOOKUP | MVCC_SCAN | UNKNOWN
```

The first proof observes two inherited Derby SELECT paths:

```text
ordinary table scan      -> DERBY_HEAP  / HEAP_SCAN
forced btree index scan  -> DERBY_BTREE / BTREE_INDEX_SCAN
```

The proof does not change the optimizer, costing, storage routing, or row production. It only makes
the access method chosen by inherited Derby visible through the DelosDB trace model.

Focused task:

```text
:delosdb-tests:runModernRdbmsStorageAccessTraceTest
```



## Transaction-boundary proof

MODULE21D adds the first transaction observation point. The model now observes inherited Derby
commit and rollback boundaries through `GenericLanguageConnectionContext` without changing
transaction behavior.

The focused proof is:

```text
:delosdb-tests:runModernRdbmsTransactionTraceTest
```

The proof executes normal JDBC transactions and asserts that the model observes:

```text
TRANSACTION_COMMITTED
TRANSACTION_ROLLED_BACK
```

The trace attributes identify the event as a `TRANSACTION` concept handled by the inherited
`DERBY_TRANSACTION` path. Native MVCC snapshot visibility, WAL, checkpoint, and vacuum-horizon
observations remain later work; this pass only creates the real transaction-boundary seam.

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
