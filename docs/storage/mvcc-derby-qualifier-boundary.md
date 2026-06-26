# MODULE7A Derby predicate / qualifier boundary map

Status: source-gated map only.  No MVCC predicate implementation is introduced here.

## Goal

MODULE6 proved that normal inherited Derby SQL paths reach MVCC physical storage for
CREATE TABLE, SELECT, INSERT, DELETE, and UPDATE.  MODULE6K then proved inherited
SQL CRUD commit/rollback behavior across real Derby shutdown/reopen.

MODULE7 must now answer a different question:

```text
When SQL contains a WHERE predicate, which layer decides which MVCC rows qualify?
```

This matters because DELETE and UPDATE mutate exactly the rows delivered by their
source result set.  If a predicate is pushed only into the store as a `Qualifier[][]`
and the MVCC scan ignores it, a selective DELETE/UPDATE can become a full-table
mutation.

## Source facts

### SELECT scan entry

`GenericResultSetFactory#getTableScanResultSet(...)` constructs the inherited
`TableScanResultSet`.  `TableScanResultSet` stores the compiled scan qualifiers in
its `qualifiers` field.  `BulkTableScanResultSet#openScanController(...)` passes
those qualifiers to `TransactionController.openCompiledScan(...)`.

Relevant source chain:

```text
GenericResultSetFactory#getTableScanResultSet(...)
  -> new TableScanResultSet(...)
TableScanResultSet.qualifiers
  -> BulkTableScanResultSet#openScanController(...)
  -> TransactionController.openCompiledScan(..., qualifiers, ...)
  -> RAMTransaction.openCompiledScan(...)
  -> RAMTransaction.openScan(...)
  -> MvccConglomerate.openScan(..., Qualifier[][] qualifier, ...)
```

### Store qualifier shape

The Derby store qualifier contract is `org.apache.derby.iapi.store.access.Qualifier`.
A qualifier exposes:

```text
getColumnId()
getOrderable()
getOperator()
negateCompareResult()
getOrderedNulls()
getUnknownRV()
```

The Derby source documents the `Qualifier[][]` shape as an AND-of-ORs structure.
Rows for which any AND group fails must not be returned by the scan.

### Residual predicate shape

`ProjectRestrictResultSet` can also evaluate a generated `restriction` method above
its source result set.  Its `getNextRowCore()` loop repeatedly pulls candidate rows
from the source, invokes the restriction, and returns only qualifying rows.

This means WHERE predicates may be represented as:

```text
store qualifiers passed to ScanController
residual generated restriction above the scan
or a combination of both
```

MODULE7 must not assume all predicates are residual, and must not assume all
predicates are pushed to the store.

### Current MVCC scan behavior

Current `MvccConglomerate.openScan(...)` receives `Qualifier[][] qualifier`, but
constructs `MvccScanController` without passing the qualifier object into it.
Current `MvccScanController` accepts qualifier arguments on `reopenScan(...)` and
`reopenScanByRowLocation(...)`, but reopens the MVCC table scan without storing or
evaluating those qualifiers.

Therefore, as of MODULE7A, MVCC store/access scans should be treated as:

```text
visibility-aware
row-location-aware
not yet qualifier-aware
```

### DELETE mutation path

`DeleteResultSet` opens its source result-set tree, pulls rows from the source, reads
the base `RowLocation`, and calls `RowChangerImpl.deleteRow(...)`.  `RowChangerImpl`
then calls `ConglomerateController.delete(baseRowLocation)`, which reaches the MVCC
conglomerate controller for MVCC physical tables.

Important consequence:

```text
DELETE mutates the rows selected by its source result set.
```

If the source over-returns because store qualifiers are ignored and no residual
restriction catches the row, DELETE can delete too much.

### UPDATE mutation path

`UpdateResultSet` opens its source result-set tree, pulls rows from the source, reads
the base `RowLocation`, builds the new row image, and calls
`RowChangerImpl.updateRow(...)`.  `RowChangerImpl` then calls
`ConglomerateController.replace(baseRowLocation, sparseRowArray, changedColumnBitSet)`,
which reaches the MVCC conglomerate controller for MVCC physical tables.

Important consequence:

```text
UPDATE mutates the rows selected by its source result set.
```

If the source over-returns because store qualifiers are ignored and no residual
restriction catches the row, UPDATE can update too much.

## Immediate MODULE7 risk

The next correctness cliff is not routing.  Routing is green.  The risk is selective
correctness:

```text
SELECT * FROM t WHERE id = 2
DELETE FROM t WHERE id = 2
UPDATE t SET name = 'x' WHERE id = 2
```

Each must affect only the matching row.

## Required next modules

### MODULE7B — SELECT WHERE equality diagnostic/proof

Start with runtime behavior, not implementation.  Insert rows 1, 2, 3 into a
`delos_mvcc` table and run:

```sql
SELECT * FROM t WHERE id = 2
SELECT * FROM t WHERE id = 999
```

The smoke must prove the result set is selective and must record whether MVCC scan
qualifiers were seen by the store path if instrumentation already exists or is added
in that module.

### MODULE7C — DELETE WHERE equality

Prove:

```sql
DELETE FROM t WHERE id = 2
```

hides only row 2, preserves rows 1 and 3, and commit/rollback behavior remains
correct.

### MODULE7D — UPDATE WHERE equality

Prove:

```sql
UPDATE t SET name = 'x' WHERE id = 2
```

updates only row 2, preserves rows 1 and 3, and commit/rollback behavior remains
correct.

### MODULE7E — non-matching predicates

Prove non-matching predicates are no-ops:

```sql
DELETE FROM t WHERE id = 999
UPDATE t SET name = 'x' WHERE id = 999
```

### MODULE7F — predicate audit

A final audit may use source checks because it is an explicit audit module.  Normal
behavior modules must remain runtime-focused and must not add brittle source-string
guards.

## Non-goals

```text
no indexes
no optimizer rewrite
no native I/O
no bridge/result-set bypass resurrection
no broad SQL expression model
no full predicate pushdown framework yet
```
