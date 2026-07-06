# DelosDB SQL Extensions

DelosDB keeps inherited Derby SQL behavior by default and adds explicit opt-in surfaces for DelosDB experiments.

## MVCC table storage

Create an MVCC-backed table explicitly:

```sql
CREATE TABLE t (
    id int primary key,
    value varchar(100)
) USING delos_mvcc;
```

Without `USING delos_mvcc`, DelosDB uses the inherited Derby-compatible heap path:

```sql
CREATE TABLE t (
    id int primary key,
    value varchar(100)
);
```

## Current MVCC type boundary

Normal Derby SQL scalar values covered by the typed durable row codec are supported by the current MVCC tests.

Unsupported in `delos_mvcc` durable rows:

```text
JAVA_OBJECT / Derby UDT object values
BLOB
CLOB
```

These rejections are deliberate compatibility and safety boundaries, not parser limitations.

## Vacuum/compress

MVCC SQL tests use the in-place MVCC compress/vacuum path when testing MVCC page lifecycle. Full Derby table-compress/rebuild behavior is not a substitute for MVCC page-lifecycle diagnostics.

## Default-provider property

The normal no-property path remains Derby heap storage. Any property-gated default-provider behavior is a guarded candidate path and must not be treated as a production default-store flip.

## MVCC isolation behavior

The MVCC bridge uses an explicit read-view policy:

```text
READ COMMITTED and weaker
  statement-scoped current read view

REPEATABLE READ / SERIALIZABLE
  transaction-scoped stable read view
```

This is a storage-engine behavior checkpoint, not new SQL syntax. Applications continue to use Derby/JDBC transaction isolation controls.

## Consistency diagnostics

DelosDB keeps the inherited consistency-check surface and extends the storage diagnostics layer with provider-neutral consistency reporting.

Heap tables participate in `SYSCS_UTIL.SYSCS_CHECK_TABLE(...)` through a read-only heap sanity checker. Mixed heap/MVCC diagnostics are exposed through DelosDB storage metadata/diagnostic APIs using a stable report shape.

Consistency diagnostics do not repair storage, rewrite pages, or change table contents.
