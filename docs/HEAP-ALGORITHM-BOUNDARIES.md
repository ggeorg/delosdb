# DelosDB Heap Algorithm Boundary Audit — Phase 4

This is an audit artifact, not a behavior change.

Phase 4 classifies inherited Derby heap/raw-store algorithms by compatibility risk after the
R5/R6/R7 lifecycle work closed green. The goal is to make the next cleanup decisions explicit
before any inherited-code algorithm is touched.

## Safety rules

* No Java runtime behavior change.
* No heap page format change.
* No raw log format change.
* No catalog behavior change.
* No DRDA or JDBC behavior change.
* No shared-service extraction is authorized by this audit.
* Shared-service extraction is not authorized by this audit.
* Do not touch without a format plan where the boundary is page-format or log-format sensitive.
* Derby heap/raw-store remains the compatibility anchor.
* Derby RawStore remains the only backup/restore authority; retired external MVCC artifacts reject explicitly.

## Classification vocabulary

| Classification | Meaning |
| --- | --- |
| `DERBY_COMPATIBILITY_ANCHOR` | Inherited Derby algorithm that defines heap compatibility. Changes require a compatibility gate. |
| `FORMAT_BOUNDARY` | Heap/raw-store page, record, field, allocation, or container format boundary. Do not change without a format-version plan. |
| `LOG_FORMAT_BOUNDARY` | Raw-store logging or recovery ordering boundary. Do not change without a log-format/recovery plan. |
| `READ_ONLY_DIAGNOSTIC` | Safe diagnostic surface that may be expanded if it remains read-only. |
| `CLEANUP_CANDIDATE` | Code may be clarified or extracted only if behavior, format, and log semantics are unchanged. |
| `SHARED_SERVICE_CANDIDATE` | Possible future shared service only after heap and MVCC both have concrete proof gates. |
| `DO_NOT_TOUCH_WITHOUT_FORMAT_PLAN` | High-risk inherited area where cleanup must stop until the format/recovery plan exists. |

## Audited inherited-code boundaries

### OpenHeap / HeapController

`OpenHeap` and `HeapController` are Derby heap compatibility anchors. They bridge the access layer to
raw-store page behavior. Cleanup is allowed only around names, helper extraction, diagnostics, or
explicit invariants. Insert, load, overflow, and row-location algorithms must stay compatibility-gated.

### OpenConglomerate / GenericController

`OpenConglomerate` and `GenericController` own inherited scan/controller state and unlogged row-count
behavior. These areas are cleanup candidates only when tests prove that heap scans, updates, locking,
open/reopen, and row-count semantics remain unchanged.

### BasePage / StoredPage

`BasePage` is both a latch/logging boundary and a page-mutation algorithm boundary. `StoredPage` is a
page-format boundary: record headers, field headers, overflow pointers, slot tables, logical data
streams, and page header placement must not be rewritten without a format plan.

### FileContainer / AllocPage

`FileContainer` and `AllocPage` are allocation-format and allocation-cache boundaries. They are useful
reference points for future shared allocation diagnostics, but not shared-service extraction targets yet.
The current audit only records the boundary.

### D_StoredPage / D_HeapController

`D_StoredPage` and `D_HeapController` are the safe side of heap modernization: read-only diagnostics.
They may expand as long as they do not repair, rewrite, compact, or mutate inherited heap state.

### RawStore backup boundary

RawStore backs up the same containers and log records used by heap and `delos_mvcc`. DelosDB adds
only fail-closed detection for artifacts from the retired external format; it does not copy, verify,
or restore a second persistence image.

## Reference models

* Derby is the compatibility anchor and source of heap/raw-store semantics.
* PostgreSQL and InnoDB are recovery, pruning, and storage-lifecycle reference models only.
* HerdDB is a Java-engine reference for checkpoint/page-cache lifecycle, not a heap replacement.
* MapDB is a compact-codec reference for future DelosDB-owned page/metadata encodings, not a heap format input.
* Stages 8.4 and 8.5 verified heap-segment and native-mirror experiments without changing the
  inherited format. Stage 8.7.2 removes both adapters from production. Any `java.lang.foreign` use in
  inherited heap or RawStore source is again a compatibility failure unless a new named proof and
  production decision explicitly authorize it.

## Next allowed work

This audit permits only bounded follow-up slices:

1. read-only heap diagnostics cost/performance audit,
2. shared-service readiness report,
3. explicit no-format-change helper extraction proposals,
4. backup/restore mixed-engine matrix expansion.

It does not permit heap page-format rewrite, raw-log rewrite, allocation algorithm replacement, or
shared page/cache/allocation service extraction.


## Stage 8.6 mapped-region boundary

The v1 heap/RawStore compatibility boundary rejects mapped regions in production. The test-only Stage
8.6 experiment demonstrates JDK 25 mapping behavior but concludes `NO_GO_FOR_V1_RAWSTORE`.
`CachedPage`, `StoredPage`, container allocation, page codec, logging, and recovery remain on the
verified byte-array plus positional heap/native I/O path. No inherited heap implementation class may
call `FileChannel.map` without a new format, recovery, lifecycle, and cross-platform proof.
