# DelosDB modern RDBMS roadmap

DelosDB's goal is to become an education- and research-friendly modern relational database system
without becoming a simplified toy database. The project should remain capable, executable, and
technically serious while making the major database-system concepts visible, observable, and
testable.

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

This baseline is good enough to continue. The next architectural work should not be a broad package
rename or a large module split. It should define a DelosDB-owned model that explains the real system
and then connect that model to real execution paths.

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

Model objects must either explain a real RDBMS concept or adapt or observe a real Derby/DelosDB
execution point. Empty future-facing interfaces should be avoided.

### Diagnostics are research tools

Diagnostics and traces are useful when they observe real behavior. They should not become source
assertion guards or audit modules unless explicitly approved.

### Stability before visibility

A model that observes broken execution is not useful. Build health, inherited Derby verification,
static-analysis hardening, and dependency-report accuracy are part of the roadmap because they keep
later educational and research work grounded in a working system.

## Verification lanes

Normal roadmap overlays use the fast verification lane:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

The inherited Derby compatibility lane is slower and should be run separately:

```bash
./gradlew fullVerification
```

`fullVerification` includes inherited Derby suites and compatibility fixtures. It is not the
per-overlay development loop. The suite generates its native-authentication fixture from the current
DelosDB runtime, avoids the fixed Derby network-server port when possible, and fails fast by default
so one fatal setup failure does not cascade through thousands of tests.

## Phase 20 — Stabilization, evidence, and roadmap cleanup

Goal: make the current project baseline trustworthy enough to support the modern RDBMS model work.

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

### MODULE20D — Storage runtime wiring and dependency-report cleanup

Scope:

```text
Remove direct build/classes runtime wiring where possible.
Use declared Gradle artifacts for Derby-compatible runtime packaging.
Improve module-dependency reporting so it separates real production issues from test/demo noise.
```

### MODULE20E — MVCC static-analysis hardening

Scope:

```text
Harden MVCC native deserialization with a JEP 290 filter.
Use atomic move semantics for row-directory rewrite.
Compact terminated transaction state behind the retained visibility watermark.
Document deferred WAL/channel and concurrency work.
```

### MODULE20F — Inherited Derby verification stabilization

Scope:

```text
Keep runtime smoke checks and inherited Derby tests executable.
Fix classpath and boot-time provider issues introduced by recent refactoring.
Do not hide failures by weakening tests.
```

### MODULE20G — Residual MVCC hardening backlog

Scope:

```text
Track residual abort-retention growth until version pruning can prove safe removal.
Track native-serialization retirement as the preferred long-term format fix.
Keep WAL/group-commit and row-level concurrency as separate later lanes.
```

## Phase 21 — Minimal teachable modern RDBMS model

Goal: introduce the first DelosDB-owned model and trace vocabulary inside `delosdb-engine` without
changing query behavior.

### MODULE21A — Minimal model and no-op trace API

Status: green.

Scope:

```text
Add the first DelosDB-owned trace vocabulary inside delosdb-engine.
Keep the API small.
Do not change query behavior.
Do not create a new Gradle module.
```

Expected proof:

```text
The project builds with the model present.
No execution path depends on the model; the trace registry defaults to a no-op sink.
```

### MODULE21B — SELECT lifecycle trace proof

Status: green.

Scope:

```text
Wire one simple SELECT lifecycle to the model.
Observe statement kind, table scan, execution start, storage access, rows produced, and execution finish.
Use real Derby/DelosDB execution points.
```

Expected proof:

```text
A simple SELECT can produce a trace that explains the real path through the engine.
The focused modernRdbmsModelProof task passes without changing query semantics.
```

### MODULE21C — Storage-provider and access-method observations

Status: green.

Scope:

```text
Expose whether access is Derby heap/btree, Delos MVCC, storeless, or unknown.
Expose heap scan, btree index scan, keyed lookup, MVCC scan, predicate pushdown, and leftover predicate concepts where available.
Do not change optimizer behavior, costing, storage routing, or row production.
```

Expected proof:

```text
An ordinary SELECT reports DERBY_HEAP / HEAP_SCAN.
A forced indexed SELECT reports DERBY_BTREE / BTREE_INDEX_SCAN.
modernRdbmsModelProof includes both the lifecycle proof and storage-access proof.
```

### MODULE21D0 — Verification stabilization support pass

Status: implemented as a support pass.

Scope:

```text
Separate the fast roadmap gate from the slow inherited Derby compatibility gate.
Generate the native-authentication test fixture from the current DelosDB runtime.
Avoid fixed localhost:1527 network-server collisions in inherited Derby tests.
Fail fast by default when inherited Derby setup failures would otherwise cascade.
```

Expected proof:

```text
roadmapVerification is the normal per-overlay gate.
fullVerification remains available as a separate inherited compatibility gate.
```

### MODULE21C3 — Package naming cleanup support pass

Status: implemented as a support pass.

Scope:

```text
Remove the fake engine.rdbms package tree.
Collapse internal engine-owned trace vocabulary into io.github.ggeorg.delosdb.engine.trace.
Document the package naming strategy.
Do not create a new Gradle module.
Do not promote trace vocabulary to API or SPI.
```

Expected proof:

```text
No source imports remain for io.github.ggeorg.delosdb.engine.rdbms.*.
The old source directory is deleted.
roadmapVerification remains green.
```

### MODULE21D — Transaction and MVCC observations

Status: implemented for inherited Derby transaction boundaries; MVCC visibility/WAL/checkpoint
observations remain future native-MVCC work.

Scope:

```text
Expose user commit and rollback boundaries from the inherited Derby transaction path.
Expose transaction identity where available.
Keep snapshot/visibility, WAL, checkpoint, and vacuum observations as explicit later MVCC work.
Do not change commit, rollback, locking, logging, isolation, or storage behavior.
```

Expected proof:

```text
A committed JDBC transaction emits TRANSACTION_COMMITTED.
A rolled-back JDBC transaction emits TRANSACTION_ROLLED_BACK.
modernRdbmsModelProof includes the transaction-boundary proof.
```

## Phase 21 closeout

Before leaving Phase 21, run:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

Then run the slow inherited compatibility gate separately when needed:

```bash
./gradlew fullVerification
```

Phase 21 is closed when the model is small, package ownership is honest, the fast gate is stable,
and the inherited compatibility lane is no longer used as the per-overlay loop.

## Phase 22 — Research and education diagnostics

Status: closed after MODULE22B.

Goal: make real database behavior observable without turning diagnostics into source guards.

### MODULE22A — Human-readable trace output

Status: green.

Scope:

```text
Render already-captured modern RDBMS trace events as deterministic reader-facing text.
Use the existing focused SELECT proof path.
Do not install global diagnostics, add source guards, change execution behavior, or create a new module.
```

Expected proof:

```text
A real SELECT trace can be formatted as readable text showing statement lifecycle, storage access,
row production, and execution finish observations.
The formatter uses stable attribute ordering and escaping.
modernRdbmsModelProof includes the trace-text output proof.
```

### MODULE22B — Trace summary and row-flow counters

Status: green.

Scope:

```text
Summarize already-captured modern RDBMS trace events as reader-facing row-flow facts.
Use the existing SELECT trace stream; do not add new Derby hooks.
Expose statement kind, execution start/finish, storage provider/access kind, storage-access count,
rows produced, and optional rows-seen/filtered and transaction outcome fields.
Do not install global diagnostics, add source guards, change execution behavior, or create a new module.
```

Expected proof:

```text
A real SELECT trace can be summarized as statement/storage/row-flow facts.
Synthetic trace events prove deterministic counter aggregation without depending on a new execution path.
modernRdbmsModelProof includes the trace-summary proof.
```

Potential later passes:

```text
storage access report polish
optimizer decision observations
catalog and type observations
```

Diagnostics are successful only when they help a reader or researcher connect a visible event to a
real Derby/DelosDB execution point.

## Phase 22 closeout

Phase 22 is closed: the readable trace output and trace-summary proofs are green. Later diagnostics
remain available as future work, but they are not required before starting the layout decision phase.

Before leaving Phase 22, run:

```bash
./gradlew clean
./gradlew roadmapVerification
./scripts/module-dependency-tree.py
```

## Phase 23 — Project layout from the proven model

Status: closed as a no-move layout decision after MODULE23B and the documentation consolidation
support pass.

Goal: use the working model to decide the future project layout.

### MODULE23A — Contract ownership map

Status: green.

Scope:

```text
Classify visible API and contract surfaces by ownership and role.
Separate inherited Derby runtime/store substrate from DelosDB-owned storage contracts, SPI contracts,
engine model/diagnostic vocabulary, implementation internals, and compatibility adapters.
Do not move code, create modules, rename packages, promote engine.trace to API/SPI, or touch MVCC,
storage, optimizer, or query behavior.
```

Expected proof:

```text
docs/architecture/delosdb-contract-ownership-map.md records the current ownership map.
The map states that delosdb-runtime-api is not the home for all DelosDB contracts while it remains
the inherited Derby runtime/service substrate module.
roadmapVerification and the dependency report remain green because the pass is documentation-only.
```

### MODULE23B — Contract boundary audit

Status: green.

Scope:

```text
Audit the contract-bearing module boundaries in one pass: runtime API, Derby store API, storage API,
SPI, engine trace/model vocabulary, MVCC internals, storage bridge, storage-derby, and storage-IO.
Decide whether any boundary is dishonest enough to require a small source move.
Do not move Module 21/22 trace vocabulary into delosdb-runtime-api.
Do not create a model or diagnostics module unless real production consumers require it.
Do not rename delosdb-runtime-api, move storage contracts, promote MVCC internals, or touch query
behavior.
```

Expected proof:

```text
docs/architecture/delosdb-contract-boundary-audit.md records the one-pass boundary audit.
The audit concludes that no Module 23 source move is justified yet.
roadmapVerification and the dependency report remain green because the pass is documentation-only.
```


### MODULE23C — Documentation cleanup and consolidation support pass

Status: documentation-only support pass.

Scope:

```text
Read the current architecture and storage documents after Phase 22 and Phase 23.
Remove stale documentation-map references to files that no longer exist.
Update the modern RDBMS model document for the trace summary proof.
Consolidate the contract ownership map and boundary audit so the Phase 23 no-move decision is clear.
Do not move Java sources, create modules, rename packages, promote diagnostics, or touch query,
storage, MVCC, or optimizer behavior.
```

Expected proof:

```text
docs/README.md references only existing long-term documents.
docs/architecture/delosdb-modern-rdbms-model.md lists the trace-summary proof and class.
docs/architecture/delosdb-contract-ownership-map.md points to the resolved boundary audit.
docs/architecture/delosdb-contract-boundary-audit.md records the chosen documentation-closeout route.
roadmapVerification and the dependency report remain green because the pass is documentation-only.
```

### Phase 23 decision

The Phase 23 evidence says the current layout is honest enough to continue without a source move.
`delosdb-runtime-api` remains the inherited Derby runtime/service substrate and must not become a
general bucket for DelosDB contracts. `delosdb-storage-api` remains the DelosDB storage-provider
contract lane. `engine.trace` remains engine-owned until a real production consumer outside the
engine requires promotion.

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

## Phase 24 — MVCC research integration

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

This phase should build on the model and diagnostics rather than bypassing them. MVCC should become
visible as a modern database subsystem, not merely as an alternate storage implementation.

## Phase 25 — Optimizer and planner experiments

Goal: enable later experimentation once the model can already observe real query execution.

Possible directions:

```text
Calcite-inspired logical plan vocabulary
predicate pushdown experiments
cost model experiments
storage-provider-aware planning
MVCC-aware access path selection
```

Optimizer work should start from observation and explanation. Replacement or deep planner rewrites
should come only after the current Derby planning path is visible through the model.
