# DelosDB documentation

This directory contains tracked product, architecture, compatibility, operational, and engineering
evidence for DelosDB. Local planning material under `.delosdb-v1/` is intentionally ignored and is
not a substitute for these documents.

## Authoritative product documents

| Document | Purpose |
|---|---|
| [`PRODUCT-STRATEGY.md`](PRODUCT-STRATEGY.md) | v1.0 strategic commitments, scope, and design principles |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | complete SQL-to-result and durable-state architecture |
| [`RESEARCH-POSITIONING.md`](RESEARCH-POSITIONING.md) | research identity, scope, and comparative reference points |
| [`DUCKDB-COMPARISON.md`](DUCKDB-COMPARISON.md) | source-backed DuckDB lessons and deliberate non-adoption |
| [`DERBY-COMPATIBILITY.md`](DERBY-COMPATIBILITY.md) | compatibility boundaries and deliberate differences |
| [`STORAGE-ARCHITECTURE.md`](STORAGE-ARCHITECTURE.md) | heap/MVCC storage ownership and durable boundaries |
| [`DELOSDB-SERVER.md`](DELOSDB-SERVER.md) | DRDA server architecture and configuration |
| [`sql-extensions.md`](sql-extensions.md) | DelosDB SQL syntax and storage selection |
| [`BUILDING.md`](BUILDING.md) | supported build and verification workflow |

## Current engineering program

| Document | Purpose |
|---|---|
| [`CLEANUP-CONSOLIDATION.md`](CLEANUP-CONSOLIDATION.md) | active product-truth and consolidation policy |
| [`STATIC-GATE-POLICY.md`](STATIC-GATE-POLICY.md) | stable S0 gate policy |
| [`PERFORMANCE-CONCURRENCY-VALIDATION.md`](PERFORMANCE-CONCURRENCY-VALIDATION.md) | benchmark and concurrency evidence policy |
| [`EXTERNAL-VALIDATION.md`](EXTERNAL-VALIDATION.md) | opt-in SQLancer and external validation lanes |

## Current storage protocols

| Document | Purpose |
|---|---|
| [`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md) | transaction durability, failure, and recovery authority |
| [`MVCC-GROUP-COMMIT.md`](MVCC-GROUP-COMMIT.md) | concurrent preparation and bounded transaction grouping |
| [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md) | database-owned vacuum and maintenance scheduling |
| [`MVCC-BACKUP-COORDINATION.md`](MVCC-BACKUP-COORDINATION.md) | database-scoped online-backup boundary |
| [`MVCC-BUFFER-REPLACEMENT-POLICY.md`](MVCC-BUFFER-REPLACEMENT-POLICY.md) | page-cache replacement and force policy |
| [`MVCC-CHECKPOINT-RECOVERY-ORDERING.md`](MVCC-CHECKPOINT-RECOVERY-ORDERING.md) | checkpoint and recovery ordering |
| [`MVCC-VISIBILITY-ALGORITHMS.md`](MVCC-VISIBILITY-ALGORITHMS.md) | read views and row-version visibility |

## Audits and permanent evidence

The remaining audit documents record source-backed design decisions and executable proof. They are
not active roadmaps unless another authoritative document says otherwise.

Examples include:

```text
ALGORITHM-INVENTORY.md
COMPARATIVE-ENGINE-AUDIT.md
HEAP-ALGORITHM-BOUNDARIES.md
HEAP-MVCC-DIFFERENTIAL-SQL-HARNESS.md
OPTIMIZER-COST-AUTHORITY.md
PAGE-CODEC-ALGORITHMS.md
STORAGE-ACCESS-DECISIONS.md
STORAGE-LIFECYCLE-CONSISTENCY-REPORT.md
```

## Historical closeout records

Completed phase and superseded roadmap records are retained under [`history/`](history/). They
provide provenance but do not override current product, architecture, compatibility, or protocol
documents.

## Documentation rules

- Supported behavior must be described in present-tense product language.
- Historical phase names belong only in closeout and history documents.
- A production class must not be described as a prototype, skeleton, or preflight once it owns
  supported behavior.
- One authoritative document owns each protocol; intermediate slice documents are consolidated or
  moved to history when the phase closes.
- Every code change that alters public behavior, durable state, ownership, or verification must
  update the corresponding tracked document.
