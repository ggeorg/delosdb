# Heap diagnostics performance audit

This document records the executable heap diagnostics performance/read-only proof.

The purpose is not to set a brittle wall-clock budget. The purpose is to make
heap diagnostic cost observable while preserving Derby-compatible heap behavior.

## Current slice

`HeapDiagnosticsPerformanceAuditTest` exercises a Derby-compatible heap table
with an index, then repeatedly inspects the inherited heap storage diagnostics
through `DelosStorageDiagnosticsRegistry.inspectHeapStoragePerformance(...)`.

The report records:

* first and last `DelosHeapStorageDiagnostics` snapshots,
* iteration count,
* total elapsed nanoseconds,
* minimum elapsed nanoseconds,
* maximum elapsed nanoseconds,
* derived average nanoseconds,
* whether read-only observations stayed stable across the run.

## Safety rules

The audit must remain read-only:

* no heap page rewrite,
* no raw log rewrite,
* no catalog rewrite,
* no DRDA behavior change,
* no optimizer behavior change,
* no storage-format change,
* no repair action hidden inside diagnostics.

The timing report deliberately avoids fixed thresholds. A fixed threshold would
be noisy across machines and would not prove Derby compatibility. The executable
proof asserts shape and stability instead: repeated diagnostics return valid
snapshots, table/index bytes are unchanged, SQL results remain correct, and the
same database reopens after inspection.

## Reference model

This follows the algorithmic modernization rule: measure and classify inherited
heap diagnostics before extracting or optimizing shared services. It is closer to
PostgreSQL page/visibility inspection and InnoDB status diagnostics than to a
new heap storage algorithm.
