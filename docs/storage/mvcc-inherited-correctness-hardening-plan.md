# Inherited MVCC correctness hardening plan

## Current boundary

MODULE6J completed the bridge cutover: `CREATE TABLE ... USING delos_mvcc` now creates an MVCC physical conglomerate, and normal inherited Derby SQL paths reach MVCC storage for `SELECT`, `INSERT`, `DELETE`, and `UPDATE`. The old MVCC `Delos*ResultSet` bypass classes were retired.

That proves routing and in-process behavior. It does not yet prove all inherited SQL correctness.

## Plan 4 sequence

### MODULE6K restart hardening

Keep this split into small proof overlays.

1. MODULE6K-1: inherited SQL `INSERT` restart proof.
2. MODULE6K-2: inherited SQL `UPDATE` restart proof.
3. MODULE6K-3: inherited SQL `DELETE` restart proof.
4. MODULE6K-4: restart hardening audit.

Each restart proof must close all connections, force a Derby database shutdown, clear in-memory Delos native table registry state, reconnect, and verify through inherited SQL `SELECT`. A simple connection close is not enough.

### MODULE7 predicate / qualifier correctness

Only after restart hardening is green:

1. MODULE7A: source-gated Derby predicate and qualifier boundary map.
2. MODULE7B: inherited MVCC `SELECT WHERE` equality.
3. MODULE7C: inherited MVCC `DELETE WHERE` equality.
4. MODULE7D: inherited MVCC `UPDATE WHERE` equality.
5. MODULE7E: non-matching predicate hardening.
6. MODULE7F: predicate audit / compensation.

## Explicit non-goals for this plan

Do not start indexes, optimizer cost modeling, native I/O, vacuum, distributed storage, Calcite, full SQL expression support, or heap redesign in this plan.
