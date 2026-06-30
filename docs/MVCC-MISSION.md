# DelosDB MVCC Mission

`delos_mvcc` is an opt-in Derby-integrated storage engine. It is not the default heap path and does not replace Derby heap compatibility.

## Current architecture

```text
SQL
  -> Derby language / transaction layer
  -> Derby store/access conglomerate bridge
  -> delosdb-storage-api
  -> delosdb-storage-mvcc
```

The old standalone `VersionedStorageProvider` direction is not the mainline storage path. The serious path is the Derby access-method bridge.

## Current green storage capabilities

```text
CREATE TABLE ... USING delos_mvcc
INSERT / UPDATE / DELETE
commit / rollback
savepoint rollback for insert/update/delete/key reuse
same Derby transaction reads its own uncommitted MVCC writes
shutdown / reopen
process halt / reopen crash-boundary proof
mixed heap + MVCC transactions
multiple MVCC tables in one transaction
cross-connection visibility
READ COMMITTED behavior
REPEATABLE READ behavior
primary key / secondary index / unique index behavior
write/write conflict behavior through public SPI mapping
DROP TABLE cleanup
SQL vacuum/compress
active snapshot protects old versions during vacuum
cleanup resumes after snapshot ends
complex-workload durable consistency checker
crash/vacuum/checkpoint stale-metadata recovery
vacuum chain rebasing
Derby typed DataValueDescriptor durable row codec
SQL type coverage for normal Derby types
MVCC page checksums / torn-write detection
long VARCHAR payloads through overflow pages
overflow lifecycle through rollback/update/delete/vacuum/reopen
whole-page reuse
reusable-page index, recovery, and stale-entry protection
MVCC page cache lifecycle and bounded eviction
page-record headers
page-record consistency validation
slot accounting
page-scan consolidation
diagnostics consolidation
format/durable/bridge layering static gates
```

## Durable-format boundaries

Rejected in `delos_mvcc` durable rows:

```text
JAVA_OBJECT / SQL_USERTYPE / SERIALIZABLE_FORMAT_ID
BLOB
CLOB
```

Reason:

```text
JAVA_OBJECT / Derby UDT object values would reintroduce Java serialization risk.
BLOB/CLOB require a deliberate stream/locator/lifecycle design, not accidental row-codec support.
```

Long `VARCHAR` overflow support is green, but that is not equivalent to full Derby LOB support.

## Storage-engine completeness status

```text
Typed durable row codec
  green

Overflow / long-row payloads
  green first serious implementation

Page checksums / torn-write detection
  green

Free-page / allocation reuse
  green first whole-page reuse implementation

Buffer/cache service cleanup
  green first bounded-cache implementation

Slotted-page / record-header work
  green foundation: headers, validation, slot accounting

Concurrency refactor away from single monitor
  not started
```

## Current recommendation

The current MVCC storage hardening slice is complete enough to stop and avoid spinning on page-format micro-overlays.

The next major MVCC engine phase should be:

```text
MVCC concurrency monitor decomposition
```

Do not start that phase until the current full verification and static closeout gates are green.
