# DelosDB optimizer authority

DelosDB retains the inherited Derby optimizer as the authority for SQL rewrite, join enumeration,
predicate semantics, plan selection, and the normal statistics catalogue. DelosDB storage providers may
contribute bounded statistics and cost information only through explicit Derby-compatible seams.

## Authority model

| Area | Authority | DelosDB role | Rule |
| --- | --- | --- | --- |
| SQL rewrite, join enumeration, plan choice | Derby optimizer | compatibility anchor | DelosDB does not replace the optimizer. |
| Native store costing | Derby `StoreCostController` path | bounded storage estimates | A valid Derby baseline remains available. |
| MVCC physical statistics | RawStore-backed MVCC storage | diagnostic or explicit cost input | Storage statistics do not become a second plan authority. |
| Analyze/update-statistics lifecycle | Derby statistics/catalog lifecycle | MVCC lifecycle integration | `optimizerAuthority=derby` remains explicit. |
| Storage-path diagnostics | DelosDB diagnostics | explanation and tracing | Diagnostics never choose plans. |

## Store-cost integration

`StoreCostController` is the compatible planning seam. `MvccStoreCostController` may derive estimates
from `DelosStorageStatistics` only through the explicit MVCC storage-statistics cost path. The native
Derby store-cost controller remains usable when provider estimates are absent, disabled, stale, or
rejected.

`CostModelProvider` and `StoreCostControllerBridge` are internal adapters, not a public optimizer SPI.
They may consume provider estimates only when the request carries a safe Derby baseline. The older
`IndexProvider` cost bridge remains diagnostic-only and cannot replace Derby cost.

## Guardrails

- Do not replace Derby join enumeration or plan selection with a second optimizer.
- Do not add Calcite as a hidden optimizer dependency.
- Do not create a parallel MVCC statistics authority.
- Do not make provider statistics a hidden default plan-choice source.
- Keep diagnostic cost reports and JFR events observational.
- Any storage estimate that affects planning must preserve Derby-compatible fallback behavior.

## Verification

Optimizer and cost-adapter ownership is covered by the normal repository-integrity and SQL regression
suites:

```bash
./gradlew delosRepositoryIntegrityStaticAnalysis --console=plain
./gradlew :delosdb-tests:delosFunctionalTests --console=plain
```

Storage-provider behavior remains covered by the normal MVCC, concurrency, and recovery suites.
