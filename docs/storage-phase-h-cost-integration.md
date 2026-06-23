# Storage Phase H — Cost integration

Phase H starts after Phase G native execution and bridge-retirement cleanup are green.
It must not reopen SQL bridge routing or regex planning.

## H1 — Costable table-access seam

H1 adds a separate optional table-access interface:

```text
DelosCostableTableAccess extends DelosTableAccess
```

The interface exposes provider-backed table statistics and a coarse full-scan
estimate through `DelosTableCostEstimate`.  It is deliberately separate from
`DelosFilterableTableAccess`, `DelosIndexableTableAccess`, and
`DelosMutableTableAccess`.

H1 does not change Derby optimizer decisions.  It proves only that the native
MVCC adapter can expose cost data from the provider stats path after Derby has
already resolved a `delos_mvcc` table through catalog metadata.

Acceptance:

```text
CREATE TABLE APP.H1_COSTABLE (...) USING delos_mvcc
INSERT rows through native Derby execution
open native table access from TableDescriptor metadata
EngineMvccTableAccess implements DelosCostableTableAccess
capabilities include COSTABLE
estimateTableCost(...) reports provider-backed row/version counts
VersionedStorageSqlBridge.tryExecute(...) is not called
```

## H2 — MVCC cost mapping into native cost path

H2 maps provider-backed MVCC table statistics into Derby's native table-cost
observation point without consuming or replacing Derby optimizer estimates.

The seam is deliberately diagnostic-only:

```text
FromBaseTable.estimateCost(...)
  -> DelosNativeTableCostLookup.observeIfEnabled(...)
  -> DelosNativeTableRegistry.openNativeExecutionTableAccess(TableDescriptor)
  -> DelosCostableTableAccess.estimateTableCost(...)
  -> VersionedStorageExecutionBridge.stats(...)
```

Acceptance:

```text
CREATE TABLE APP.H2_COST_MAPPING (...) USING delos_mvcc
INSERT rows through native Derby execution
prepare/execute native SELECT *
DelosNativeTableCostLookup records MVCC logical/visible/physical/dead-version stats
recorded provider cost remains diagnostic-only
Derby CostEstimate is not mutated
VersionedStorageSqlBridge.tryExecute(...) is not called
```

## H3 — heap proof-only cost mapping

H3 maps inherited Derby heap cost data into the same `DelosTableCostEstimate`
shape through `EngineHeapTableAccessProof`.  This is intentionally proof-only:
heap SQL still runs through Derby heap execution, and Derby optimizer estimates
are not replaced.

The seam is diagnostic-only:

```text
FromBaseTable.estimateCost(...)
  -> DelosHeapCostProofLookup.observeIfEnabled(...)
  -> EngineHeapTableAccessProof.estimateTableCost(...)
  -> DelosTableCostEstimate
```

Acceptance:

```text
CREATE TABLE APP.H3_HEAP_COST_PROOF (...) -- no USING clause
INSERT rows through normal Derby heap execution
prepare/execute heap SELECT
DelosHeapCostProofLookup records heap proof-only cost mapping
heap proof adapter advertises COSTABLE
recorded heap cost remains proof-only
Derby CostEstimate is not mutated
VersionedStorageSqlBridge.tryExecute(...) is not called
```

## H4 — session-tunable Derby cost constant

H4 makes one inherited Derby store-cost constant session-tunable without adding
a provider-specific optimizer decision.  The selected constant is Derby's
`StoreCostController.BASE_UNCACHED_ROW_FETCH_COST`, which heap and btree store
cost controllers already use when estimating page/cache-miss fetch work.

The seam is intentionally narrow:

```text
SYSCS_UTIL.SYSCS_SET_DELOSDB_UNCACHED_ROW_FETCH_COST(double)
  -> current LanguageConnectionContext / StoreExecutionContext
  -> DelosStoreCostTuning.uncachedRowFetchCost()
  -> HeapCostController / BTreeCostController existing formulas
```

`SYSCS_CLEAR_DELOSDB_UNCACHED_ROW_FETCH_COST()` clears the session override
and restores Derby's default constant for the current connection.  Other
connections keep their own default or override.

Acceptance:

```text
normal heap CREATE TABLE / INSERT / SELECT still works
current SQL session can override BASE_UNCACHED_ROW_FETCH_COST
a separate SQL session still observes Derby's default constant
clear procedure restores the default for the tuned session
existing Derby CostEstimate path remains in place
VersionedStorageSqlBridge.tryExecute(...) is not called
```

Phase H is closed after H4.  Broader provider-specific optimizer decisions and
operator-selection changes are deferred to the next phase.

## Phase I handoff

After H4, the next active work is Phase I mutation concurrency.  I1 chooses
Option A from the deferred E4 plan: optimistic row-identity validation and
preparation, not row locking or reservation ownership.
