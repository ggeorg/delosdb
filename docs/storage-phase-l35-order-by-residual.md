# Storage Phase L3.5 — delos_mvcc ORDER BY residual-sort coverage

## Decision

L3.5 is an ordered-result coverage proof, not an ordered provider contract.

`delos_mvcc` may participate in `ORDER BY` queries through the existing native table-scan seam, but Derby remains responsible for sort semantics above the scan. This deliberately avoids adding a generic `DelosOrder`, ordered-scan, index-order, or provider-side ordering API before both providers can implement it honestly.

## Scope

Included:

- property-gated native `delos_mvcc` full-scan coverage for `ORDER BY` statements;
- proof that Derby `SortResultSet` / row-count layers may sit above `DelosTableScanResultSet`;
- proof that non-selected `ORDER BY` columns still work through Derby's existing column pull-up and projection machinery;
- proof that heap `ORDER BY` stays Derby-native.

Excluded:

- no heap Delos routing;
- no heap live provider;
- no provider-side ordered-scan contract;
- no index-order exploitation;
- no optimizer ordered-cost change;
- no mutation or locking behavior change;
- no bridge resurrection.

## Property gate

```text
 delosdb.storage.phaseL35.nativeOrderByResidual=true
```

The gate exists only to prove the next read-shape coverage increment. It enables the native table-scan branch for non-default-provider tables, but it does not claim native ordering. Derby still performs `ORDER BY` semantics using its normal execution nodes above the source scan.

## Architecture truth after L3.5

```text
SQL ORDER BY
  -> Derby compiler/planner owns ORDER BY columns and sort nodes
  -> GenericResultSetFactory chooses native scan only for delos_mvcc under proof gate
  -> DelosTableScanResultSet returns rows
  -> Derby SortResultSet / projection / row-count layers own final ordering semantics
```

This keeps the Delos storage contract honest. Ordered scan can become a future contract only after a second provider can implement it without pretending.

## Acceptance

`verifyStoragePhaseL35OrderByResidual` passes and proves:

- the L3.5 property exists;
- `delos_mvcc ORDER BY` uses the native table-scan route;
- ascending and descending ordering are correct;
- ordering by a non-selected column is correct;
- predicate plus `ORDER BY` composes correctly;
- `FETCH FIRST` above `ORDER BY` composes correctly;
- heap `ORDER BY` remains default-provider Derby-native;
- no provider-side ordered-scan contract appears.
