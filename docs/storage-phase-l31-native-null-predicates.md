# Storage Phase L3.1 — Native delos_mvcc NULL predicates

L3.1 is the first small SQL-coverage slice after L4 cost consumption. It adds explicit native `IS NULL` and `IS NOT NULL` predicate support for `delos_mvcc` SELECTs only.

## Decision

`delos_mvcc` should support NULL predicates through the existing Delos predicate contract, not through a new broad expression model.

This milestone keeps the change narrow:

- Derby heap remains Derby-native.
- `GenericResultSetFactory` remains the branch point.
- `FromBaseTable.generate()` is unchanged.
- No optimizer routing change is introduced.
- No mutation NULL predicate support is claimed.
- No heap provider activation is introduced.
- No bridge path is restored.

## Source fact used

Derby does not expose a separate store `Orderable` constant for `IS NULL`. In the compiled scan qualifier path, `IsNullNode.generateOperator()` emits `Orderable.ORDER_OP_EQUALS`, while `IsNullNode.generateOrderedNulls()` emits `true`. `IS NOT NULL` is the same NULL comparison with `negateCompareResult=true`.

L3.1 therefore recognizes the Derby qualifier shape explicitly:

```text
operator == ORDER_OP_EQUALS
orderedNulls == true
orderable is DataValueDescriptor and isNull()
negateCompareResult == false  -> DelosPredicateOperator.IS_NULL
negateCompareResult == true   -> DelosPredicateOperator.IS_NOT_NULL
```

This avoids relying on accidental `EQUAL NULL` / `NOT_EQUAL NULL` behavior.

## Contract shape

`DelosPredicateOperator` already had `IS_NULL` and `IS_NOT_NULL`. L3.1 makes them executable by adding explicit `DelosPredicate.isNull(...)` and `DelosPredicate.isNotNull(...)` factories and by teaching `EngineMvccTableAccess` to evaluate zero-operand NULL predicates.

SQL equality against NULL remains distinct from `IS NULL`:

```sql
label = CAST(NULL AS VARCHAR(32))
```

continues to behave as SQL UNKNOWN and should not return NULL rows.

## Acceptance

`verifyStoragePhaseL31NativeNullPredicates` proves:

- `delos_mvcc` `WHERE col IS NULL` returns only NULL rows.
- `delos_mvcc` `WHERE col IS NOT NULL` returns only non-NULL rows.
- NULL predicates compose with the existing native range predicate path.
- `= NULL` does not masquerade as `IS NULL`.
- heap `IS NULL` remains on the Derby-native/default-provider route.
- no live heap Delos routing appears.

## Next

Continue L3 with the next narrow SQL-coverage slice, likely `IN` list support, still for `delos_mvcc` only and without heap routing changes.
