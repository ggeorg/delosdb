# Derby Compatibility Policy

DelosDB preserves Derby-compatible SQL and JDBC behavior while opening selected
engine seams for DelosDB extension work. Compatibility means existing Derby-style
applications should continue to run unless a DelosDB compatibility break is
explicitly documented and gated by a future major version.

## Default behavior stays Derby-compatible

These statements keep their Derby meaning:

```sql
CREATE INDEX idx ON t(c);

CREATE TABLE t (
  id int
);
```

DelosDB treats omitted providers as the built-in Derby-compatible defaults:

```text
index provider   -> btree
storage provider -> heap
```

## Additive provider syntax

DelosDB accepts explicit provider syntax for the built-in defaults:

```sql
CREATE INDEX idx ON t(c) USING btree;

CREATE TABLE t (
  id int
) USING heap;
```

The explicit form is additive. It must not change the physical Derby behavior of
ordinary indexes or heap tables.

## Registered but not SQL-creatable providers

`index memory` is registered as the second `IndexProvider` implementation and is
used by the v2 provider-owned runtime proof. It is intentionally not a physical
Derby SQL index yet:

```sql
CREATE INDEX idx ON t(c) USING memory;
```

That statement must fail cleanly during validation until DelosDB builds a real
executor/storage bridge for non-B-tree physical indexes.

## Cost compatibility

Native provider-cost consumption belongs to `CostModelProvider` through Derby's
store-cost seam:

```text
RAMTransaction.openStoreCost()
  -> StoreCostControllerBridge
  -> CostModelProviderResolver
```

The v2 cost seam supports two built-in providers:

```text
factory id 0 -> heap CostModelProvider
factory id 1 -> btree CostModelProvider
```

The old optimizer-side `FromBaseTable` / `IndexProviderCostBridge` hook is kept
only as legacy diagnostic history. It may expose a provider estimate for review,
but it must not mutate planner cost.

## Catalog compatibility

Runtime SQL compatibility and Apache Derby catalog interchangeability are not the
same promise. DelosDB may persist DelosDB-specific metadata where needed, but the
change must be explicit and migration-aware.

Current conservative rules:

```text
old index descriptor without provider metadata -> btree
CREATE INDEX without USING                     -> btree
CREATE INDEX ... USING btree                   -> btree
CREATE TABLE without USING                     -> heap
CREATE TABLE ... USING heap                    -> heap
```

## Visibility routines

The DelosDB SQL utility routines are read-only metadata surfaces:

```sql
VALUES SYSCS_UTIL.DELOSDB_EXTENSIONS();
VALUES SYSCS_UTIL.DELOSDB_TYPES();
```

They add system routine permission rows. Derby tests with fixed
`SYS.SYSROUTINEPERMS` counts must use `DelosDbTestBaselines` rather than magic
numbers.

## Guardrails

Do not break Derby compatibility by accident:

- do not change the default B-tree index implementation;
- do not change the default heap table implementation;
- do not require provider metadata for existing Derby-created objects;
- do not expose Derby Monitor or raw store internals as public SPI;
- do not let diagnostic-only provider hooks mutate optimizer plans;
- do not remove Derby package names or compatibility jars as part of SPI work.
