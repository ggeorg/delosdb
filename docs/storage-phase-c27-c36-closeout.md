# DelosDB Storage Phase C27-C36 Closeout

This closeout records the second Phase C routing slice: guarantee honesty,
leftover predicate evaluation, and controlled regex retirement after Derby
JavaCC / QueryTreeNode replacement routes.

## Closed work

```text
C27 — guarantee honesty
C28 — caller-side NOT_EQUAL leftover-predicate evaluation
C29 — JavaCC / QueryTreeNode range SELECT classifier
C30 — first range-regex deletion: standalone >
C31 — JavaCC / QueryTreeNode INSERT VALUES classifier
C32 — INSERT VALUES regex deletion
C33 — JavaCC / QueryTreeNode DELETE equality classifier
C34 — DELETE equality regex deletion
C35 — JavaCC / QueryTreeNode UPDATE equality classifier
C36 — UPDATE equality regex deletion
```

## Contract truth

`DelosTableCapability` remains structural only. It says which method surface
exists: `FILTERABLE`, `INDEXABLE`, `MUTABLE`, and `PROJECTABLE`.

`DelosTableGuarantee` is separate. It says which semantic guarantees the access
implementation advertises: `ROW_LOCKING`, `DURABLE_RECOVERY_LOG`, and
`SNAPSHOT_ISOLATION`.

The JavaCC / QueryTreeNode classifier is still classification-only. It does not
read capabilities, guarantees, cost, lock state, or provider internals.

## Regex routes retired in this slice

The following direct regex routes are retired:

```text
SELECT * FROM table WHERE column = literal
SELECT * FROM table WHERE column > literal
INSERT INTO table VALUES (...)
DELETE FROM table WHERE column = literal
UPDATE table SET column = literal WHERE column = literal
```

These statements remain supported through Derby parser context:

```text
Derby JavaCC parser
  -> QueryTreeNode classifier
  -> PlannedRoute
  -> table-access execution path
```

Mutation remains row-identity based:

```text
DelosFilterableTableAccess.scan(...)
  -> DelosRowIdentity
  -> DelosMutableTableAccess.update/delete(...)
```

## Remaining regex fallback routes

The bridge still keeps regex fallback routes for shapes that have not yet been
retired:

```text
CREATE TABLE ... USING delos_mvcc
CREATE INDEX ... ON ...
SELECT * FROM table
SELECT * FROM table ORDER BY column [ASC|DESC]
SELECT COUNT(*) FROM table
SELECT * FROM table WHERE column >= literal
SELECT * FROM table WHERE column <= literal
SELECT * FROM table WHERE column < literal
SELECT * FROM table WHERE column BETWEEN literal AND literal
```

The range classifier can recognize `>`, `>=`, `<=`, and `<`, but only the direct
`>` range regex branch has been deleted so far. The other range regex fallbacks
remain until their own deletion proofs are added.

## Honest boundary

MVCC still enters through:

```text
EmbedStatement -> VersionedStorageSqlBridge.tryExecute(...)
```

This work reduces regex routing and strengthens the table-access execution
boundary. It does not yet move MVCC behind Derby binder/compiler/executor table
metadata or remove the `EmbedStatement` interception point.

## Deferred tracks

E3 cost estimation and E4 mutation-concurrency primitives remain named and
scoped, but not implemented in C27-C36.
