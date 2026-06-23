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

H2 should map `VersionedStorageExecutionBridge.stats(...)` into the native cost
path that will later feed Derby costing.  It should still avoid broad optimizer
rewrites.

## H3 — heap proof-only cost mapping

Heap mapping remains proof-only until live heap SQL is routed through the Delos
table-access contracts.

## H4 — session-tunable Derby cost constant

Making one Derby cost constant session-tunable is a separate, labeled optimizer
/store change.  Do not bundle it into H1.
