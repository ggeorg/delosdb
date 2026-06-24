# Storage Phase S3 — DelosPageVolume contract

S3 introduces the narrow page-volume contract in `delosdb-storage-io`.

The contract uses only storage-io-owned page primitives from S2:

- `DelosPage`
- `DelosPageId`

It deliberately does not import `MvccPage`, `MvccPageId`, MVCC storage
classes, Derby engine classes, heap classes, SQL execution classes, provider
dispatch classes, transaction policy, or recovery policy.

S3 adds no file-backed implementation and performs no caller migration. The
file-backed boundary is deferred to S4 so the contract can remain stable and
source-clean before implementation work begins.

Contract responsibilities:

- read a complete page
- write a complete page
- allocate a page
- count pages
- force the durability boundary
- expose construction-time sync policy
- close the volume

Forbidden contract responsibilities:

- `path()`
- transaction state
- MVCC visibility
- recovery policy
- mutation/outcome log operations
- SQL/provider/heap concepts

Acceptance:

- `delosdb-storage-io` has no upward dependency on `delosdb-storage-mvcc`.
- `DelosPageVolume` uses `DelosPage` / `DelosPageId` only.
- Boundary verification still passes.
- Existing O5 and C7 storage smokes remain green.
