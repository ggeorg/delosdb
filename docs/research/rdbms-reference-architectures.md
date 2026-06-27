# RDBMS reference architectures for DelosDB

DelosDB should use PostgreSQL, Apache Calcite, and HerdDB as reference architectures.  The purpose is
not to copy code.  The purpose is to design a modern, teachable, research-friendly RDBMS model that
can be implemented and observed through inherited Derby and DelosDB code.

## PostgreSQL

PostgreSQL is the strongest reference for a complete classical RDBMS layout.  Its source tree makes
major database subsystems visible:

```text
parser
optimizer
executor
catalog
access methods
storage
transaction/runtime utilities
```

The lesson for DelosDB is stage clarity.  DelosDB should make the distinction between parse tree,
semantic query, logical/physical plan, executor state, catalog access, and storage access visible to
students and researchers.

DelosDB should not copy PostgreSQL's C implementation structure directly.

## Apache Calcite

Apache Calcite is the strongest reference for SQL validation, relational algebra, planner rules,
traits, cost models, and adapters.  It cleanly separates:

```text
SQL syntax
validation
SQL-to-relational conversion
relational algebra
row expressions
planner rules and costs
adapter-specific execution conventions
```

The lesson for DelosDB is conceptual separation between SQL syntax, logical plan, physical plan, and
storage access.  DelosDB should not replace the inherited Derby compiler with Calcite in the current
phase.

## HerdDB

HerdDB is useful as a compact Java database reference.  It keeps concepts such as statement,
execution plan, planner operation, table manager, scanner, commit log, storage manager, and index
manager easier to see than in a large inherited engine.

The lesson for DelosDB is to keep a small, explicit Java model vocabulary even when the underlying
implementation is complex.

## Combined lesson for DelosDB

DelosDB should combine:

```text
PostgreSQL's lifecycle clarity
Calcite's SQL/planner/algebra vocabulary
HerdDB's compact Java model style
Derby's proven implementation substrate
DelosDB's native storage and MVCC research seam
```

The resulting model should be implemented incrementally and proven against real execution paths.
