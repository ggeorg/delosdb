# DelosDB modern RDBMS roadmap

DelosDB's goal is to become an education- and research-friendly modern relational database system
without becoming a simplified toy database.  The project should remain capable, executable, and
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

## Current baseline

The current project already has useful subsystem boundaries:

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

This baseline is good enough to continue.  The next phase should not be a broad package rename or a
large module split.  It should define a DelosDB-owned model that explains the real system and then
connect that model to real execution paths.

## Design principles

### Model first, layout second

The future project layout should be driven by proven database concepts and real source dependency
facts.  A new module or package is justified only when it represents a real subsystem and can be
connected to executable behavior.

### Modern does not mean decorative

The word modern must correspond to observable mechanisms: MVCC, snapshots, WAL, checkpointing,
recovery, vacuum, storage-provider choice, predicate pushdown, cost and plan observations, and
execution row flow.

### Teachable does not mean simplified

The model should reduce conceptual noise, not capability.  DelosDB should remain a serious RDBMS
while making its internals easier to study.

### Derby traceability is valuable

Inherited Derby packages should not be renamed wholesale.  Keeping Derby-origin package names helps
compare DelosDB with upstream Derby.  New DelosDB-owned seams and model code should use DelosDB
package names.

### No fake abstraction layers

Model objects must either explain a real RDBMS concept or adapt or observe a real Derby/DelosDB
execution point.  Empty future-facing interfaces should be avoided.

### Diagnostics are research tools

Diagnostics and traces are useful when they observe real behavior.  They should not become source
assertion guards or audit modules unless explicitly approved.

## Phase 20 — Teachable modern RDBMS model

Goal: define the model and connect it to real Derby/DelosDB behavior.

### MODULE20A — Reference study and cleanup

Scope:

```text
Study PostgreSQL, Apache Calcite, and HerdDB.
Document DelosDB's teachable modern RDBMS direction.
Retire stale state files, stale dev workspace files, and obsolete scripts.
```

### MODULE20B — Modern RDBMS roadmap

Scope:

```text
Record the strategic direction and reviewed project rules.
Keep the project layout decision downstream of the proven model.
```

### MODULE20C — Documentation consolidation

Scope:

```text
Consolidate overlapping roadmap, model, engine-map, SQL-types, and reference-study notes.
Keep book/reference documentation.
Remove per-overlay state markdown and stale cleanup/dev artifacts.
```

### MODULE20D — Introduce the minimal model and no-op trace API

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

### MODULE20E — Prove SELECT lifecycle tracing

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

### MODULE20F — Add storage-provider and access-method observations

Scope:

```text
Expose whether access is Derby heap/btree, Delos MVCC, storeless, or unknown.
Expose heap scan, index scan, MVCC scan, predicate pushdown, and leftover predicate concepts where available.
```

Expected proof:

```text
The same model can explain different storage-provider paths without making the bridge the architecture center.
```

### MODULE20G — Add transaction and MVCC observations

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

## Phase 21 — Research and education diagnostics

Goal: make real database behavior observable without turning diagnostics into source guards.

Potential passes:

```text
query lifecycle trace output
execution row-flow counters
storage access observations
optimizer decision observations
catalog and type observations
```

## Phase 22 — Project layout from proven model

Goal: use the working model to decide the future project layout.

Possible future layout areas:

```text
SQL types
catalog / dictionary
planner / optimizer model
execution observations
engine runtime implementations
transaction / recovery concepts
storage provider APIs and implementations
research diagnostics and examples
```

A physical module should be created only when the dependency direction supports it and the module
represents real code, not a placeholder.

## Phase 23 — MVCC research integration

Goal: make native MVCC a first-class modern RDBMS research subsystem.

Expose:

```text
transaction snapshot
visible rows
WAL/log position
checkpoint state
vacuum horizon
page/version access
```

## Phase 24 — Optimizer and planner experiments

Goal: enable later experimentation once the model can already observe real query execution.

Possible directions:

```text
Calcite-inspired logical plan vocabulary
predicate pushdown experiments
cost model experiments
storage-provider-aware planning
MVCC-aware access path selection
```
