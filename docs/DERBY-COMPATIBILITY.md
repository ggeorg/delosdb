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
continues through the existing Derby index creation path. Provider metadata is
stored as descriptor metadata. The older optimizer-side
`delosdb.optimizer.indexProviderCost` switch is now legacy diagnostic-only;
native provider cost consumption belongs to CostModelProvider through the
StoreCostController seam. The default behavior remains Derby-compatible.

## Provider defaults

The built-in default index provider is:

```text
ExtensionType.INDEX / btree / delos / ENABLED
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

Provider metadata is introduced conservatively as descriptor metadata:

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

## CREATE INDEX provider metadata plumbing

`CREATE INDEX ... USING btree` is intentionally additive. The parsed provider
name is carried into the internal `CreateIndexConstantAction` as metadata, but
it does not change Derby's execution path yet. Existing Derby syntax and
constraint-backed indexes continue to use the same implicit `btree` default.

The parser and constant-action plumbing must not load provider adapter or
resolver classes while compiling or executing ordinary Derby-compatible SQL.
That keeps `derby.jar` smoke and inherited tests independent of SPI runtime
classes until DelosDB intentionally ships a runtime provider layer.

## Minimal provider catalog persistence

DelosDB persists the normalized index provider name inside the existing serialized
index descriptor metadata. This is deliberately conservative:

```text
old Derby-created descriptor with no provider key -> btree
CREATE INDEX without USING                     -> btree
CREATE INDEX ... USING btree                   -> btree
```

No new system catalog table is introduced at this stage, and the persisted
provider name remains metadata only. Derby's existing physical B-tree creation,
optimizer costing, and execution path are unchanged.

## CREATE INDEX provider metadata compatibility

DelosDB records the normalized index provider name in the existing serialized
index descriptor metadata. Derby-compatible indexes and indexes created without
`USING` are treated as `btree`. Descriptors created before this metadata existed
are read as `btree` when the key is absent.

The verification task `verifyCreateIndexProviderMetadataRoundTrip` checks:

```text
implicit CREATE INDEX metadata -> btree
explicit USING btree metadata -> btree
old descriptor metadata without a provider key -> btree
```

The parser and constant-action path must not require provider adapter/resolver
classes to execute normal Derby-compatible `CREATE INDEX` statements.


For the first release, provider validation is intentionally strict:

```text
CREATE INDEX ...                  -> btree
CREATE INDEX ... USING btree      -> btree
CREATE INDEX ... USING hash       -> unsupported in first release
CREATE INDEX ... USING nonsense   -> unsupported provider diagnostic
```

This keeps public SQL additive and Derby-compatible while making the provider boundary explicit.

## Provider metadata diagnostics

The DelosDB provider identity is exposed through the existing index descriptor
API with `IndexDescriptor.indexProviderName()`. This is diagnostic metadata only:
existing Derby-compatible indexes still report `btree`, and the value does not
affect optimizer costing, storage layout, or execution behavior at this phase.

Smoke coverage reads `SYS.SYSCONGLOMERATES.DESCRIPTOR` through JDBC and uses the
typed `IndexDescriptor` API to verify that both implicit Derby-compatible indexes
and explicit `USING btree` indexes report the same provider identity.
## Optimizer compatibility rule

Existing Derby costing remains authoritative by default. Provider-cost integration
is explicit-mode only: diagnostic mode records estimates, while enabled mode may
consume a valid estimate at the narrow index-cost bridge. Missing DelosDB SPI
runtime classes must not break ordinary Derby-compatible query compilation or
cost estimation.


## Legacy IndexProvider cost diagnostics

The built-in `btree` provider can still return a provider-neutral baseline cost estimate. This path is kept as an internal diagnostic for IndexProvider metadata and catalog plumbing. It no longer consumes provider cost or mutates Derby's optimizer cost. Native provider cost consumption is handled by CostModelProvider through `StoreCostController`.

The legacy optimizer bridge is controlled by the internal DelosDB property:

```text
delosdb.optimizer.indexProviderCost=off          # default; no provider cost probe
delosdb.optimizer.indexProviderCost=diagnostic  # record provider estimate, keep Derby cost
delosdb.optimizer.indexProviderCost=enabled     # accepted legacy spelling; still diagnostic-only
```

Only `btree` is exposed as a public provider name in this checkpoint. Internal diagnostics must not introduce fake public provider names or debug SQL syntax.
