# Storage Phase S9 — Fault Recovery Proof

S9 expands page-backed recovery proof coverage by using the storage I/O
`FaultInjectingPageVolume` decorator from `delosdb-storage-io`.

This phase does not change recovery policy. It proves that the existing
page-backed recovery paths observe injected write and force failures, while the
fault-injection volume remains an I/O-only decorator.

## What changed

- `PageBackedMvccTableStore` gained a package-private volume-injection seam.
- `PageBackedMvccFaultRecoverySmoke` exercises recovery through
  `FaultInjectingPageVolume` over `OffHeapPageVolume`.
- `verifyStoragePhaseS9FaultRecoveryProof` checks that the proof uses the fault
  decorator without moving recovery semantics into `delosdb-storage-io`.

## What is proven

- committed legacy recovery can replay through an injected page volume
- injected write failure is detected and does not materialize a recovered row
- injected force failure is detected
- strict recovery suppresses aborted mutations before touching a faulting write volume
- strict recovery fails on unresolved outcome before writing to the injected volume
- strict recovery detects committed write failure
- unresolved delete recovery preserves an existing committed record

## Boundaries preserved

- no heap/provider migration
- no DelosStorageDispatch
- no path() on DelosPageVolume
- no recovery policy in storage-io
- no transaction/commit/abort semantics in FaultInjectingPageVolume
- no page format change
