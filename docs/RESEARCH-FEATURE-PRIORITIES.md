
# DelosDB Research Feature Priorities

## Principle

Product correctness is not a research feature. Explicit database ownership, failure-atomic supported
transactions, truthful isolation and type behavior, and secure defaults are prerequisites.

DelosDB v1 then provides a small research foundation that strengthens the product itself:

```text
immutable structured observability snapshots
deterministic fault injection and replay
storage-aware EXPLAIN ANALYZE
reproducible experiment and benchmark manifests
```

## Why these four belong in v1

They serve existing product obligations:

```text
snapshots
    replace broad test forwarding and provide authoritative diagnostics

fault injection and replay
    prove transaction, recovery, checkpoint, allocation, index, overflow, and maintenance boundaries

storage-aware EXPLAIN ANALYZE
    connects optimizer and execution decisions to visibility, pages, buffers, versions, and commits

experiment manifests
    preserve environment, workload, schedule, invariant, semantic checksum, and final-state digest
```

They do not add a second planner, transaction engine, storage engine, or public plugin platform.

## Post-v1 candidates

Recommended order:

```text
transaction and row-version provenance views
MVCC time travel with explicit retention and schema semantics
adaptive durability and maintenance policies
robust optimizer feedback and plan confidence
online index construction
what-if plans and index advice
read-only agent access over existing snapshots
```

## Deliberate exclusions

```text
LLM operators in normal SQL plans
agent control of durability
GPU-native execution
vector-database specialization
distributed or disaggregated DelosDB
PostgreSQL wire compatibility without a concrete adoption requirement
```

The detailed local plan is maintained under `.delosdb-v1/05-research/`.
