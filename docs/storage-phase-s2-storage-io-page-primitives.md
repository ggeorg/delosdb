# Storage Phase S2 — storage I/O page primitives

S2 establishes neutral page primitives in `delosdb-storage-io` before adding
`DelosPageVolume`.

The milestone intentionally does not migrate callers and does not introduce a
page-volume contract yet. Its purpose is to close the module-boundary risk: the
future `DelosPageVolume` must use storage-io-owned page primitives rather than
importing page classes from `delosdb-storage-mvcc`.

Added primitives:

- `DelosPageId`
- `DelosPage`
- `DelosPageIo`

Boundary rules:

- `delosdb-storage-io` must not depend on `delosdb-storage-mvcc`.
- Storage I/O packages must stay neutral and must not use `mvcc` in the package name.
- Raw page-format facts may live in `delosdb-storage-io`.
- Transaction, visibility, recovery policy, SQL row, heap, and provider semantics must not move into `delosdb-storage-io`.
- `DelosPageVolume` is still deferred to S3.
