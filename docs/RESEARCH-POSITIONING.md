
# DelosDB Research Positioning

## Position

DelosDB is a complete compatibility-oriented relational database designed for end-to-end
inspection and controlled experimentation.

Its research value comes from the combination of:

```text
Derby continuity and modernization
one complete SQL/JDBC/catalog/DRDA engine
two deliberately integrated storage modes
real transaction, durability, recovery, backup, and maintenance machinery
structured evidence from SQL text to durable state
```

DelosDB is not a simplified teaching database and does not attempt to reproduce every current
research trend.

## Current research context

Recent database-systems work emphasizes AI-facing data systems, unstructured data, cloud and
disaggregated architectures, emerging hardware, robust optimization, workflow durability,
governance, provenance, and reproducibility.

DelosDB should not respond by adding unrelated fashionable features. The relevant opportunity is
more specific:

> A complete relational system in which execution, transactions, storage, recovery, and durable
> state can be studied and reproduced end to end.

## Closest reference points

| DelosDB concern | Useful reference |
|---|---|
| compact integrated engine | DuckDB and H2 |
| complete modern relational engine | Umbra |
| optimizer and benchmark discipline | CMU systems |
| durable application execution | DBOS |
| transaction and coordination correctness | Berkeley systems research |
| robust query execution | Wisconsin systems research |
| architecture-aware experimentation | EPFL |

The references are comparative, not product templates.

## V1 research foundation

```text
immutable structured observability snapshots
deterministic fault injection and replay
storage-aware EXPLAIN ANALYZE
reproducible experiment and benchmark manifests
```

These capabilities support correctness, teaching, and controlled experiments inside the production
engine.

## Post-v1 research directions

```text
transaction and row-version provenance views
MVCC time travel with explicit retention semantics
adaptive durability and maintenance policies
robust optimizer feedback and plan confidence
online index construction
what-if plans and index advice
read-only external tools over authoritative snapshots
```

## Directions outside v1

```text
LLM operators in normal SQL plans
agent control of transactions or durability
vector-database specialization
GPU-native execution
disaggregated distributed storage
self-driving physical redesign
workflow orchestration
HTAP redesign
PostgreSQL wire compatibility for marketing
```

## Research constraints

- Product correctness and compatibility precede experiments.
- Every experiment identifies an authoritative owner and invariant.
- Production defaults remain stable and safe.
- Comparison paths are temporary and removable.
- Results include machine-readable data, semantic checksums, and reproducible metadata.
- A research abstraction requires concrete consumers before it becomes product architecture.

## Immediate prerequisite

The first research obligation is not a new algorithm. It is correct ownership of:

```text
database identity
transaction outcome
```

Until database-scoped runtime ownership and failure-atomic supported transactions are established,
performance and research claims remain subordinate to those corrections.

## References

- The Cambridge Report on Database Research, 2025
- CIDR 2026 program
- DuckDB source architecture
- DelosDB source, tests, and tracked protocol documentation
