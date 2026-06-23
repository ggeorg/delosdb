# Storage Phase L3.3 — delos_mvcc OR-predicate coverage

## Decision

L3.3 adds a narrow, property-gated `delos_mvcc` SELECT path for supported SQL
`OR` predicate shapes without adding a generic Delos OR/disjunction contract.

This is intentionally not a new provider-wide predicate model. Derby already has
a concrete `Qualifier[][]` shape for OR-list qualifiers: leading AND predicates
are placed in `qualifiers[0]`, and each trailing OR predicate group is placed in
`qualifiers[1..N]`. L3.3 keeps that Derby source truth local to the Derby-native
result-set boundary.

## Property gate

```text
delosdb.storage.phaseL33.nativeOrPredicateResidual=true
```

When the property is enabled for a non-default `delos_mvcc` table:

```text
GenericResultSetFactory
  -> DelosTableScanResultSet
  -> EngineMvccTableAccess.scan(...)
  -> local OR qualifier/residual evaluation at the result-set boundary
  -> Derby ExecRow materialization
```

## Scope

Supported for this milestone:

```text
- delos_mvcc SELECT only
- OR groups composed of currently supported qualifier operators
- equality OR
- range OR
- NULL/equality OR
- leading AND predicates composed with OR groups
```

Not included:

```text
- no heap live-provider route
- no heap OR pushdown
- no generic DelosPredicateOperator.OR
- no generic disjunction tree/interface
- no MultiProbe replacement
- no mutation predicate expansion
- no optimizer rewrite
- no FromBaseTable.generate() change
- no ASM emission change
- no bridge resurrection
```

## Why not a generic OR contract yet?

The current strategic rule still applies:

```text
Do not make a generic contract method until two providers can implement it honestly.
```

Only `delos_mvcc` can consume this behavior today. Heap remains Derby-native, so
L3.3 keeps OR evaluation inside `DelosTableScanResultSet` as a Derby/result-set
residual behavior rather than pretending that the Delos storage contract has a
provider-neutral disjunction model.

## Acceptance

`verifyStoragePhaseL33OrPredicateResidual` proves:

```text
- Derby OR-list qualifier source shape remains visible
- delos_mvcc OR SELECT uses the native table-scan route
- equality OR works
- string equality OR works
- range OR works
- NULL/equality OR works
- leading AND predicates compose with OR groups
- heap OR SELECT remains Derby-native
- no fake generic Delos OR/DISJUNCTION operator appears
- no live heap Delos routing appears
```
