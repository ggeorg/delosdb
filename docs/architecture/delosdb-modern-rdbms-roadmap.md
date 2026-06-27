# DelosDB modern RDBMS roadmap

DelosDB's goal is to become an education- and research-friendly modern relational database system
without becoming a simplified toy database. The project should remain capable, executable, and
technically serious while making the major database-system concepts visible and testable.

The strategic direction is:

```text
Design a teachable modern RDBMS model,
prove it against real Derby/DelosDB execution,
then use that model to decide the future project layout.
```

This roadmap treats inherited Derby code as the working implementation substrate, DelosDB storage
and MVCC code as the native research subsystem, and PostgreSQL, Apache Calcite, and HerdDB as
reference architectures for designing a clear modern database model.

## Mission

DelosDB should support two audiences at the same time:

1. Students should be able to understand how a serious relational database is assembled.
2. Researchers should be able to observe, modify, and compare real database mechanisms.

The system should expose the classical RDBMS pipeline:

```text
SQL text
  -> parse
  -> bind / validate
  -> optimize
  -> physical plan
  -> execute
  -> access storage
  -> return rows or update counts
```

It should also expose modern implementation mechanisms:

```text
MVCC / snapshot visibility
WAL / durability
checkpoint / recovery
vacuum / garbage-collection horizon
storage-provider abstraction
access methods and indexes
predicate pushdown and leftover predicates
cost-based planning
transaction isolation
catalog metadata
type conversion and null semantics
diagnostics and execution tracing
```

## Current baseline

The current project layout already has several useful boundaries:

```text
delosdb-runtime-api
  low-level inherited Derby runtime/service contracts

delosdb-engine
  inherited Derby SQL engine plus DelosDB extensions

delosdb-storage-api
  provider-neutral DelosDB storage contracts

delosdb-derby-store-api
  inherited Derby store contracts

delosdb-storage-derby
  inherited Derby heap/raw/btree storage provider

delosdb-storage-mvcc
  native DelosDB MVCC storage provider

delosdb-storage-bridge
  temporary Derby access-method compatibility adapter
```

This baseline is good enough to continue. The next phase should not be a broad package rename or a
large module split. It should define a DelosDB-owned model that explains the real system and then
connect that model to real execution paths.

## Design principles

### Model first, layout second

The future project layout should be driven by proven database concepts and real source dependency
facts. A new module or package is justified only when it represents a real subsystem and can be
connected to executable behavior.

### Modern does not mean decorative

The word modern must correspond to observable mechanisms: MVCC, snapshots, WAL, checkpointing,
recovery, vacuum, storage-provider choice, predicate pushdown, cost and plan observations, and
execution row flow.

### Teachable does not mean simplified

The model should reduce conceptual noise, not capability. DelosDB should remain a serious RDBMS
while making its internals easier to study.

### Derby traceability is valuable

Inherited Derby packages should not be renamed wholesale. Keeping Derby-origin package names helps
compare DelosDB with upstream Derby. New DelosDB-owned seams and model code should use DelosDB
package names.

### No fake abstraction layers

Model objects must either explain a real RDBMS concept or adapt/observe a real Derby/DelosDB
execution point. Empty future-facing interfaces should be avoided.

### Diagnostics are research tools

Diagnostics and traces are useful when they observe real behavior. They should not become source
assertion guards or audit modules unless explicitly approved.

## Reference architecture lessons

### PostgreSQL

PostgreSQL is the strongest reference for a complete classical RDBMS layout. The useful lesson is
its clear separation of parser, rewriter, optimizer, executor, catalog, access methods, storage, and
runtime utilities.

DelosDB should adopt the stage clarity, not the C implementation or directory structure.

### Apache Calcite

Calcite is the strongest reference for a clean SQL and planning model: SQL AST, validation,
relational algebra, row expressions, planner rules, traits, costs, and adapters.

DelosDB should adopt the conceptual separation between SQL syntax, logical plan, physical plan, and
storage access. DelosDB should not replace the Derby compiler with Calcite in this phase.

### HerdDB

HerdDB is useful as a compact Java database reference. It shows how a smaller Java system can keep
model, planner, table manager, log, storage manager, scanner, and index concepts visible.

DelosDB should adopt the compact model vocabulary, not HerdDB's implementation.

## Target modern RDBMS model

The first model should live inside `delosdb-engine`, not in a new Gradle module:

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

The initial concepts should be intentionally small:

```text
RdbmsStage
RdbmsStatementKind
RdbmsPlanNodeKind
RdbmsStorageProviderKind
RdbmsStorageAccessKind
RdbmsTransactionVisibility
RdbmsTraceEvent
RdbmsTraceSink
RdbmsTraceRegistry
```

The first goal is not replacement. The first goal is explanation and observation.

## Roadmap

### Phase 20 — Teachable modern RDBMS model

Goal: define the model and connect it to real Derby/DelosDB behavior.

#### MODULE20A — Reference study and cleanup

Status: planned/created as the first cleanup and study pass.

Scope:

```text
Study PostgreSQL, Apache Calcite, and HerdDB.
Document DelosDB's teachable modern RDBMS direction.
Retire stale state files, stale dev workspace files, and obsolete scripts.
```

#### MODULE20B — Introduce the minimal model and no-op trace API

Scope:

```text
Add the first DelosDB-owned model and trace classes inside delosdb-engine.
Keep the API small.
Do not change query behavior.
Do not create a new Gradle module.
```

Expected proof:

```text
The project builds with the model present.
No execution path depends on the model yet except harmless no-op plumbing if needed.
```

#### MODULE20C — Prove SELECT lifecycle tracing

Scope:

```text
Wire one simple SELECT lifecycle to the model.
Observe statement kind, table scan, execution start, storage access, rows produced, and execution finish.
Use real Derby/DelosDB execution points.
```

Expected proof:

```text
A simple SELECT can produce a trace that explains the real path through the engine.
```

#### MODULE20D — Add storage-provider and access-method observations

Scope:

```text
Expose whether access is Derby heap/btree, Delos MVCC, storeless, or unknown.
Expose heap scan, index scan, MVCC scan, predicate pushdown, and leftover predicate concepts where available.
```

Expected proof:

```text
The same model can explain different storage-provider paths without making the bridge the architecture center.
```

#### MODULE20E — Add transaction and MVCC observations

Scope:

```text
Expose transaction identity where available.
Expose snapshot/visibility concepts for MVCC paths.
Expose WAL/checkpoint/vacuum concepts through existing MVCC diagnostics where available.
```

Expected proof:

```text
The model explains modern concurrency and durability mechanisms instead of only SQL execution.
```

#### MODULE20F — Add catalog and type adapters

Scope:

```text
Adapt Derby catalog and type objects into DelosDB model concepts:
  table
  column
  index
  SQL type
  SQL value
  null semantics
  conversion/comparison behavior
```

Expected proof:

```text
Students can inspect database metadata and SQL values through a stable DelosDB vocabulary.
```

#### MODULE20G — Project-layout decision point

Scope:

```text
Use the proven model, dependency reports, and source facts to decide the next project layout.
Possible outcomes may include delosdb-sql-types, delosdb-catalog, engine diagnostics, or keeping delosdb-engine intact longer.
```

Expected proof:

```text
Layout decisions are based on working model boundaries, not guesses or package names alone.
```

### Phase 21 — Research diagnostics and observability

Goal: make DelosDB useful for experiments without requiring full Derby internals knowledge.

Candidate passes:

```text
Query lifecycle trace output
Execution row-flow counters
Storage access observations
Optimizer decision observations
Catalog/type observations
MVCC visibility and recovery observations
```

This phase should favor runtime diagnostics over source-checking guards.

### Phase 22 — Layout evolution

Goal: evolve the project layout only after the model shows stable boundaries.

Possible future layout components:

```text
delosdb-sql-types
delosdb-catalog
delosdb-engine-runtime
delosdb-engine-diagnostics
delosdb-storage-recovery
delosdb-research-tools
```

These are candidates, not commitments. A component becomes a module only when the source dependency
direction and tests justify it.

### Phase 23 — MVCC as a first-class research subsystem

Goal: expose native MVCC as a modern storage engine, not as a patch over inherited Derby storage.

Focus areas:

```text
snapshot visibility
WAL and page LSNs
checkpoint state
vacuum horizon
version chains or page-version access
MVCC vs Derby heap comparison
```

### Phase 24 — Planning and optimizer experiments

Goal: make optimizer and planning experiments possible after the lifecycle and storage observations
exist.

Possible directions:

```text
Calcite-inspired logical plan vocabulary
predicate pushdown experiments
cost model experiments
storage-provider-aware access-path selection
MVCC-aware planning experiments
```

This phase should not start before the model can observe current Derby planning and execution.

## Reviewed risks and decisions

### Risk: building a toy model

Decision: the model must include modern mechanisms and must be connected to real execution. It is not
a simplified teaching-only database.

### Risk: creating empty architecture

Decision: no model class should exist only for future aesthetics. Each class should explain or observe
a real concept.

### Risk: losing Derby comparability

Decision: inherited Derby package names remain until a subsystem is genuinely DelosDB-owned or cleanly
separable.

### Risk: over-splitting the project

Decision: the roadmap now says future project layout, not future splits. Splitting is only one possible
layout outcome.

### Risk: making the bridge central

Decision: the bridge remains compatibility infrastructure. The model should describe storage providers
and access methods through DelosDB concepts, not through bridge internals.

## Immediate next overlay

The next implementation pass should be:

```text
MODULE20B — Introduce minimal teachable modern RDBMS model
```

It should add only the first model and trace vocabulary inside `delosdb-engine`. It should not move
packages, create new modules, rewrite compiler behavior, or add source guards.
