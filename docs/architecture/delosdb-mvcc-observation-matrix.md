# DelosDB MVCC observation matrix

This document closes the first Phase 24 observation pass. It records what the current MVCC
observation proofs expose, what they intentionally do not expose, and what remains future work.

DelosDB remains a Java 25, Gradle-only, Derby-compatible database kernel built from the Apache
Derby codebase. The MVCC work in Phase 24 is a set of selected internal modernization proofs. It is
not a claim that Derby storage or Derby transaction behavior has been replaced.

## Scope rule

Phase 24 observation objects are read-only diagnostics over existing MVCC internals.

They must not:

```text
change Derby-compatible SQL/JDBC behavior
change storage routing
change MVCC visibility semantics
change recovery behavior
promote MVCC internals to public API
make the storage bridge a shared storage architecture layer
claim production features that the current code does not implement
```

## Observation matrix

| Concept | Current observation status | Proof | Honest limit |
| --- | --- | --- | --- |
| Transaction snapshot | Observed for the native in-memory MVCC path. | Module 24A | Not wired to the inherited Derby SQL transaction path. |
| Visible rows | Observed for native in-memory MVCC snapshots and page-backed MVCC snapshots. | Modules 24A and 24B | Observation remains inside MVCC test/proof paths. |
| Logical rows | Observed for native, page-backed, and page-volume MVCC paths. | Modules 24A, 24B, and 24C | Does not imply SQL routing through these paths. |
| Physical versions | Observed for native, page-backed, and page-volume MVCC paths. | Modules 24A, 24B, and 24C | Does not implement version pruning. |
| Dead-version estimate | Observed for the native in-memory MVCC path. | Module 24A | It is a diagnostic estimate, not vacuum. |
| Visibility horizon | Observed through oldest retained visibility / retained snapshot state. | Module 24A | It is not a full vacuum horizon implementation. |
| Page-backed storage files | Observed through page file, row-directory file, and mutation-log file facts. | Module 24B | File presence is not page-file parsing or recovery proof. |
| Page/version access | Observed through page count, row-directory heads, logical rows, and physical versions. | Module 24B | Observation does not alter page allocation or version layout. |
| Write-ahead-log file state | Observed as file presence/state at the page-volume boundary. | Module 24C | No WAL replay position, group commit, or replay proof is claimed. |
| Checkpoint state | Observed as page-volume checkpoint status: `WRITTEN` after rewrite and `VALID` after reopen/validation. | Module 24C | No checkpoint scheduler or production recovery policy is claimed. |
| Durable-state presence | Observed at the page-volume state-store boundary. | Module 24C | Does not make page-volume the default SQL storage path. |
| Next inherited row id | Observed at the page-volume state-store boundary. | Module 24C | Used for diagnostic visibility only. |

## Explicit non-claims

The Phase 24 observation proofs do not implement or claim:

```text
production MVCC storage replacement
SQL routing to page-volume MVCC
a Derby storage-engine replacement
engine-level MVCC transaction integration
WAL replay position
group commit
checkpoint scheduling
vacuum
version pruning
row-level concurrency changes
optimizer/provider-aware MVCC planning
```

## Current conclusion

Phase 24 has made selected MVCC internals observable through small executable proofs. That is enough
for the current MVCC observation lane. Further MVCC work should be opened only when the next proof
needs new behavior, such as safe version pruning, WAL replay evidence, checkpoint scheduling, or
SQL-facing storage routing.

The next roadmap phase should remain separate: optimizer and planner experiments should start from
observation and explanation, not from replacing Derby planning or storage behavior.
