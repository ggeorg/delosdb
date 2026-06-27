# DelosDB Storage API

Source-owner module for storage contracts and Derby-compatible store value
bridge types.

This module owns provider-neutral contracts such as:

- `DelosTableAccess`
- `DelosMutableTableAccess`
- `DelosFilterableTableAccess`
- `DelosIndexableTableAccess`
- `DelosTableCapability`
- `DelosTableGuarantee`
- `DelosTableIdentity`
- `DelosRow`
- `DelosRowIdentity`
- `StoreDataValue`
- `StoreRowLocation`
- `StoreTypeSupport`
- `org.apache.derby.iapi.store.types.*`

It must remain contracts-only. It must not own inherited Derby heap/raw/btree
implementation, MVCC implementation, WAL/checkpoint/vacuum/page-volume I/O, or
bridge code.

For now these classes are still patched/packaged into `derby.jar` for Derby
runtime compatibility while source ownership becomes explicit.
