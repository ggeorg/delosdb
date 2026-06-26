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

## MODULE7C - DELETE WHERE equality

Runtime-only predicate mutation proof.

Scope:
- normal inherited Derby SQL DELETE over `USING delos_mvcc`
- equality predicates on committed rows
- prepared equality predicates
- rollback keeps non-deleted state visible
- committed selective delete survives real Derby shutdown/reopen
- heap and btree compatibility

Implementation boundary:
- DELETE uses the qualified inherited scan result stream
- MVCC scan remains full-scan based
- qualifier evaluation is row filtering, not index access
- no UPDATE predicate claim yet
- no non-matching DELETE/UPDATE no-op hardening yet; that belongs to MODULE7E
- no source-string guards in this behavior smoke

## MODULE7D - UPDATE WHERE equality

Runtime-only predicate mutation proof.

Scope:
- normal inherited Derby SQL UPDATE over `USING delos_mvcc`
- equality predicates on committed rows
- prepared equality predicates
- rollback keeps the old version visible
- committed selective update survives real Derby shutdown/reopen
- non-updated columns are preserved
- heap and btree compatibility

Implementation boundary:
- UPDATE uses the qualified inherited scan result stream
- MVCC scan remains full-scan based
- qualifier evaluation is row filtering, not index access
- no non-matching DELETE/UPDATE no-op hardening yet; that belongs to MODULE7E
- no source-string guards in this behavior smoke

## MODULE7E - non-matching predicate hardening

Runtime-only predicate no-op proof.

Scope:
- normal inherited Derby SQL SELECT over `USING delos_mvcc` with non-matching equality predicates
- normal inherited Derby SQL DELETE over `USING delos_mvcc` with non-matching equality predicates
- normal inherited Derby SQL UPDATE over `USING delos_mvcc` with non-matching equality predicates
- prepared non-matching DELETE and UPDATE predicates
- committed no-op DELETE/UPDATE state survives real Derby shutdown/reopen
- rolled-back no-op DELETE/UPDATE state remains unchanged after real Derby shutdown/reopen
- heap and btree compatibility

Implementation boundary:
- MVCC scan remains full-scan based
- qualifier evaluation is row filtering, not index access
- no new predicate type beyond equality
- no source-string guards in this behavior smoke
- predicate audit / compensation remains MODULE7F

## MODULE7F - predicate audit / compensation

Runtime/documentation predicate audit.

Scope:
- aggregate SELECT WHERE equality and non-matching equality behavior
- aggregate DELETE WHERE equality and non-matching equality behavior
- aggregate UPDATE WHERE equality and non-matching equality behavior
- verify committed selective DELETE/UPDATE state survives real Derby shutdown/reopen
- verify rolled-back selective DELETE/UPDATE remains invisible after real Derby shutdown/reopen
- verify committed non-matching DELETE/UPDATE no-ops survive real Derby shutdown/reopen
- verify MVCC physical conglomerate identity remains MVCC
- verify inherited MVCC scan/controller runtime counters are reached
- verify heap and btree compatibility remains green

Implementation boundary:
- runtime-only behavior audit
- no source-string guards in this audit smoke
- equality qualifiers only
- MVCC scan remains full-scan based
- qualifier evaluation is row filtering, not index access
- no range predicate claim
- no broad SQL expression support claim
- no optimizer or index work
