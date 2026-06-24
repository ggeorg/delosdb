# Storage Phase O2 — permanent guard cleanup

## Purpose

O1 proved the unified, property-gated heap read/write provider-parity route by
behavior. O2 cleans up the verification wiring that was still carrying the
extra N1.6 detour and old exact-text/history-marker guard habit.

This is cleanup only. It does not add provider behavior.

## Decision

Retire N1.6 as a drift-only milestone.

The accepted route is:

```text
N1.5 — RowChanger-backed heap mutation adapter proof
N2   — heap INSERT live path
N3   — heap DELETE / UPDATE live path
O1   — unified heap provider-parity gate
```

Permanent storage verification now points at the O1 behavior proof instead of
old N1.x/N2 history markers.

## What O2 changes

```text
- remove N1.6 from storage-native closeout wiring
- make N2 depend directly on N1.5
- make verifyStorageNativeExecutionCloseout point at O1 behavior
- make verifyDelosDbPermanentStorageGuards point at O1 behavior
- add a narrow cleanup script for the stale N1.6 source/doc/generated files
```

## What O2 does not change

```text
- no production SQL route changes
- no provider contract changes
- no heap locking or reservation claim
- no removal of real behavior smokes
- no broad cleanup patterns
```

## Next step

After O2 is green, continue O-phase provider consolidation. The likely next
step is to move heap read/write access behind a named live heap provider facade,
without making heap locking/reservation claims.
