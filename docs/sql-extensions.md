# DelosDB SQL extension surface

DelosDB keeps Derby SQL/JDBC compatibility as the default behavior and adds
small, explicit SQL extension points only where a product seam already exists.
This page documents the current supported surface. It is not a roadmap for
future syntax.

## Supported syntax

### Index providers

```sql
CREATE INDEX index_name ON table_name(column_name) USING btree;
```

`btree` is the built-in Derby-compatible index provider. Omitting `USING btree`
is equivalent to using the default provider:

```sql
CREATE INDEX index_name ON table_name(column_name);
```

`memory` is a registered IndexProvider v2 proof provider, but it is not a
SQL-creatable physical Derby index yet:

```sql
CREATE INDEX index_name ON table_name(column_name) USING memory;
```

The statement above is intentionally rejected during validation. The memory
provider proves the provider-owned runtime abstraction, not Derby executor/storage
integration.

Unknown index providers are rejected during binding before physical index work
starts.

### Storage providers

```sql
CREATE TABLE table_name (
  id int
) USING heap;
```

`heap` is the built-in Derby-compatible storage provider. Omitting `USING heap`
is equivalent to using the default provider:

```sql
CREATE TABLE table_name (
  id int
);
```

Unknown storage providers are rejected during binding before physical storage
work starts.

## Supported read-only visibility routines

The registered built-in DelosDB providers are visible through the Derby-style
utility surface:

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

The type visibility routine reports the provider name, SQL type name, JDBC type,
Java type, nullable flag, and comparable flag. It is metadata-only. It does not
add new SQL type semantics.

## Not supported yet

The current surface deliberately does not include:

- `SHOW EXTENSIONS`
- external provider loading
- `CREATE INDEX ... USING memory` as a physical SQL index
- custom physical storage engines
- JSON/type-provider syntax
- planner replacement syntax
- public cost-model provider loading
- new provider families

## Verification

The SQL extension surface is covered by the existing product smokes:

```bash
./gradlew indexProviderV2Smoke
./gradlew indexProviderMetadataSmoke
./gradlew storageProviderSyntaxSmoke
./gradlew costModelProviderStoreCostSmoke
./gradlew extensionRegistrySqlVisibilitySmoke
./gradlew typeProviderSqlVisibilitySmoke
./gradlew :delosdb-tests:runDerbyLangSuite
```
