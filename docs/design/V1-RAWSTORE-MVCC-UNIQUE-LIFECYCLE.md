# RawStore MVCC unique-metadata lifecycle

## Scope

This design extends access-method-native uniqueness beyond inline `CREATE TABLE` definitions.
For a RawStore-backed `delos_mvcc` table, the following SQL DDL paths now maintain the same
persisted RawStore metadata used by statement-time INSERT and UPDATE enforcement:

```text
ALTER TABLE ... ADD CONSTRAINT ... PRIMARY KEY
ALTER TABLE ... ADD CONSTRAINT ... UNIQUE
ALTER TABLE ... DROP CONSTRAINT ...
CREATE UNIQUE INDEX ...
DROP INDEX ...
```

The inherited Derby catalog and backing-index lifecycle remains intact. The additional metadata is
owned by the base access method and is changed inside the same RawStore transaction as the catalog and
backing-index changes.

## Neutral access-method seam

`delosdb-derby-store-api` defines the optional internal contract:

```text
AccessMethodUniqueConstraintLifecycle
```

A base-table `ConglomerateController` may implement:

```text
validateUniqueConstraintDefinition
addUniqueConstraint
dropUniqueConstraint
```

The engine opens the base conglomerate with update/table/serializable semantics and invokes the hook
only when the controller implements it. Heap and retained providers remain unchanged. The interface
uses only zero-based base-row positions, duplicate-null semantics, and the deferrable flag; it exposes
no MVCC implementation type.

## CREATE ordering

For `ALTER TABLE ADD CONSTRAINT` and `CREATE UNIQUE INDEX`, validation occurs before physical index or
catalog publication. The access method rejects unsupported deferrable uniqueness with SQLState
`0A000` before DDL state is created.

After Derby has successfully created or shared the inherited backing index and catalog descriptor, the
base access method adds one logical unique definition. That addition:

```text
joins the caller's existing RawStore transaction
acquires the normal table-container locks
ensures the RawStore ordered index exists
reads the latest persisted unique metadata
checks all currently visible rows for duplicates
rewrites the table control row transactionally
updates only the transaction-local descriptor cache
```

If duplicate committed data exists, SQLState `23505` is raised before native metadata is published.
The normal statement/transaction rollback also removes any inherited DDL work performed earlier in the
same transaction.

## DROP ordering

`DROP CONSTRAINT` and direct `DROP INDEX` remove one matching logical definition before completing the
inherited catalog/backing-index drop. Both operations use the same RawStore transaction, so any later
failure restores the native metadata together with Derby catalog and physical-index state.

Tables created before native unique metadata existed may have an inherited unique descriptor but an
empty RawStore metadata list. In that compatibility case, DROP is a no-op at the access-method hook and
the inherited Derby lifecycle proceeds normally.

## Logical reference counts

The control row stores a list of logical definitions rather than a deduplicated set. This is required
because Derby may have more than one logical constraint or index over the same key, including shared
physical backing conglomerates.

Adding another definition appends another entry. Dropping one descriptor removes one matching entry.
Uniqueness remains enforced until the last matching logical definition has been removed.

Strict and duplicate-null definitions remain distinct:

```text
CREATE UNIQUE INDEX:
    strict key uniqueness

nullable SQL UNIQUE constraint:
    duplicate NULL-containing keys allowed
```

Dropping one does not silently change or remove the other.

## Existing-row validation

Before an ADD operation publishes metadata, the RawStore ordered index is opened under the normal
metadata -> version -> ordered-index lock order. The latest committed database sequence is captured,
then every visible logical row is reread through the authoritative MVCC version chain. Composite keys
use Derby typed comparison and nullable SQL UNIQUE definitions apply duplicate-null semantics.

The ordered index remains an accelerator and lock boundary; base-version visibility remains the final
authority.

## Rollback, savepoint, recovery, and memory

The control-row rewrite is a normal logged RawStore page update. Therefore:

```text
statement rollback
transaction rollback
savepoint rollback
one RawStore commit record
crash recovery
file-backed databases
jdbc:derby:memory:
```

all use the inherited storage lifecycle. No sidecar constraint file, separate DDL log, second commit
decision, or provider-specific recovery pass exists.

## Executable evidence

Focused runtime task:

```text
:delosdb-tests:runDelosMvccRawStoreUniqueLifecycleTest
```

Permanent architecture task:

```text
delosMvccRawStoreUniqueLifecycleStaticAnalysis
```

The focused proof covers:

```text
ALTER TABLE ADD UNIQUE commit and rollback
ALTER TABLE DROP UNIQUE commit and rollback
CREATE UNIQUE INDEX and DROP INDEX
shared logical definitions and one-at-a-time removal
existing duplicate-data rejection before metadata publication
deferrable fail-closed behavior
no catalog residue after rejected DDL
direct base-conglomerate duplicate rejection
savepoint and transaction rollback
clean reopen
halt after MVCC stamping before RawStore commit
halt after RawStore commit before in-memory publication
jdbc:derby:memory:
```

## Current boundaries

The lifecycle hook remains deliberately narrow. Deferrable or initially deferred uniqueness fails
closed; foreign-key lifecycle changes, retroactive discovery of pre-existing catalog constraints, and
constraint-name persistence in the RawStore control row remain separate concerns. Catalog identity,
authorization, dependency management, and inherited backing-index ownership remain Derby engine
responsibilities.

## Schema-lock integration

Ordinary DML takes a shared table-schema lock while native unique validation and ADD/DROP operations
take an exclusive table-schema lock through Derby's inherited lock manager. This prevents control-row
uniqueness metadata from changing concurrently with table mutation while keeping catalog, backing-index,
native metadata, and data changes under one RawStore outcome. See
`V1-RAWSTORE-MVCC-LOGICAL-LOCKING.md`.
