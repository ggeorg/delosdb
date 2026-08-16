# Heap/MVCC differential SQL harness

The heap/MVCC differential SQL harness is a deterministic validation gate for
SQL behavior that must remain equivalent between Derby heap tables and
`delos_mvcc` tables.

It is intentionally narrower than randomized SQLancer validation. SQLancer can
find new cases, but a reduced case must be promoted into this deterministic
harness before it becomes a normal DelosDB release gate.

## Task

```bash
./gradlew :delosdb-tests:delosFunctionalTests --tests '*HeapMvccDifferentialSqlHarnessTest'
```

The same class is part of the permanent `delosFunctionalTests` suite; use the standard `--tests` filter above for a focused run.

## Current proof

`HeapMvccDifferentialSqlHarnessTest` creates one heap table and one
`delos_mvcc` table with the same schema and indexes. It then executes matched
operations against both providers and compares query results at named
checkpoints.

The current workload covers:

* primary-key and unique-key inserts;
* secondary indexes over status/quantity and date columns;
* nullable columns;
* decimal, date, varchar, and long-varchar-like payloads;
* rollback-only update/delete/insert mutation;
* committed update/delete/insert mutation;
* ordered projections;
* grouped aggregates;
* scalar aggregates;
* indexed lookup predicates;
* date predicates;
* provider maintenance through in-place compress/vacuum;
* shutdown/reopen persistence.

## Rules

* The harness may only include SQL whose heap and MVCC semantics are expected to
  match.
* Intentional provider differences must be documented outside the equality
  probes.
* A SQLancer failure must be minimized before being added here.
* This harness must not change heap, MVCC, optimizer, DRDA, catalog, or storage
  format behavior.
