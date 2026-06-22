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

- C29 — JavaCC range SELECT classifier — done
- C30 — delete one matching range regex — done
- C31 — JavaCC INSERT classifier — done
- C32 — delete INSERT regex — done in this slice
- C33 — JavaCC DELETE equality classifier — done in this slice
- C34 — delete DELETE regex — done in this slice
- C35 — JavaCC UPDATE equality classifier — done in this slice
- C36 — delete UPDATE regex — done in this slice
- C37 — route-retirement closeout and regex inventory

C30 deletes only the standalone `>` range regex branch; the remaining range regex forms stay as fallback until their own parser replacements are retired.

C32 deletes only the direct `INSERT INTO ... VALUES (...)` regex branch; INSERT remains supported through Derby JavaCC / QueryTreeNode classification in JDBC/parser context.

C33 adds the Derby JavaCC / QueryTreeNode classifier for `DELETE FROM ... WHERE column = literal`.  C34 deletes the matching direct DELETE regex branch; DELETE remains supported through Derby JavaCC / QueryTreeNode classification in JDBC/parser context.

C35 adds the Derby JavaCC / QueryTreeNode classifier for `UPDATE ... SET column = literal WHERE column = literal`.  C36 deletes the matching direct UPDATE regex branch; UPDATE remains supported through Derby JavaCC / QueryTreeNode classification in JDBC/parser context.

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


## C36 update

C36 deletes the UPDATE equality regex route after C35 proved the matching Derby JavaCC / QueryTreeNode classifier. UPDATE remains supported through parser-classified planned routes and the C23 row-identity mutation path.

## C37 closeout

C37 is non-behavioral. It records and verifies the C27-C36 state: guarantee
honesty, caller-side NOT_EQUAL leftover filtering, parser-classified SELECT
range / INSERT / DELETE / UPDATE routes, and the exact regex routes already
retired. Remaining regex routes stay as explicit temporary fallbacks until each
has its own QueryTreeNode replacement and deletion proof.
