# DelosDB shared-service readiness report

This report is a conservative decision point for deciding which heap/MVCC seams
can become shared services.

The rule is intentionally strict: only read-only reporting seams are currently
allowed to be extracted as shared services. Execution authority remains owned by
Derby heap/raw-store code or DelosDB MVCC code unless a later executable proof
proves otherwise.

## Current classifications

| Candidate | Current classification | Extraction stance |
| --- | --- | --- |
| Storage diagnostics read model | Ready for read-only shared service | May be shared only as read-only diagnostics |
| Storage lifecycle read model | Ready for read-only shared service | May be shared only as read-only lifecycle reporting |
| Statistics/cost read model | Report-only | Keep diagnostic-only; Derby optimizer remains authority |
| Backup/restore orchestration | Report-only | Verify together, but do not extract execution |
| Buffer management | MVCC-only proof | Heap cache/raw-store boundary is not ready |
| Page codec | Heap compatibility boundary | Do not share heap and MVCC page codecs |
| Ordered-index authority | MVCC-only proof | Keep heap BTree and MVCC ordered pages provider-local |
| Purge/vacuum | MVCC-only proof | Heap compress/purge semantics differ from MVCC purge |

## Executable proof

`StorageSharedServiceReadinessReportTest` creates one inherited heap table and
one `using delos_mvcc` table. It asks the storage diagnostics registry for a
provider-neutral shared-service readiness report and verifies that:

* only diagnostics and lifecycle read models are extraction-ready;
* extraction is limited to read-only services;
* statistics/cost and backup/restore remain report-only;
* buffer, page-codec, ordered-index, and purge/vacuum execution remain
  provider-owned;
* heap and MVCC rows remain readable after the report is produced and after
  shutdown/reopen.

## Non-goals

This slice does not extract a shared service. It does not change heap page
format, raw log format, MVCC page format, optimizer behavior, DRDA behavior,
backup/restore execution, buffer replacement, ordered-index execution, or purge
policy.
