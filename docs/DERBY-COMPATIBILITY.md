# Derby compatibility policy

DelosDB is derived from Apache Derby 10.17.1.0. Compatibility is a product commitment and an
engineering constraint.

## Compatibility objectives

DelosDB preserves Derby behavior where it provides practical value to applications, databases, and
operational tooling. It does not preserve every inherited internal structure merely because it is
old.

## Protected boundaries

### Heap and raw-store formats

The inherited heap and raw store remain the default storage path and the durable compatibility
foundation. DelosDB does not retrofit MVCC record formats, version metadata, or page structures into
existing Derby heap pages.

### Catalog and SQL behavior

Default SQL, catalog, metadata, privilege, and JDBC behavior remains Derby-compatible unless a
DelosDB extension is requested explicitly.

### DRDA protocol

`delosdb-server` preserves Derby-compatible DRDA framing and client behavior. Protocol
modernization may improve scheduling, resource ownership, diagnostics, cancellation, and shutdown,
but does not replace DRDA with a new wire format.

### Runtime artifacts

The pre-1.0 distribution retains Derby-compatible jar names and module identities where required by
existing launchers and applications.

## Explicit DelosDB behavior

### MVCC table selection

The MVCC engine is selected in SQL:

```sql
CREATE TABLE t (
    id INTEGER PRIMARY KEY,
    value VARCHAR(100)
) USING delos_mvcc;
```

There is no production system-property route that silently redirects ordinary heap execution into
an experimental or proof implementation. Table metadata and the registered access method determine
storage behavior.

### MVCC durable-value boundaries

The current `delos_mvcc` implementation rejects unsupported durable values explicitly:

```text
JAVA_OBJECT / SQL_USERTYPE / SERIALIZABLE_FORMAT_ID
    rejected with SQLState 0A000 before an MVCC base conglomerate is created

BLOB / CLOB
    supported through the materialized and overflow lifecycle covered by the MVCC gates
```

The v1.0 contract requires stable SQLStates and rejection before partial catalog or durable mutation.
Supported heap behavior remains unchanged.

### Isolation

Heap isolation preserves verified Derby behavior.

The MVCC implementation provides statement snapshots for `READ COMMITTED` and transaction
snapshots for `REPEATABLE READ`. Access to a `delos_mvcc` table at JDBC `SERIALIZABLE` rejects with
SQLState `0A000` before a scan or write opens. Heap `SERIALIZABLE` behavior remains unchanged. This
is a truthful current implementation boundary; true MVCC `SERIALIZABLE` remains required for the
intended v1 contract rather than being accepted as a permanent post-v1 limitation.

## Compatibility changes

A compatibility-sensitive change requires:

- a named public or durable boundary;
- source comparison with Derby 10.17.1.0 where relevant;
- positive and negative regression tests;
- upgrade, reopen, or protocol proof where applicable;
- documentation of the deliberate difference;
- a rollback or migration strategy for durable changes.

## Reuse direction

Derby remains a valuable design reference for:

```text
typed values
heap and overflow lifecycle
page checks and diagnostics
cache ownership
allocation and free-space management
catalog and transaction integration
DRDA behavior
```

DelosDB implements MVCC-specific logical row/version structures as a RawStore access method when
inherited heap structures are inseparable from heap page layout, physical row locations, or
lock-manager-centered isolation. RawStore remains the shared physical persistence/recovery authority.
