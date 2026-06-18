# DelosDB SQL Extension Surface

DelosDB keeps Derby SQL/JDBC compatibility as the default behavior and adds small
SQL extension points only where a product seam already exists. This file records
the supported surface, not speculative future syntax.

## Index providers

Default Derby-compatible form:

```sql
CREATE INDEX index_name ON table_name(column_name);
```

Explicit default-provider form:

```sql
CREATE INDEX index_name ON table_name(column_name) USING btree;
```

`btree` is the built-in Derby-compatible SQL-backed index provider.

`memory` is a registered `IndexProvider` v2 proof provider, but it is not a
SQL-creatable physical Derby index yet:

```sql
CREATE INDEX index_name ON table_name(column_name) USING memory;
```

The statement above is intentionally rejected during validation. The memory
provider proves the provider-owned runtime abstraction, not Derby executor/storage
integration.

## Storage providers

Default Derby-compatible form:

```sql
CREATE TABLE table_name (
  id int
);
```

Explicit Derby-compatible heap form:

```sql
CREATE TABLE table_name (
  id int
) USING heap;
```

`heap` is the built-in Derby-compatible storage provider.

Experimental MVCC form:

```sql
CREATE TABLE table_name (
  id int primary key,
  name varchar(40)
) USING delos_mvcc;
```

`delos_mvcc` is the guarded versioned-storage provider. It is not the default
store.

The engine-level default-provider candidate property can route bare `CREATE
TABLE` statements through `delos_mvcc` only when explicitly enabled:

```text
-Ddelosdb.storage.defaultProvider=delos_mvcc
```

Do not confuse this property with earlier provider-local smoke properties. The
`defaultProvider` property is the engine-level candidate path for bare SQL.

## Read-only visibility routines

Registered built-in DelosDB providers are visible through:

```sql
VALUES SYSCS_UTIL.DELOSDB_EXTENSIONS();
```

Current built-in extension entries include provider families such as:

```text
cost_model heap
cost_model btree
function   delos
index      btree
index      memory
storage    heap
storage    delos_mvcc
type       derby
```

Built-in Derby type metadata is visible through:

```sql
VALUES SYSCS_UTIL.DELOSDB_TYPES();
```

The type routine reports provider name, SQL type name, JDBC type, Java type,
nullable flag, and comparable flag. It is metadata-only and does not add new SQL
type semantics.

## Not supported yet

The current surface deliberately does not include:

- `SHOW EXTENSIONS`;
- external provider loading;
- physical SQL indexes backed by `index memory`;
- global MVCC default-store flip;
- row locks / `SELECT FOR UPDATE` MVCC semantics;
- custom JSON/type-provider syntax;
- planner replacement syntax;
- public cost-model provider loading;
- new provider families;
- SQL `EXPLAIN MVCC` / research explain surfaces.

## Verification

```bash
./gradlew indexProviderV2Smoke
./gradlew indexProviderMetadataSmoke
./gradlew storageProviderSyntaxSmoke
./gradlew costModelProviderStoreCostSmoke
./gradlew extensionRegistrySmoke
./gradlew extensionRegistrySqlVisibilitySmoke
./gradlew typeProviderSqlVisibilitySmoke
./gradlew mvccDefaultProviderCandidateMatrix
./gradlew :delosdb-tests:runDerbyLangSuite
```
