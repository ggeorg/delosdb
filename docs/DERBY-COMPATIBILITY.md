# Derby Compatibility Policy

DelosDB is a fork of Apache Derby 10.17.1.0. Compatibility is a project constraint, not an afterthought.

## Compatibility boundaries

### Heap/raw-store disk format

The inherited Derby heap/raw-store path remains the default and is compatibility-locked. Do not change the Derby heap page format to support MVCC experiments.

### DRDA/JDBC wire protocol

`delosdb-server` remains a Derby-compatible DRDA server. Do not replace the DRDA/JDBC wire protocol with protobuf, gRPC, JSON, Netty-specific framing, or another protocol inside the compatibility server.

### SQL/JDBC behavior

Default SQL/JDBC behavior should remain Derby-compatible unless a DelosDB extension is explicitly requested.

## Opt-in DelosDB storage

The MVCC storage engine is selected explicitly:

```sql
CREATE TABLE t (id int) USING delos_mvcc;
```

or by a guarded default-provider property for candidate paths. The global default remains the inherited heap path.

## MVCC durable-format rules

`delos_mvcc` is greenfield enough to reject unsafe or incomplete durable values:

```text
JAVA_OBJECT / SQL_USERTYPE / SERIALIZABLE_FORMAT_ID
  rejected in MVCC durable rows

BLOB / CLOB
  rejected in MVCC durable rows until a full lifecycle design exists
```

This does not change inherited Derby heap compatibility.

## Reuse rule

Reuse direction is mostly Derby heap/raw-store patterns into MVCC:

```text
typed value codec
long-row/overflow design
page checksums
cache/buffer-pool ideas
free-space/allocation-page ideas
slotted-page/record-header ideas
```

Use Derby self-contained services where safe. Reimplement MVCC-native formats where Derby code is coupled to raw-store logging, heap disk format, physical RowLocation assumptions, or lock-manager-centered isolation.
