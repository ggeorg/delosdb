# DelosDB Optimizer Cost Authority Audit

This audit records the optimizer/cost boundary after the first algorithmic audit
pass. It is deliberately an authority audit, not a new optimizer feature.

Derby optimizer remains the authority.

DelosDB may expose storage statistics, cost diagnostics, and opt-in store-cost
adapter estimates, but those values must enter the compiler only through
inherited Derby seams such as `StoreCostController`, explicit diagnostic
surfaces, or named compatibility gates. DelosDB must not create a hidden MVCC
optimizer, a Calcite planner replacement, or a parallel statistics authority.

## Authority model

| Area | Authority | DelosDB role | Rule |
| --- | --- | --- | --- |
| SQL rewrite, join enumeration, plan choice | Derby optimizer | compatibility anchor | Do not replace without a full optimizer compatibility plan. |
| Native store costing | Derby `StoreCostController` path | safe seam for bounded hints | Storage hints must preserve a valid Derby baseline. |
| MVCC physical storage statistics | DelosDB MVCC storage | diagnostic or opt-in input | Must not bypass Derby `SYSSTATISTICS` or cost-controller semantics. |
| Analyze/update-statistics lifecycle | Derby statistics daemon and catalog behavior | lifecycle checkpoint for MVCC snapshots | `optimizerAuthority=derby` remains the explicit marker. |
| IndexProvider cost bridge | legacy DelosDB diagnostic | diagnostic-only metadata proof | Enabled spelling must not replace Derby cost. |
| CostModelProvider bridge | DelosDB internal adapter proof | explicit opt-in store-cost adapter | Safe only when the request has a safe Derby baseline. |
| Storage path diagnostics | DelosDB diagnostic surface | explanation and tracing | Diagnostics must not choose plans. |

## Classifications

### DERBY_OPTIMIZER_AUTHORITY

`OptimizerImpl`, `CostEstimateImpl`, `FromBaseTable`, and the inherited Derby
compiler remain the authority for SQL optimization. DelosDB can add diagnostics
and storage-aware estimates, but it must not replace join enumeration, predicate
semantics, or plan selection in this slice.

### STORE_COST_CONTROLLER_SEAM

`StoreCostController` is the compatible store-cost seam. Any DelosDB-owned cost
input that affects planning must be adapted through this seam, and the native
Derby store-cost controller must remain usable when DelosDB providers are absent,
disabled, stale, or rejected.

### MVCC_STATISTICS_HINT

`MvccStoreCostController` may derive estimates from `DelosStorageStatistics` only
through the explicit MVCC storage-statistics cost diagnostic property. This keeps
MVCC physical statistics visible without creating a separate optimizer truth
source.

### EXPLICIT_OPT_IN_COST_MODEL

`CostModelProvider` and `StoreCostControllerBridge` are internal adapter proofs.
They are not public optimizer SPI. Their enabled mode must remain explicit and
must consume provider estimates only when the request reports a safe Derby
baseline.

### LEGACY_INDEX_PROVIDER_DIAGNOSTIC

The older IndexProvider cost bridge is diagnostic-only. Its diagnostic and
enabled spellings may record provider estimates, but they must not replace Derby
optimizer cost.

### DIAGNOSTIC_ONLY_COST_REPORT

`DelosStorageCostIntegration`, storage cost reports, storage path diagnostics,
JFR path-decision events, and optimizer-adjacent reports may explain cost inputs
and storage paths, but they must not decide plans.

### REFERENCE_MODEL_ONLY

Calcite, HerdDB, H2, PostgreSQL, InnoDB, MapDB, and JDK 25 are reference models
for metadata, cost explanation, storage statistics, page/cache algorithms, and
validation. They are not optimizer dependencies in this audit.

## Guardrails

* Derby optimizer remains the authority.
* Do not replace Derby optimizer.
* Do not add Calcite as a DelosDB optimizer dependency.
* Do not create a parallel MVCC optimizer statistics channel.
* Do not make MVCC storage statistics a hidden default plan-choice source.
* Do not resurrect candidate indexes as optimizer authority.
* Do not consume provider cost without a safe Derby baseline.
* Keep IndexProvider cost diagnostics diagnostic-only.
* Keep storage path diagnostics diagnostic-only until a named runtime-wiring gate
  explicitly says otherwise.
* No optimizer behavior change in this audit.

## Verification

```bash
./gradlew delosOptimizerCostAuthorityAuditStaticAnalysis
```

Normal compatibility verification remains unchanged:

```bash
./gradlew s0CloseoutVerification
./gradlew :delosdb-tests:runDelosMvccSqlIntegrationTest
./gradlew :delosdb-storage-api:check :delosdb-storage-derby:check :delosdb-storage-bridge:check :delosdb-storage-mvcc:check
```
