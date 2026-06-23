# Storage Phase L3.4 — delos_mvcc projection variants

## Decision

L3.4 keeps projection semantics Derby-owned while allowing native `delos_mvcc`
SELECT coverage to handle common projection shapes.

This is not provider-side projection pushdown. The native table scan still asks
`EngineMvccTableAccess` for `DelosProjection.all()`, materializes a Derby base-row
candidate, and then uses Derby's existing `accessedCols`/`getCompactRow(...)`
shape to return the compact row expected by the surrounding Derby result-set
pipeline.

## Why this is the safe slice

Derby's normal heap scan does not make `ProjectRestrictResultSet` consume raw
base-row ordinals for every query shape. `TableScanResultSet` materializes a base
candidate row, then compacts it through the saved `FormatableBitSet` column map
when the compiler chose a subset of base columns.

The native `DelosTableScanResultSet` must mirror that boundary before broader
projection variants are considered complete. Otherwise queries such as
`SELECT score FROM t WHERE id = 2` can accidentally return the first materialized
base column instead of the selected compact column.

## Scope

In scope:

- read Derby's saved `colRefItem` / `accessedCols` for native delos_mvcc scans
- materialize full base-row candidates from native MVCC row values
- compact those candidates with `getCompactRow(candidate, accessedCols, false)`
- add a separate L3.4 proof gate for full-scan projection variants
- prove selected, reordered, expression, NULL-value, and full-scan projection shapes

Out of scope:

- provider-side `DelosProjection.columns(...)` pushdown
- heap live-provider routing
- mutation projection behavior
- locking or row reservation
- optimizer changes
- ordered scan guarantees
- bridge resurrection

## Guard truth

`verifyStoragePhaseL34ProjectionVariants` proves:

- `DelosTableScanResultSet` has an L3.4 projection-variant property
- native delos_mvcc scan reads Derby `accessedCols`
- native delos_mvcc scan compacts rows through Derby's existing compact-row path
- provider projection pushdown is not introduced
- selected/reordered/expression projection variants work through native delos_mvcc scan
- NULL projected values survive native row materialization and compaction
- heap projection SELECT remains Derby-native default-provider execution

## Route impact

L3.4 continues the A-lite route chosen by K1:

- harden `delos_mvcc` under the storage contract first
- leave heap Derby-native
- defer heap mutation and locking parity
- do not make a new generic contract method until two providers can implement it honestly
