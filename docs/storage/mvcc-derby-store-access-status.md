# MVCC Derby store/access status after MODULE6I

This note records the source-gated status after MODULE6B through MODULE6I.
It is intentionally an audit checkpoint, not a new design phase.

## Green facts

- Derby can discover the `delos_mvcc` access method through the inherited access-method lookup path.
- `CREATE TABLE ... USING delos_mvcc` creates an MVCC physical conglomerate rather than a heap physical conglomerate.
- Normal inherited SQL `SELECT` reaches `TableScanResultSet`, `TransactionController.openCompiledScan`, and `MvccScanController`.
- Normal inherited SQL `INSERT` reaches `InsertResultSet`, `RowChangerImpl`, and `MvccConglomerateController`.
- Normal inherited SQL `DELETE` reaches `DeleteResultSet`, `RowChangerImpl`, and `MvccConglomerateController.delete`.
- Normal inherited SQL `UPDATE` reaches `UpdateResultSet`, `RowChangerImpl`, and `MvccConglomerateController.replace`.
- The old MVCC-specific `DelosTableScanResultSet`, `DelosInsertResultSet`, `DelosDeleteResultSet`, and `DelosUpdateResultSet` bypass classes have been retired.
- Heap and btree compatibility smokes remain green.

## Guardrails

- Do not reintroduce MVCC-specific `GenericResultSetFactory` bypasses.
- Do not route MVCC CRUD through old proof properties.
- Do not let `delos_mvcc` base tables fall back to heap physical conglomerates.
- Do not treat heap live-route proof classes as MVCC provider integration.
- Do not start MVCC indexes, optimizer changes, native I/O, vacuum, or distributed storage from this checkpoint.

## Remaining limitations

The inherited path is now real, but the following are not proven by MODULE6I or MODULE6J:

- WHERE predicate correctness for MVCC scans.
- Store qualifier evaluation or pushdown.
- Multi-row selective DELETE/UPDATE correctness.
- Index-backed MVCC access.
- Constraint behavior on MVCC physical tables.
- Durable RowLocation semantics beyond the current smoke scope.
- A production-quality MVCC optimizer cost model.

## Next safe target

After this audit remains green, the next safe planning choice is a narrow predicate/qualifier proof or a narrow constraint/RowLocation proof. It should not be bundled with index work.
