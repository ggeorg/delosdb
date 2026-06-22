# DelosDB Phase E Plan

Phase E is folded into the D sequence.  The immediate work is C27/C28; the
larger cost and concurrency tracks are named and deferred, not abandoned.

## C27 — Guarantee honesty

`DelosTableCapability` remains structural only: `FILTERABLE`, `INDEXABLE`,
`MUTABLE`, and `PROJECTABLE` describe which method surface exists.

`DelosTableGuarantee` is separate and semantic:

- `ROW_LOCKING`
- `DURABLE_RECOVERY_LOG`
- `SNAPSHOT_ISOLATION`

There is no `DURABLE_WAL` claim and no `POINT_IN_TIME_RECOVERY` claim.  Those
are not proven by the current code.

`DelosTableAccess.guarantees()` defaults to an empty set.  MVCC truthfully
declares `SNAPSHOT_ISOLATION` and `DURABLE_RECOVERY_LOG`.  The heap proof type
truthfully declares `ROW_LOCKING` and `DURABLE_RECOVERY_LOG`, but remains
compile-time/proof-only; live Derby heap SQL is not routed through the Delos
table-access contracts.  Storeless/base-only access declares no guarantees.

One real consumer belongs at the execution boundary in
`VersionedStorageSqlBridge`, not in `DelosVersionedStorageQueryTreeClassifier`.
The JavaCC / QueryTreeNode classifier stays classification-only.

## C28 — Leftover-predicate evaluation

C21 already proves partial pushdown removal: equality is pushed and `NOT_EQUAL`
remains in the mutable filter list.  C28 closes the remaining gap by evaluating
`NOT_EQUAL` as a caller-side leftover predicate above the adapter.

No function-call predicates, no expression model, and no new predicate operators
beyond `NOT_EQUAL` are part of C28.

## D resumes after C28

- C29 — JavaCC range SELECT classifier
- C30 — delete one matching range regex
- C31 — JavaCC INSERT classifier
- C32 — delete INSERT regex
- C33 — JavaCC DELETE equality classifier
- C34 — delete DELETE regex
- C35 — JavaCC UPDATE equality classifier
- C36 — delete UPDATE regex

C30 deletes only the standalone `>` range regex branch; the remaining range regex forms stay as fallback until their own parser replacements are retired.

## E3 — Cost estimation, deferred

Cost estimation should use a separate optional surface such as
`DelosCostableTableAccess extends DelosTableAccess`, not methods bolted onto
`DelosFilterableTableAccess`.  Heap cost mapping remains proof-only until live
heap routing exists.  MVCC can later delegate to
`VersionedStorageExecutionBridge.stats(...)`.

Making `BASE_UNCACHED_ROW_FETCH_COST` session-tunable is a separate labeled
Derby store/optimizer modification, not bundled into the contract-design step.

## E4 — Mutation concurrency primitive, deferred

Before writing code, choose one shape honestly:

- `validateMutable(context, rowIdentity)` / `prepareMutation(...)`: proves
  row-identity-boundary conflict detection and claims no locking.
- `tryLock(context, rowIdentity, mode) -> DelosLockResult`: a real row-lock or
  reservation primitive requiring actual reservation state.

Do not build a method that looks like locking but is implemented as optimistic
write-conflict validation only.

## Explicit non-goals

- No new SPI module.
- No `io.github.ggeorg.delosdb.spi.storage.access` package.
- No function-call predicates.
- No `DURABLE_WAL`.
- No `POINT_IN_TIME_RECOVERY`.
- No guarantee/capability checks inside the JavaCC classifier.
- No E3 or E4 implementation in C27/C28.
