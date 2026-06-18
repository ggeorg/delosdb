# Derby Compatibility Policy

DelosDB preserves Derby-compatible SQL/JDBC behavior as the default user-facing
contract during modernization.

Compatibility rule:

```text
No explicit DelosDB opt-in -> inherited Derby behavior
Explicit DelosDB opt-in    -> guarded DelosDB experimental behavior
```

## Default storage behavior

Without a DelosDB storage property or explicit `USING` clause, normal Derby heap
storage remains the default:

```sql
CREATE TABLE t (id int primary key, name varchar(20));
```

This must continue to create/open through the Derby-compatible heap path.

The guarded MVCC candidate path is enabled only with:

```text
-Ddelosdb.storage.defaultProvider=delos_mvcc
```

When that property is set, bare `CREATE TABLE` statements may route through the
`delos_mvcc` candidate path. This is a candidate gate, not a global default flip.

Explicit experimental MVCC syntax is also supported by the versioned-storage SQL
bridge:

```sql
CREATE TABLE t (id int primary key, name varchar(20)) USING delos_mvcc;
```

## Extension compatibility

DelosDB extension points must not silently change Derby behavior. An extension
surface can be visible before it is SQL-creatable, but unsupported SQL must fail
cleanly during validation.

Examples:

```sql
CREATE INDEX i ON t(id) USING btree;
```

`btree` maps to the normal Derby-compatible index provider.

```sql
CREATE INDEX i ON t(id) USING memory;
```

`memory` is a provider-owned runtime proof. It is intentionally rejected as a
physical SQL index until a real Derby executor/storage bridge exists for that
provider.

## MVCC compatibility rule

`delos_mvcc` is not allowed to become the default store until these classes of
proofs are green:

```text
history-pruned / missing-history safety;
command/statement visibility;
SQL statement-boundary integration;
durable transaction outcome recovery;
vacuum watermark integration;
SQL compatibility candidate matrix;
crash/recovery matrix.
```

Until then, Derby heap compatibility is the safe default.

## Research-facing behavior

Research-friendly traces and readable test output may be added to proof gates,
but they must not create new SQL semantics or change Derby-compatible behavior.
New SQL explain surfaces, teaching profiles, deterministic schedulers, and
benchmark/artifact pipelines are future work, not compatibility commitments.
