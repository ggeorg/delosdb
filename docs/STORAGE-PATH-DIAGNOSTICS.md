# DelosDB storage path diagnostics

This document defines the first diagnostic surface for explaining DelosDB storage-path choices.
It builds on `docs/STORAGE-ACCESS-DECISIONS.md` and remains diagnostic-only.

## Scope

A storage path diagnostic records why an existing path was chosen, rejected, or used as an explicit fallback.
It does not change plan selection.
It does not replace Derby's optimizer.
It does not change heap scan or B-tree scan behavior.
It does not change MVCC visibility rules.
It does not resurrect candidate indexes as SQL read authority.
It does not make Calcite, HerdDB, or MapDB runtime dependencies.

The API vocabulary lives in:

```text
org.apache.derby.iapi.store.types.DelosStorageAccessDecisionState
org.apache.derby.iapi.store.types.DelosStoragePathDiagnostic
```

## Diagnostic state vocabulary

* `CHOSEN` — the existing storage path was chosen by the normal authority for the operation.
* `REJECTED` — a shortcut or optional path was rejected before execution.
* `FALLBACK` — a shortcut declined and execution deliberately used a compatibility/authority path.
* `DIAGNOSTIC_ONLY` — the path exists only to produce a diagnostic or parity result.
* `TEST_ONLY` — the path exists only for tests, static gates, or proof harnesses.

## Diagnostic fields

`DelosStoragePathDiagnostic` records:

```text
decisionKind
state
providerId
segment
containerId
reason
readMode
shortcutSafe
rowIdCount
details
```

The record also exposes `diagnosticLine()` for stable, grep-friendly reports.
The line is intentionally simple and should remain safe for static reports, test assertions, and future JFR/logging bridges.

## Safety rules

The diagnostic record enforces several invariants:

* `EXPLICIT_COMPATIBILITY_FALLBACK` must be recorded as `FALLBACK`.
* `DIAGNOSTIC_ONLY` is reserved for `DIAGNOSTIC_CANDIDATE_PARITY_SCAN`.
* `TEST_ONLY` requires `TEST_ONLY_PATH`.
* rejected or fallback shortcut diagnostics cannot claim `shortcutSafe=true`.
* row-id count must be non-negative or `UNKNOWN_ROW_ID_COUNT`.

These checks are local validation rules for diagnostic objects only.
They do not alter storage execution.

## Current path families

The diagnostic surface can describe these existing path families from the previous access-decision audit:

* `FULL_HEAP_SCAN`
* `HEAP_INDEX_SCAN`
* `MVCC_FULL_SCAN`
* `MVCC_ORDERED_EQUALITY_LOOKUP`
* `MVCC_ORDERED_RANGE_SCAN`
* `MVCC_ROW_ID_LOOKUP`
* `MVCC_VISIBILITY_FILTERED_INDEX_LOOKUP`
* `DIAGNOSTIC_CANDIDATE_PARITY_SCAN`
* `EXPLICIT_COMPATIBILITY_FALLBACK`
* `TEST_ONLY_PATH`

## Reference influence

Calcite contributes the idea that rule applicability and rejection reasons should be explainable.
HerdDB contributes explicit storage/index-operation decision shapes.
PostgreSQL and InnoDB reinforce that visibility, recovery, and fallback authority must remain first-class algorithms.
MapDB is relevant later for compact row-id/key encoding, not for this diagnostic surface.

This slice does not import any of those frameworks.

## Later wiring

A later overlay may connect `DelosStoragePathDiagnostic` to runtime diagnostics after the current behavior is already selected.
That later overlay must still prove:

* diagnostics are side-effect free,
* Derby optimizer authority remains unchanged,
* heap compatibility paths remain unchanged,
* candidate-index paths remain diagnostic/parity only,
* MVCC ordered-index shortcuts still obey visibility and snapshot safety.
