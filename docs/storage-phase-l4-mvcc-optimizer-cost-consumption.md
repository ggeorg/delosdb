# Storage Phase L4: delos_mvcc optimizer cost consumption

## Decision

L4 consumes provider cost for delos_mvcc only.

This milestone turns the existing H2/H3/H4 cost infrastructure into one narrow optimizer-consumption proof:

```text
delos_mvcc table statistics
  -> DelosCostableTableAccess.estimateTableCost(...)
  -> DelosNativeTableCostLookup
  -> FromBaseTable.estimateCost(...)
  -> Derby CostEstimate returned to the optimizer
```

The default remains Derby-compatible. Consumption is enabled only by an explicit proof property:

```text
delosdb.storage.phase.l4.nativeOptimizerCostConsumption=true
```

## What L4 changes

`DelosNativeTableCostLookup` was already called from `FromBaseTable.estimateCost(...)` after Derby's inherited store-cost path had produced a `CostEstimate`. Before L4, that lookup was diagnostic-only. L4 keeps the old diagnostic property and adds a separate consumption property.

When the L4 property is enabled and the table descriptor says `USING delos_mvcc`, the lookup:

```text
1. opens the catalog-backed native delos_mvcc table access;
2. asks EngineMvccTableAccess for DelosTableCostEstimate;
3. validates the provider estimate is safe to consume;
4. replaces Derby's current CostEstimate cost and row counts;
5. records the lookup as consumed.
```

This is the first point where a Delos provider-owned table-cost estimate can affect the `CostEstimate` object returned from `FromBaseTable.estimateCost(...)`, which is the object Derby compares while choosing access paths and join orders.

## What L4 deliberately does not change

No heap routing change.
No heap cost consumption.
No mutation behavior change.
No locking behavior change.
No `FromBaseTable.generate()` change.
No ASM emission change.
No `GenericResultSetFactory` heap branch.
No bridge resurrection.
No generic provider contract expansion.

Heap remains Derby-native. `EngineHeapTableAccessProof` remains proof-only. `DelosHeapCostProofLookup` remains diagnostic/proof-only and does not call `CostEstimate.setCost(...)`.

## Why this is safe after K1

K1 concluded that heap scan/cost parity is feasible incrementally, but heap mutation and locking parity must be deferred. L4 follows that rule: it does not move heap. It consumes cost only for the one live Delos provider that can honestly implement `DelosCostableTableAccess` today:

```text
delos_mvcc -> EngineMvccTableAccess -> DelosCostableTableAccess
```

The consumption point is also narrower than a new optimizer integration layer. It reuses the existing Derby optimizer boundary:

```text
FromBaseTable.estimateCost(...)
```

That means the generated execution shape and result-set factory branch remain unchanged.

## Acceptance

L4 is complete when the guard proves:

```text
- the new consumption property exists;
- the old H2 diagnostic property still exists;
- delos_mvcc provider estimates can replace Derby's current optimizer CostEstimate;
- the recorded lookup says consumed;
- optimizer cost and row counts match the consumed provider estimate;
- a delos_mvcc SELECT still runs through the native result-set route;
- ordinary heap SELECT remains Derby default-provider routing;
- heap SELECT records no delos_mvcc cost consumption;
- DelosHeapCostProofLookup remains proof-only and does not call CostEstimate.setCost(...).
```

## Route after L4

L4 does not start heap activation. The route remains:

```text
L3  — broader delos_mvcc SQL coverage
L1  — MVCC-specific row reservation / real mutation concurrency
M1  — heap scan candidate, no SQL routing
M2  — heap scan shadow branch
M3  — heap SELECT live route for supported shapes
```
