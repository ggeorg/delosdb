# DelosDB teachable modern RDBMS model

DelosDB should expose a clear modern database model while continuing to execute through inherited
Derby engine code and DelosDB storage-provider proofs. The model is not a replacement engine. It is
a DelosDB-owned vocabulary and contract shape for education, tracing, research, and future
project-layout decisions.

The strategic direction is:

```text
Design a teachable modern RDBMS model,
prove it against real Derby/DelosDB execution,
then use that model to decide the future project layout.
```

## Mission

DelosDB should be education- and research-friendly without becoming a simplified toy database. It
should make real database mechanisms visible while remaining a Derby-compatible database kernel with
selected internal modernization proofs.

For students, the model should explain the classical SQL pipeline:

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

For research, the same model should expose modern implementation mechanisms:

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

## Model is not trace

Trace is an observation mechanism. It is not an RDBMS building block.

The DelosDB engine model should be visible as model vocabulary first. Trace events should record
facts about that model from real Derby/DelosDB execution points. Diagnostics should render or
summarize those recorded facts for readers.

The intended in-module package shape is:

```text
io.github.ggeorg.delosdb.engine.model
  DelosDB-owned RDBMS building-block vocabulary and small contracts

io.github.ggeorg.delosdb.engine.trace
  event capture, sinks, registries, and Derby execution hooks that observe the model

io.github.ggeorg.delosdb.engine.diagnostics
  reader-facing formatters, summaries, and observed-plan reports derived from captured events
```

All three packages remain internal to `delosdb-engine` unless a real production consumer proves that
a lower/shared module or public API is needed. This is not a move to `delosdb-runtime-api`, not a
provider SPI, and not a new Gradle module.

Current correction note:

```text
The previous documentation drifted by describing io.github.ggeorg.delosdb.engine.trace as the
implementation home of the modern RDBMS model. That is not the intended architecture. engine.trace is
only the observation layer. MODULE25B restores the documented model/trace/diagnostics separation
inside delosdb-engine.
```

## Core RDBMS building blocks

A teachable model should make these building blocks visible, even when some are still only mapped to
inherited Derby source or future proofs:

| Building block | Meaning in DelosDB |
| --- | --- |
| SQL language surface | Statement kinds, predicates, expressions, type behavior, and result/update shape. |
| Catalog / dictionary | Tables, columns, indexes, schemas, constraints, and metadata lookup. |
| Planner / optimizer | Logical intent, physical access path, predicates, cost/choice observations. |
| Execution engine | Runtime operators, scans, filters, joins, row flow, and statement lifecycle. |
| Storage access | Provider choice, heap scan, btree/index access, MVCC scan, page/version access. |
| Transactions / concurrency | Transaction identity, commit, rollback, isolation, locks, snapshots. |
| MVCC / versioning | Snapshots, visible versions, retained visibility horizon, version pruning/vacuum concepts. |
| Durability / recovery | WAL/log state, checkpoint state, durable replay/recovery concepts. |
| Diagnostics / observability | Trace events, summaries, reports, and teaching/research views built from real execution. |

## Engine model vocabulary

The first engine-owned model vocabulary should live under:

```text
io.github.ggeorg.delosdb.engine.model
```

The current vocabulary includes these concept groups.

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

### Transaction and recovery concepts

```text
TRANSACTION
COMMIT
ROLLBACK
SNAPSHOT
VISIBILITY_CHECK
WAL_POSITION
CHECKPOINT
VACUUM_HORIZON
```

These concepts do not all have complete implementation yet. They are the vocabulary DelosDB uses to
explain and measure the real system without claiming that every future mechanism is already wired to
SQL execution.

## Trace layer

Trace belongs under:

```text
io.github.ggeorg.delosdb.engine.trace
```

Its role is to record model facts emitted from real execution points. It should contain event
capture machinery such as trace events, trace sinks, registries, and Derby hook helpers. It should
not become the container for the model vocabulary itself.

The trace registry defaults to a no-op sink. That keeps the model behaviorally inert unless a
focused test or diagnostic tool installs a sink.

## Diagnostics layer

Diagnostics belong under:

```text
io.github.ggeorg.delosdb.engine.diagnostics
```

Diagnostics are reader-facing views over already-captured observations: text formatting, summaries,
row-flow counters, and observed-plan reports. They do not subscribe to traces globally and do not
affect planning, execution, costing, storage routing, or row production.

## Implementation status

The model document names both implemented observations and future concepts. The detailed Phase 24
MVCC status and non-claims are recorded in
`docs/architecture/delosdb-mvcc-observation-matrix.md`.

| Area | Status | Current proof or future phase |
| --- | --- | --- |
| SELECT lifecycle | Implemented as real execution observations. | Module 21B focused proof. |
| Heap table scan observation | Implemented. | Module 21C storage-access proof. |
| Btree index scan observation | Implemented. | Module 21C storage-access proof. |
| Row-flow counters | Implemented for captured SELECT traces. | Module 22B trace-summary proof. |
| Observed table access plan | Implemented as a diagnostic baseline over captured SELECT traces. | Module 25A observed-plan proof; not an optimizer replacement. |
| Commit boundary observation | Implemented for inherited Derby transaction boundaries. | Module 21D transaction proof. |
| Rollback boundary observation | Implemented for inherited Derby transaction boundaries. | Module 21D transaction proof. |
| Human-readable trace text | Implemented for captured trace events. | Module 22A trace-text proof. |
| Model/trace/diagnostics package separation | Required correction. | Module 25B restores the intended internal boundary. |
| Transaction snapshot | Observed as a native-MVCC internal proof. | Module 24A; not wired to inherited Derby SQL transactions. |
| Visible rows / visibility checks | Observed for native and page-backed MVCC proof paths. | Modules 24A and 24B; not SQL routing. |
| Vacuum horizon | Observed as visibility-horizon diagnostics through retained snapshot state. | Module 24A; not a vacuum implementation. |
| Page/version access | Observed as selected page-backed and page-volume MVCC internal facts. | Modules 24B and 24C; no page-file parsing claim. |
| WAL/log position | Not implemented as a real replay position. Page-volume observation reports write-ahead-log file state only. | Module 24C; future work for replay position. |
| Checkpoint state | Observed as selected page-volume checkpoint status: `WRITTEN` after rewrite and `VALID` after reopen/validation. | Module 24C; no scheduler or production recovery policy. |

This distinction prevents the model from overclaiming. A concept can belong to the model before the
project has a complete implementation, but diagnostics are considered implemented only when a
focused proof observes real Derby/DelosDB behavior.

## Phase 24 MVCC observation closeout

The MVCC observation proofs intentionally remain inside `delosdb-storage-mvcc`. They expose selected
internal facts for research and education, but they do not promote MVCC internals into public API and
do not change the Derby-compatible SQL/JDBC surface.

Current non-claims:

```text
no production MVCC storage replacement
no SQL routing to page-volume MVCC
no WAL replay position
no group commit
no checkpoint scheduler
no vacuum or version-pruning implementation
no optimizer/provider-aware MVCC planning
```

## Current source placement before MODULE25B

Before the Module 25B correction, some model vocabulary, trace machinery, and diagnostics may still
physically live together under `io.github.ggeorg.delosdb.engine.trace`. That placement is legacy
roadmap drift, not the intended model boundary.

The intended correction is:

```text
engine.model
  RdbMS concept vocabulary and small contracts

engine.trace
  trace event, sink, registry, and Derby hook helpers

engine.diagnostics
  formatter, summary, and observed-plan report classes
```

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

It includes the focused proofs for:

```text
simple SELECT lifecycle observations
ordinary heap table scan observations
forced btree index scan observations
commit boundary observations
rollback boundary observations
human-readable trace output for captured SELECT events
reader-facing trace summary and row-flow counters for captured SELECT events
observed table access plan summary for heap and forced-index SELECT events
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
