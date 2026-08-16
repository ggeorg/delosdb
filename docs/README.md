# DelosDB public documentation

This directory contains the public product, architecture, compatibility, operations, and accepted
design documentation for DelosDB.

Documentation describes the implementation. The build does not parse Markdown or comments to decide
whether an architecture is valid. Executable authority comes from source structure, module and
artifact metadata, runtime providers, bytecode verification, tests, and checked structural
manifests.

## Start here

| Document | Purpose |
|---|---|
| [`PROJECT-STATUS.md`](PROJECT-STATUS.md) | current capabilities, repository metrics, limitations, and remaining work |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | SQL-to-result, generated-class, transaction, and storage architecture |
| [`READABLE-ENGINE.md`](READABLE-ENGINE.md) | stable plans, EXPLAIN, EXPLAIN ANALYZE, and the end-to-end trace |
| [`BUILDING.md`](BUILDING.md) | supported build and verification workflow |
| [`STATIC-GATE-POLICY.md`](STATIC-GATE-POLICY.md) | permanent verification authorities and static-analysis rules |
| [`DERBY-COMPATIBILITY.md`](DERBY-COMPATIBILITY.md) | compatibility boundaries and deliberate differences |
| [`SECURITY.md`](SECURITY.md) | TLS, deserialization, and secure processing behavior |
| [`sql-extensions.md`](sql-extensions.md) | DelosDB SQL syntax and `USING delos_mvcc` |

## Product and architecture

| Document | Purpose |
|---|---|
| [`PRODUCT-STRATEGY.md`](PRODUCT-STRATEGY.md) | v1 commitments and scope |
| [`STORAGE-ARCHITECTURE.md`](STORAGE-ARCHITECTURE.md) | RawStore, heap, and MVCC ownership |
| [`OPTIMIZER-AUTHORITY.md`](OPTIMIZER-AUTHORITY.md) | optimizer, statistics, and store-cost authority boundaries |
| [`V1-MODULE-ARCHITECTURE.md`](V1-MODULE-ARCHITECTURE.md) | module and dependency boundaries |
| [`DELOSDB-SERVER.md`](DELOSDB-SERVER.md) | DRDA server architecture and configuration |
| [`design/V1-GENERATED-CLASS-ARCHITECTURE.md`](design/V1-GENERATED-CLASS-ARCHITECTURE.md) | JDK 25 activation-generation architecture |

## Current storage protocols

| Document | Purpose |
|---|---|
| [`MVCC-DATABASE-RUNTIME.md`](MVCC-DATABASE-RUNTIME.md) | database-scoped MVCC runtime ownership |
| [`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md) | transaction decision, failure, and recovery authority |
| [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md) | database-owned vacuum and maintenance |
| [`MVCC-VISIBILITY-ALGORITHMS.md`](MVCC-VISIBILITY-ALGORITHMS.md) | snapshots and version visibility |

## Evidence and research

| Document | Purpose |
|---|---|
| [`V1-BASELINE.md`](V1-BASELINE.md) | opt-in capture and reviewed immutable baseline policy |
| [`PERFORMANCE-CONCURRENCY-VALIDATION.md`](PERFORMANCE-CONCURRENCY-VALIDATION.md) | benchmark and concurrency evidence policy |
| [`EXTERNAL-VALIDATION.md`](EXTERNAL-VALIDATION.md) | opt-in SQLancer and external validation lanes |
| [`RESEARCH-POSITIONING.md`](RESEARCH-POSITIONING.md) | research identity and boundaries |
| [`ALGORITHM-INVENTORY.md`](ALGORITHM-INVENTORY.md) | current source-backed algorithm and ownership inventory |

## Design records

The public design directory is intentionally small. These records add implementation detail that is not
repeated in the higher-level architecture documents:

| Document | Durable decision |
|---|---|
| [`design/V1-GENERATED-CLASS-ARCHITECTURE.md`](design/V1-GENERATED-CLASS-ARCHITECTURE.md) | JDK 25 generated activation classes and compiler/backend ownership |
| [`design/V1-JDK25-RAWSTORE-PAGE-IO-REPRESENTATION-DECISION.md`](design/V1-JDK25-RAWSTORE-PAGE-IO-REPRESENTATION-DECISION.md) | authoritative RawStore page representation |
| [`design/V1-MVCC-COMMIT-ORDERING-AND-SNAPSHOT-MATHEMATICS.md`](design/V1-MVCC-COMMIT-ORDERING-AND-SNAPSHOT-MATHEMATICS.md) | commit publication ordering and snapshot mathematics |
| [`design/V1-MVCC-STABLE-ROW-AND-VERSION-IDENTITY.md`](design/V1-MVCC-STABLE-ROW-AND-VERSION-IDENTITY.md) | durable logical row/version identity |
| [`design/V1-RAWSTORE-MVCC-DATABASE-WIDE-IDENTITIES.md`](design/V1-RAWSTORE-MVCC-DATABASE-WIDE-IDENTITIES.md) | database-wide transaction and commit-sequence allocation |
| [`design/V1-RAWSTORE-MVCC-LOGICAL-LOCKING.md`](design/V1-RAWSTORE-MVCC-LOGICAL-LOCKING.md) | semantic row/schema/unique-key conflict locking |
| [`design/V1-RAWSTORE-MVCC-PHYSICAL-LOCKING.md`](design/V1-RAWSTORE-MVCC-PHYSICAL-LOCKING.md) | RawStore physical lock granularity |
| [`design/V1-RAWSTORE-MVCC-ORDERED-INDEXES.md`](design/V1-RAWSTORE-MVCC-ORDERED-INDEXES.md) | version-aware ordered-index candidates |
| [`design/V1-RAWSTORE-MVCC-UNIQUE-CONSTRAINTS.md`](design/V1-RAWSTORE-MVCC-UNIQUE-CONSTRAINTS.md) | native uniqueness enforcement and DDL lifecycle |
| [`design/V1-RAWSTORE-MVCC-UPDATE-DELETE.md`](design/V1-RAWSTORE-MVCC-UPDATE-DELETE.md) | replacement-version and tombstone mutation model |

Completed experiments, detailed acceptance matrices, test seams, and superseded implementation slices
belong in private engineering history. Current product behavior remains owned by the current documents
above together with source code, tests, and permanent structural manifests.

## Documentation ownership rules

- One current document owns each public topic.
- Current documents use present-tense product language.
- Superseded engineering plans and implementation diaries are not public product documentation.
- Design records explain durable decisions without requiring knowledge of internal development sequencing.
- Public behavior, durable formats, module ownership, and supported limitations must match code and tests.
- Documentation wording, headings, line counts, and exact phrases are never build authority.

- [Isolation specifications](ISOLATION-SPECIFICATIONS.md) — declarative concurrency format, catalogue, and execution rules.
