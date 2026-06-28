# DelosDB teachable modern RDBMS model

DelosDB should expose a clear modern database model while continuing to execute through inherited
Derby engine code and DelosDB storage providers. The model is not a replacement engine. It is a
DelosDB-owned vocabulary for education, tracing, research, and future project-layout decisions.

The strategic direction is:

```text
Design a teachable modern RDBMS model,
prove it against real Derby/DelosDB execution,
then use that model to decide the future project layout.
```

## Mission

DelosDB should be education- and research-friendly without becoming a simplified toy database. It
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
TRANSACTION_COMMITTED
TRANSACTION_ROLLED_BACK
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
UNKNOWN
```

### Execution node kinds

```text
STATEMENT
TABLE_SCAN
INDEX_SCAN
UNKNOWN
```

### Storage provider kinds

```text
DERBY_HEAP
DERBY_BTREE
DELOS_MVCC
STORELESS
UNKNOWN
```

### Storage access kinds

```text
HEAP_SCAN
BTREE_INDEX_SCAN
BTREE_KEYED_LOOKUP
MVCC_SCAN
INSERT
UPDATE
DELETE
PREDICATE_PUSHDOWN
LEFTOVER_PREDICATE
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

These concepts do not all need complete implementation in the first pass. They remain part of the
model so DelosDB does not stop at a classical SQL pipeline.

## Implementation status

The model document names both implemented observations and future concepts. The current status is:

| Area | Status | Current proof or future phase |
| --- | --- | --- |
| SELECT lifecycle | Implemented. | Module 21B focused proof. |
| Heap table scan observation | Implemented. | Module 21C storage-access proof. |
| Btree index scan observation | Implemented. | Module 21C storage-access proof. |
| Row-flow counters | Implemented for captured SELECT traces. | Module 22B trace-summary proof. |
| Commit boundary observation | Implemented for inherited Derby transaction boundaries. | Module 21D transaction proof. |
| Rollback boundary observation | Implemented for inherited Derby transaction boundaries. | Module 21D transaction proof. |
| Human-readable trace text | Implemented for captured trace events. | Module 22A trace-text proof. |
| Transaction snapshot | Implemented as a native-MVCC internal observation proof. | Module 24A MVCC observation proof. |
| Visible rows / visibility checks | Implemented as a native-MVCC internal observation proof. | Module 24A MVCC observation proof. |
| Vacuum horizon | Implemented as a native-MVCC internal observation through oldest retained visibility. | Module 24A MVCC observation proof. |
| Page/version access | Partly implemented as physical-version counts, not page-level access. | Module 24A internal observation; page-level detail remains later. |
| WAL/log position | Future native-MVCC internal observation; explicitly not observed by the in-memory path. | Later Phase 24. |
| Checkpoint state | Future native-MVCC internal observation; explicitly not observed by the in-memory path. | Later Phase 24. |

This distinction prevents the model from overclaiming. The vocabulary can describe modern RDBMS
concepts before every concept is wired to a real execution point, but diagnostics are considered
implemented only when a focused proof observes real Derby/DelosDB behavior.

## Implementation package

The current classes are:

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
io.github.ggeorg.delosdb.engine.trace.RdbmsTraceFormatter
io.github.ggeorg.delosdb.engine.trace.RdbmsTraceSummary
io.github.ggeorg.delosdb.engine.trace.DerbyRdbmsTrace
```

The trace registry defaults to a no-op sink. That keeps the model behaviorally inert unless a
focused test or diagnostic tool installs a sink. The formatter and summary classes are
diagnostic-only: they render or aggregate already-captured events and do not subscribe to traces or
affect execution.

## Wired inherited Derby execution points

The first real execution points are:

```text
org.apache.derby.impl.sql.GenericPreparedStatement
  emits statement lifecycle observations

org.apache.derby.impl.sql.execute.TableScanResultSet
  emits table-scan plan, storage-access, row-flow, and finish observations

org.apache.derby.impl.sql.conn.GenericLanguageConnectionContext
  emits inherited Derby user commit and rollback boundary observations
```

These hooks observe real Derby/DelosDB behavior. They do not change planning, optimization, storage
access, locking, row production, commit, rollback, isolation, WAL, or recovery behavior.

## Focused proofs

The focused model proof task is:

```text
./gradlew modernRdbmsModelProof
```

It includes:

```text
:delosdb-tests:runModernRdbmsSelectLifecycleTraceTest
:delosdb-tests:runModernRdbmsStorageAccessTraceTest
:delosdb-tests:runModernRdbmsTransactionTraceTest
:delosdb-tests:runModernRdbmsTraceTextOutputTest
:delosdb-tests:runModernRdbmsTraceSummaryTest
```

The focused proofs verify:

```text
simple SELECT lifecycle observations
ordinary heap table scan observations
forced btree index scan observations
commit boundary observations
rollback boundary observations
human-readable trace output for captured SELECT events
reader-facing trace summary and row-flow counters for captured SELECT events
```

## Verification role

Normal roadmap overlays should use:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

The inherited Derby compatibility suite remains available through:

```bash
./gradlew fullVerification
```

The slow inherited suite is not the per-overlay development loop.
