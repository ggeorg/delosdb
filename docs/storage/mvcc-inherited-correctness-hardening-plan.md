# Inherited MVCC correctness hardening plan

## Current boundary

MODULE6J completed the bridge cutover: `CREATE TABLE ... USING delos_mvcc` now creates an MVCC physical conglomerate, and normal inherited Derby SQL paths reach MVCC storage for `SELECT`, `INSERT`, `DELETE`, and `UPDATE`. The old MVCC `Delos*ResultSet` bypass classes were retired.

MODULE6K restart hardening is split into small proofs because restart correctness is a separate integration boundary from in-process routing.

## MODULE6K restart hardening

Each restart proof must close all connections, force a Derby database shutdown, clear in-memory Delos native table registry state, reconnect, and verify through inherited SQL `SELECT`. A simple connection close is not enough.

Status:

1. MODULE6K-1: inherited SQL `INSERT` restart proof.
2. MODULE6K-2: inherited SQL `UPDATE` restart proof.
3. MODULE6K-3: inherited SQL `DELETE` restart proof.
4. MODULE6K-4: inherited SQL CRUD restart audit.

MODULE6K behavior/restart smokes must be runtime proofs only. Do not add brittle source-string guards to these smokes. Source audits belong in explicit source-map/audit modules such as MODULE6A, MODULE6I, MODULE6J, MODULE7A, and MODULE7F.

## Next sequence after MODULE6K

Only after restart hardening is green:

1. MODULE7A: source-gated Derby predicate and qualifier boundary map.
2. MODULE7B: inherited MVCC `SELECT WHERE` equality.
3. MODULE7C: inherited MVCC `DELETE WHERE` equality.
4. MODULE7D: inherited MVCC `UPDATE WHERE` equality.
5. MODULE7E: non-matching predicate hardening.
6. MODULE7F: predicate audit / compensation.

## Explicit non-goals for this plan

Do not start indexes, optimizer cost modeling, native I/O, vacuum, distributed storage, Calcite, full SQL expression support, or heap redesign in this plan.

## MODULE7B - SELECT WHERE equality

Runtime-only predicate proof.

Scope:
- normal inherited Derby SQL SELECT over `USING delos_mvcc`
- equality predicates on committed rows
- non-matching equality predicates
- prepared equality predicates
- heap and btree compatibility

Implementation boundary:
- MVCC scan remains full-scan based
- qualifier evaluation is row filtering, not index access
- no DELETE/UPDATE predicate claim yet
- no source-string guards in this behavior smoke
