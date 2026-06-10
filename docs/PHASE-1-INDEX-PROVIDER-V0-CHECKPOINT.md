# Phase 1 IndexProvider v0 Checkpoint

Status: checkpointed after the built-in `btree` provider seam became observable and opt-in cost consumption was added.

## What is complete

IndexProvider v0 now has the minimum vertical seam:

```text
CREATE INDEX ... USING btree
  -> provider metadata persistence
  -> IndexDescriptor.indexProviderName()
  -> provider-neutral IndexMetadata
  -> provider capabilities
  -> provider cost request
  -> optimizer diagnostic/opt-in cost bridge
```

The public SQL surface remains intentionally small:

```sql
CREATE INDEX idx ON t(c);
CREATE INDEX idx ON t(c) USING btree;
```

Both forms use the existing Derby B-tree physical implementation. `btree` is the only public provider name in this checkpoint.

## Compatibility position

Default behavior remains Derby-compatible:

```text
delosdb.optimizer.indexProviderCost unset/off -> Derby cost remains authoritative
```

Provider cost integration is opt-in:

```text
delosdb.optimizer.indexProviderCost=diagnostic -> record provider estimates only
delosdb.optimizer.indexProviderCost=enabled    -> consume valid provider estimates at the narrow index-cost bridge
```

If the provider bridge is unavailable, invalid, or not explicitly enabled, the optimizer keeps the existing Derby cost path.

## What is deliberately not complete

This checkpoint does not introduce:

```text
external provider loading
ServiceLoader discovery
new physical index storage
new catalog tables
fake diagnostic provider names
public debug SQL syntax
broad optimizer rewrites
```

## Next work

The next work should move toward controlled registration of non-built-in providers in test scope first, then reviewed provider discovery. StorageProvider work should wait until IndexProvider registration, diagnostics, costing, and failure modes are stable.
