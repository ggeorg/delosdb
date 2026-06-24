# Storage compensation cleanup

This phase is not a new storage feature.

It compensates for drift created during the K/L/M/N/O provider-parity sequence.
The final architectural proof remains O5:

- `delos_mvcc` has live SELECT / INSERT / UPDATE / DELETE / cost / MVCC reservation behavior.
- `heap` has live supported SELECT / INSERT / UPDATE / DELETE / cost behavior.
- Heap locking remains Derby-owned; no MVCC-style heap reservation or generic `tryLock` is claimed.

## Cleanup decision

The permanent closeout path should not re-run every historical milestone smoke.
Those smokes were useful while building the route, but they produced too much friction once O5 became the final closeout proof.

The compact permanent route is now:

```text
storagePhaseC7StabilizationSmoke
  -> storagePhaseO5FullProviderParityCloseoutSmoke
  -> verifyStorageNativeExecutionCloseout
  -> verifyDelosDbPermanentStorageGuards
```

Historical milestone smoke sources may remain available for archaeology or focused debugging, but they are no longer part of the permanent closeout chain.

## Stale files removed by cleanup script

The cleanup script removes only named stale drift files and named generated outputs:

- the drift-only N1.6 doc/smoke/database/build output;
- one-off cleanup scripts that were only needed during the transition;
- generated build outputs for old milestone smokes no longer wired into the compact closeout.

No production source files are removed by this compensation cleanup.
