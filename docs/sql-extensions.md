# DelosDB SQL Extension Surface

DelosDB keeps Derby SQL/JDBC compatibility as the default behavior and adds small
SQL extension points only where a product seam already exists. This file
records the supported surface, not future syntax.

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

Explicit default-provider form:

```sql
CREATE TABLE table_name (
  id int
) USING heap;
```

`heap` is the built-in Derby-compatible storage provider. Unknown storage
providers are rejected before physical storage work starts.

## Read-only visibility routines

Registered built-in DelosDB providers are visible through:

```sql
VALUES SYSCS_UTIL.DELOSDB_EXTENSIONS();
```

Current built-in extension entries include:

```text
cost_model heap
cost_model btree
function   delos
index      btree
index      memory
storage    heap
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
- custom physical storage engines;
- JSON/type-provider syntax;
- planner replacement syntax;
- public cost-model provider loading;
- new provider families.

## Verification

```bash
./gradlew indexProviderV2Smoke
./gradlew indexProviderMetadataSmoke
./gradlew storageProviderSyntaxSmoke
./gradlew costModelProviderStoreCostSmoke
./gradlew extensionRegistrySmoke
./gradlew extensionRegistrySqlVisibilitySmoke
./gradlew typeProviderSqlVisibilitySmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```
