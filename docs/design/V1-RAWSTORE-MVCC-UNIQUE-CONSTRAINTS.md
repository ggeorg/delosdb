# RawStore MVCC primary-key and unique-constraint enforcement

## Scope

This milestone adds access-method-native enforcement for primary-key and unique constraints declared
inside `CREATE TABLE ... USING delos_mvcc` for the opt-in RawStore-backed format.

The SQL layer continues to create its inherited backing indexes. In addition, it passes compact,
provider-neutral constraint metadata into base-conglomerate creation. The RawStore MVCC access method
persists that metadata in the table control row and checks it before every INSERT and UPDATE. A caller
which reaches the base conglomerate directly therefore cannot bypass uniqueness by avoiding the SQL
backing-index path.

## Neutral DDL metadata

The shared store API defines one internal conglomerate property:

```text
derby.access.uniqueConstraints.v1
```

Each definition records:

```text
strict or duplicate-null mode
deferrable flag
zero-based base-table column positions
```

`CreateTableConstantAction` derives the definitions from inline primary-key and unique constraints.
The encoding contains no MVCC implementation class and no Lucene type.

The first RawStore format supports immediate constraints. A deferrable unique constraint fails closed
before table storage is created because commit-time deferred validation is not yet implemented.

## Persisted table metadata

The RawStore table control row contains optional trailing unique-constraint metadata after the ordered
index container identifier:

```text
constraint count
for each constraint:
    duplicate-null flag
    key-column count
    key-column positions
```

Older shorter control rows remain readable and mean that no native unique metadata is present. There
is no boot-time rewrite and no external metadata file.

## Enforcement boundary

INSERT and UPDATE enforce uniqueness before allocating or appending a new version.

The check acquires table containers in the normal mutation order:

```text
database metadata reservation
    -> table metadata container
    -> version container
    -> ordered-index container
```

After the table locks are held, the check captures the latest committed database sequence. It does not
use the transaction's older read snapshot because a writer must reject a key committed after that
snapshot rather than create a duplicate.

The RawStore-backed ordered index supplies candidate `MvccRowId` values. Every candidate is then read
through the authoritative MVCC version chain at the latest committed sequence plus the caller's own
uncommitted versions. Complete composite keys are compared with Derby typed comparison.

The ordered index is therefore an accelerator, not the uniqueness authority.

## SQL semantics

Primary keys use strict uniqueness.

A nullable SQL UNIQUE key uses duplicate-null semantics:

```text
if any key column is NULL:
    the row does not conflict with another NULL-containing key
else:
    all key columns must be distinct from every visible row
```

Composite keys are compared across all declared columns. UPDATE excludes its own stable logical row
from the conflict candidates. DELETE makes the old key reusable in the same transaction because the
caller's tombstone is visible to the authoritative chain read.

A conflict raises SQLState:

```text
23505
```

## Concurrency, rollback, and recovery

The conservative RawStore table locks serialize concurrent writers. A second writer attempting the
same key waits for the first transaction outcome. It fails after the first commits and succeeds after
the first rolls back.

RawStore remains the only physical and transactional authority:

```text
constraint metadata
ordered-index entries
base versions
directory heads
savepoint rollback
transaction rollback
commit record
crash recovery
file and memory storage
```

Both inherited RawStore crash boundaries are covered. A key from a transaction halted before the
RawStore commit record remains available; a key committed before the halt remains occupied after
recovery.

## Executable proof

Focused runtime gate:

```text
:delosdb-tests:runDelosMvccRawStoreUniqueConstraintTest
```

Permanent architecture gate:

```text
delosMvccRawStoreUniqueConstraintStaticAnalysis
```

The proof covers:

```text
persisted primary-key and unique metadata
single-column and composite keys
duplicate-null semantics
direct base-conglomerate insertion bypassing SQL backing indexes
UPDATE conflict and row preservation
DELETE followed by same-transaction key reuse
savepoint rollback and later key reuse
clean reopen
concurrent commit and rollback outcomes
halt before RawStore commit
halt after RawStore commit before publication
jdbc:derby:memory:
```

## Current limits

This milestone does not add:

```text
ALTER TABLE ADD CONSTRAINT native metadata migration
CREATE UNIQUE INDEX native metadata
retrofit of native metadata into older RawStore control rows
deferrable or initially deferred uniqueness
foreign-key enforcement changes
incremental ordered-index page splits or merges
final fine-grained row/key locking
vacuum or obsolete-entry removal
```

Those capabilities must preserve the same rule: the authoritative MVCC version chain decides visible
rows, and RawStore decides the transaction outcome.
