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


## Legacy heap provider identity

The public storage provider name `heap` remains unchanged. Internally it denotes
the inherited Derby-compatible heap/raw/access/WAL store, not a new DelosDB-native
storage implementation. This distinction is important during the Derby store
surgery: the legacy store may be modularized later, but its disk format, package
identity, boot wiring, and default behavior remain compatible.


## Legacy Derby store module extraction

The inherited Derby-compatible heap/raw/access/WAL store now has a real source
ownership module:

```text
delosdb-storage-derby
  org.apache.derby.iapi.store.*
  org.apache.derby.impl.store.*
```

The package and class names remain unchanged for compatibility. The module move
is a source-ownership and build-boundary change, not a disk-format change and not
a default-storage change.

derby.jar still includes the inherited Derby store runtime classes. Existing
users do not need to manually add a separate storage jar yet. A separate runtime
jar split is a later explicit packaging decision, not part of this closeout.

Permanent boundary rules:

```text
delosdb-storage-mvcc must not import Derby store internals.
legacy Derby store code must not import MVCC internals.
inherited store packages must not be split across modules.
```

The current inherited-storage closeout gates are:

```bash
./gradlew :delosdb-storage-derby:compileLegacyDerbyStorage \
          storagePhaseO5FullProviderParityCloseoutSmoke \
          storagePhaseC7StabilizationSmoke
```

## MVCC compatibility rule

The A44--A52 semantic-correctness sprint is green for the guarded MVCC candidate
path:

```text
history-pruned / missing-history safety;
vacuum watermark integration;
command/statement visibility;
SQL statement-boundary integration;
durable transaction outcome logging;
unresolved outcome recovery;
captured visibility snapshot;
SQL compatibility candidate matrix.
```

This does not make `delos_mvcc` the default store. legacy Derby heap compatibility
remains the safe default until a later explicit promotion decision and broader
compatibility/recovery/performance gates.

## Research-facing behavior

Research-friendly traces and readable test output may be added to proof gates,
but they must not create new SQL semantics or change Derby-compatible behavior.
New SQL explain surfaces, teaching profiles, deterministic schedulers, and
benchmark/artifact pipelines are future work, not compatibility commitments.
