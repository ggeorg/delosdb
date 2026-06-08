# Derby Compatibility Policy

DelosDB is a modular database platform built on the proven Apache Derby codebase.
That platform direction is additive: existing Derby-style SQL and JDBC behavior
must continue to work unless a compatibility break is explicitly documented and
gated by a future DelosDB major version.

## Compatibility rule

DelosDB extension features are added beside Derby behavior, not in place of it.

For index providers this means:

```text
CREATE INDEX idx ON t(c)
```

continues to mean the default Derby B-tree index behavior.

The future DelosDB syntax:

```text
CREATE INDEX idx ON t(c) USING btree
```

is equivalent to the Derby-compatible form while `btree` is the default built-in
provider. If a statement does not name a provider, DelosDB treats it as if it
named the default provider.

## Optional provider syntax

DelosDB now accepts the additive provider form for the built-in default provider:

```sql
CREATE INDEX idx ON t(c) USING btree;
```

For this phase, `btree` is the only accepted provider name. Unknown providers
fail before execution with a clean unsupported-feature diagnostic. The statement
continues through the existing Derby index creation path; no catalog metadata,
optimizer behavior, or storage behavior changes are introduced by this syntax.

## Provider defaults

The built-in default index provider is:

```text
ExtensionType.INDEX / btree / builtin / ENABLED
```

Existing Derby indexes and indexes created without a provider name must resolve
to `btree`. Provider metadata, parser support, catalog persistence, and optimizer
integration must all preserve that default.


## Parser compatibility guard

The SQL compiler must not load DelosDB provider adapter or SPI implementation
classes when compiling ordinary Derby-compatible `CREATE INDEX` statements. The
optional provider clause is parser-level plumbing at this phase: unnamed indexes
and `USING btree` normalize to the local default provider name and continue down
the existing Derby B-tree creation path. This preserves jar/classpath behavior for
existing Derby-style runtime scenarios.

## Catalog compatibility

Runtime SQL compatibility and Apache Derby catalog interchangeability are not the
same promise.

DelosDB should preserve existing Derby SQL/JDBC application behavior. New DelosDB
catalog metadata may eventually make a database a DelosDB catalog rather than an
Apache Derby catalog. When that happens, the change must be explicit, documented,
and migration-aware.

Until catalog metadata is introduced for providers:

```text
no provider metadata  -> default index provider btree
unknown provider      -> clean DelosDB diagnostic
missing provider      -> clean DelosDB diagnostic
```

## Guardrails

Do not break Derby compatibility by accident:

```text
Do not change CREATE INDEX semantics without an explicit DelosDB feature.
Do not change the default physical index implementation.
Do not require provider metadata for existing indexes.
Do not expose Derby Monitor or store internals as public SPI.
Do not remove Derby package names or compatibility jars as part of SPI work.
Do not make provider costing affect plans until fallback behavior is proven.
```

The safe sequence is:

```text
1. default provider identity = btree
2. optional SQL syntax maps to that identity for `USING btree`
3. existing syntax defaults to that identity
4. catalog metadata defaults old indexes to that identity
5. optimizer bridge falls back to Derby costing if provider declines
```
