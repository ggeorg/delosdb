
# DelosDB Product Strategy

## Product position

DelosDB is a maintained Java 25 successor to Apache Derby and a comprehensible, research-capable
relational database management system.

It does not compete through feature-count breadth. It combines Derby continuity, deliberate
modernization, whole-system coherence, explicit transaction and storage ownership, and unusually
strong source-level inspectability.

## Strategic commitments

### Derby continuity and modernization

- Preserve supported Derby SQL, JDBC, catalogs, durable heap databases, and DRDA behavior.
- Keep heap as the default durable compatibility foundation.
- Correct inherited defects with source-backed tests and documentation.
- Provide optional modern storage without forcing migration.

### A comprehensible, research-capable RDBMS

Students and researchers can follow:

```text
SQL
→ parse and bind
→ optimize and generate
→ execute
→ decide transaction outcome
→ access heap or MVCC
→ log, recover, and back up
→ deliver JDBC or DRDA results
```

The production engine, not a simplified clone, is the teaching and research subject.

## Current product truth

The ownership questions that drove the RawStore-convergence program are now resolved:

```text
one database-scoped MVCC runtime owns logical MVCC state
one Derby transaction boundary owns supported transaction outcome
one Derby RawStore owns physical persistence and recovery
```

`delos_mvcc` is an access method over RawStore, not a parallel database/storage runtime. Unsupported
transaction or type combinations continue to reject before mutation until their product contracts are
implemented and proven.

## Comparison-engine lessons

- **DuckDB:** explicit database ownership, first-class plans/profiling, clear source anatomy, and
  declarative verification.
- **PostgreSQL:** explicit planning/execution structures, access-method discipline, and recovery
  invariants.
- **H2:** compact embedded integration and practical lifecycle.
- **MariaDB/InnoDB:** mature storage and operational boundaries.

These are lessons, not product templates. DelosDB retains Derby compatibility, DRDA, generated
activations, row-oriented result sets, and dual storage modes.

## Research position

DelosDB occupies a relatively uncommon position: a complete compatibility-oriented relational
system designed for end-to-end inspection and controlled experimentation.

It does not add LLM operators, vector specialization, GPU execution, distributed storage, or agent
control merely to follow current research trends.
