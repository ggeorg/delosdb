# DelosDB public documentation

This directory contains the public product, architecture, compatibility, operations, and accepted
design records for DelosDB.

Documentation describes the implementation. The build does not parse Markdown or comments to decide
whether an architecture is valid. Executable authority comes from source structure, module and
artifact metadata, runtime providers, bytecode verification, tests, and checked structural
manifests.

## Start here

| Document | Purpose |
|---|---|
| [`PROJECT-STATUS.md`](PROJECT-STATUS.md) | current completed work, metrics, and next phase |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | SQL-to-result, generated-class, transaction, and storage architecture |
| [`BUILDING.md`](BUILDING.md) | supported build and verification workflow |
| [`STATIC-GATE-POLICY.md`](STATIC-GATE-POLICY.md) | seven permanent S0 authorities and gate rules |
| [`DERBY-COMPATIBILITY.md`](DERBY-COMPATIBILITY.md) | compatibility boundaries and deliberate differences |
| [`SECURITY.md`](SECURITY.md) | TLS, deserialization, and secure processing behavior |
| [`sql-extensions.md`](sql-extensions.md) | DelosDB SQL syntax and `USING delos_mvcc` |

## Product and architecture

| Document | Purpose |
|---|---|
| [`PRODUCT-STRATEGY.md`](PRODUCT-STRATEGY.md) | v1 commitments and scope |
| [`STORAGE-ARCHITECTURE.md`](STORAGE-ARCHITECTURE.md) | RawStore, heap, and MVCC ownership |
| [`V1-MODULE-ARCHITECTURE.md`](V1-MODULE-ARCHITECTURE.md) | module and dependency boundaries |
| [`DELOSDB-SERVER.md`](DELOSDB-SERVER.md) | DRDA server architecture and configuration |
| [`V1-RAWSTORE-CONVERGENCE-ARCHITECTURE.md`](V1-RAWSTORE-CONVERGENCE-ARCHITECTURE.md) | accepted RawStore convergence record |
| [`design/V1-GENERATED-CLASS-ARCHITECTURE.md`](design/V1-GENERATED-CLASS-ARCHITECTURE.md) | JDK 25 activation-generation architecture |
| [`design/V1-REPOSITORY-INTEGRITY-CLEANUP.md`](design/V1-REPOSITORY-INTEGRITY-CLEANUP.md) | permanent repository-integrity model and closeout |

## Current storage protocols

| Document | Purpose |
|---|---|
| [`MVCC-DATABASE-RUNTIME.md`](MVCC-DATABASE-RUNTIME.md) | database-scoped MVCC runtime ownership |
| [`MVCC-DURABILITY-PROTOCOL.md`](MVCC-DURABILITY-PROTOCOL.md) | transaction decision, failure, and recovery authority |
| [`MVCC-GROUP-COMMIT.md`](MVCC-GROUP-COMMIT.md) | concurrent preparation and bounded grouping |
| [`MVCC-MAINTENANCE.md`](MVCC-MAINTENANCE.md) | database-owned vacuum and maintenance |
| [`MVCC-BUFFER-REPLACEMENT-POLICY.md`](MVCC-BUFFER-REPLACEMENT-POLICY.md) | page-cache replacement and force policy |
| [`MVCC-CHECKPOINT-RECOVERY-ORDERING.md`](MVCC-CHECKPOINT-RECOVERY-ORDERING.md) | checkpoint and recovery ordering |
| [`MVCC-VISIBILITY-ALGORITHMS.md`](MVCC-VISIBILITY-ALGORITHMS.md) | snapshots and version visibility |

## Evidence and research

| Document | Purpose |
|---|---|
| [`V1-BASELINE.md`](V1-BASELINE.md) | opt-in capture and reviewed immutable baseline policy |
| [`PERFORMANCE-CONCURRENCY-VALIDATION.md`](PERFORMANCE-CONCURRENCY-VALIDATION.md) | benchmark and concurrency evidence policy |
| [`EXTERNAL-VALIDATION.md`](EXTERNAL-VALIDATION.md) | opt-in SQLancer and external validation lanes |
| [`RESEARCH-POSITIONING.md`](RESEARCH-POSITIONING.md) | research identity and boundaries |
| [`RESEARCH-FEATURE-PRIORITIES.md`](RESEARCH-FEATURE-PRIORITIES.md) | v1 research foundation and post-v1 order |
| [`ALGORITHM-INVENTORY.md`](ALGORITHM-INVENTORY.md) | source-backed algorithm inventory |
| [`TEST-ORGANIZATION-AND-CONSOLIDATION.md`](TEST-ORGANIZATION-AND-CONSOLIDATION.md) | accepted test provenance, organization, and consolidation plan |

## Design records

Files under [`design/`](design/) record accepted implementation decisions and proof boundaries. Many
retain historical stage names because those names identify the change that produced the design.
They are not active roadmaps and do not override the current documents above.

## Historical records

Completed phases and superseded plans live under [`history/`](history/). Historical commands and task
names may no longer exist. History provides provenance only.

## Local/private workspace

`.delosdb-v1/` contains local planning, overlay workflow, research notes, and unpublished manuscript
material. It is not part of the public product contract. Stable conclusions should be promoted into
this public directory, source comments, tests, or release notes.

## Documentation ownership rules

- One current document owns each public topic.
- Current documents use present-tense product language.
- Completed stage logs are summarized or moved to history.
- Detailed design records may preserve historical terminology but must not be listed as current plans.
- Public behavior, durable formats, module ownership, and supported limitations must match code and
  tests.
- Documentation wording, headings, line counts, and exact phrases are never build authority.
