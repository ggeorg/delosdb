# DelosDB product strategy

## Product position

DelosDB is a maintained Java 25 successor to Apache Derby and a comprehensible, research-capable
relational database management system.

It does not compete by reproducing every PostgreSQL, MariaDB, MySQL, or H2 feature. It competes by
combining compatibility, deliberate modernization, whole-system coherence, and unusually strong
source-level comprehensibility.

## Strategic commitments

### Derby continuity and modernization

DelosDB preserves the practical value of Derby for existing applications and databases.

This commitment includes:

- Derby-compatible SQL, JDBC, catalogs, database formats, and DRDA where explicitly supported;
- the inherited heap and raw store as the durable compatibility foundation;
- continued operation on Java 25;
- correction and documentation of inherited defects;
- optional modern storage without a mandatory migration.

Compatibility is preserved where it has user and ecosystem value. Derby internals are not retained
merely because they are inherited.

### A comprehensible, research-capable RDBMS

DelosDB exposes the complete database path:

```text
SQL text
    -> parse and bind
    -> optimize and generate
    -> execute
    -> coordinate transactions
    -> access heap or MVCC storage
    -> log, recover, and back up
    -> deliver JDBC or DRDA results
```

Students should be able to trace this path in production code. Researchers should be able to change
one algorithm, preserve a comparison path while evaluating it, and produce reproducible evidence.

## Design principles

- Prefer vertical coherence over feature-count competition.
- Complete selected features across JDBC, DRDA, heap, MVCC, backup, recovery, and diagnostics.
- Use mature technology deliberately.
- Keep safe defaults and truthful public semantics.
- Add an abstraction only after concrete consumers prove the ownership boundary.
- Keep one authoritative implementation after an experiment is accepted.
- Measure before optimizing and preserve semantic checksums during comparison.
- Extract classes by independent responsibility, not by line count alone.

## Historical perspective

DelosDB follows the successor-system principle demonstrated by the Berkeley lineage:

```text
Ingres
    -> POSTGRES
    -> PostgreSQL
```

The relevant lesson is not architectural equivalence. A successor can preserve valuable relational
knowledge and continuity while replacing implementation decisions that no longer serve its goals.

DelosDB applies that principle to Apache Derby:

```text
Apache Derby
    -> DelosDB
```

## v1.0 scope

DelosDB v1.0 requires:

- a maintained Derby-compatible heap path;
- an explicit, durable MVCC table engine;
- embedded JDBC and Derby-compatible DRDA operation;
- complete backup, restore, shutdown, reopen, and recovery behavior for supported features;
- first-class plan and execution visibility;
- bounded resource defaults;
- explicit isolation and unsupported-feature behavior;
- reproducible benchmarks and fault-injection evidence;
- source-accurate architecture and teaching material.

Features that cannot meet the complete product contract are rejected explicitly or deferred.
