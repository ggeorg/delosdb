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

Heap mapping remains proof-only until live heap SQL is routed through the Delos
table-access contracts.

## H4 — session-tunable Derby cost constant

Making one Derby cost constant session-tunable is a separate, labeled optimizer
/store change.  Do not bundle it into H1.
