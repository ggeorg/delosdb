# Storage Phase O1 — Heap provider-parity gate

O1 introduces one unified heap read/write gate for the supported heap live routes that were proven separately in M3, N2, and N3.

```text
delosdb.storage.phaseO.heapProviderParity=true
```

When this property is enabled, the existing heap live routes are enabled together:

```text
heap SELECT  -> DelosHeapLiveTableScanResultSet
heap INSERT  -> DelosHeapInsertResultSet
heap UPDATE  -> DelosHeapUpdateResultSet
heap DELETE  -> DelosHeapDeleteResultSet
```

This is a consolidation step, not a locking closeout.

## What O1 proves

O1 proves that an ordinary heap table can use the live heap SELECT / INSERT / UPDATE / DELETE routes under one property. This moves heap closer to the final A destination without pretending heap locking and reservation parity are done.

## What O1 does not claim

```text
No heap row-reservation claim.
No generic DelosMutableTableAccess.tryLock(...).
No generic reserveMutation(...).
No heap locking parity.
No deletion of the proof-only heap adapter yet.
No default-on provider parity yet.
```

Unsupported heap shapes continue to use the earlier route checks and fall back to Derby where those checks reject the shape.

## Next step after O1

The next safe step is O2: decide whether the unified heap parity gate can become the default for supported heap shapes, or whether one more hardening proof is needed first.
