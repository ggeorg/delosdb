# Storage Phase L3.2 — IN-list coverage through native scan plus residual restriction

## Decision

L3.2 deliberately does **not** add a generic `IN_LIST` Delos predicate and does
**not** add a native `MultiProbeTableScanResultSet` replacement.

The source-backed Derby shape is narrower:

- Derby's `PredicateList` explicitly says the store can never treat `IN` as a
  normal qualifier.
- Derby's indexed `IN` execution path is a separate `MultiProbeTableScanResultSet`
  route with generated probe values.
- Replacing that route for Delos now would require honest index-row/base-row and
  index-column mapping work, not just a predicate enum.

So L3.2 proves SQL coverage for `delos_mvcc` by using the already hardened native
full-scan route and leaving the `IN` expression as Derby residual evaluation
above the native scan.

## What L3.2 proves

- `delos_mvcc` `SELECT ... WHERE col IN (...)` works through the native table-scan
  route plus Derby residual expression evaluation.
- SQL `NULL` behavior inside an `IN` list remains Derby-owned.
- heap/default-provider `IN` queries remain Derby-native.
- no fake generic Delos `IN_LIST` contract appears.
- no native MultiProbe branch appears.
- no heap live-provider route appears.

## What L3.2 does not claim

- no native MVCC index-probe implementation for `IN` yet.
- no general `OR` support.
- no heap routing change.
- no mutation behavior change.
- no locking behavior change.
- no optimizer behavior change.

## Why this is the safe step

The project rule still applies:

> Do not make a generic contract method until two providers can implement it
> honestly.

An `IN_LIST` Delos predicate would be premature because Derby heap does not yet
implement live Delos scan semantics, and Derby's own `IN` execution has two
distinct shapes: residual expression evaluation and indexed multi-probe. L3.2
therefore records the honest current route and leaves native indexed `IN` work
for a later milestone after index/provider semantics are stronger.

## Follow-up

L3.3 should continue with the same honesty rule for `OR` predicates. If Derby
encodes a supported `OR` as a residual expression, the first safe proof may again
be native full-scan coverage with Derby residual evaluation, not a fake generic
Delos `OR` contract.
