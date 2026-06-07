# DelosDB SPI bridge over Derby monitor services

## Decision

DelosDB will expose a modern public SPI above Derby's existing monitor/module system.
The Derby monitor remains the internal boot, lifecycle, and service-discovery mechanism.
Third-party DelosDB extensions must not depend directly on Derby monitor APIs.

The intended layering is:

```text
DelosDB public SPI
        ↓
DelosDB extension registry and adapter layer
        ↓
Derby Monitor / module boot system
        ↓
existing engine internals
```

This is a bridge, not a replacement. The proven Derby boot machinery stays in place
while DelosDB provides a cleaner, typed, stability-marked extension surface.

## Why this matters

Derby's monitor system is not incidental. It wires the engine boot sequence, module
lookup, lifecycle callbacks, service properties, and persistent database services.
A public SPI that bypasses this machinery would either break lifecycle assumptions or
create a second parallel container that has to be kept in sync forever.

DelosDB therefore treats the Derby monitor as a legacy internal container and exposes
new contracts above it.

## Core mapping

| Derby concept | DelosDB concept | Visibility decision |
| --- | --- | --- |
| `Monitor.bootModule()` | `ExtensionRegistry.load()` / internal adapter loading | Hidden — internal boot detail |
| `ModuleFactory` lookup | internal registry lookup | Hidden — implementation detail |
| `ModuleControl.boot()` | `Provider.initialize()` | Adapted — typed config, lifecycle guarded |
| `ModuleControl.stop()` | `Provider.close()` / lifecycle shutdown | Adapted — typed lifecycle contract |
| `PersistentService` | future `StorageProvider` bridge | Exposed later only through a clean contract |
| `Properties` boot configuration | future `ProviderConfig<T>` | Exposed as typed configuration, not stringly properties |
| module name strings | provider name plus provider type | Exposed as stable provider identity |
| Derby service directories | storage/catalog-owned provider state | Hidden unless elevated through a storage SPI |
| Derby monitor error propagation | DelosDB provider diagnostics | Adapted — public error model, internal cause retained |

## Boundary rules

1. Public extension code must not implement Derby `ModuleControl` directly.
2. Public extension code must not call `Monitor.bootModule()` directly.
3. Public extension code must not depend on Derby module name strings as a stable API.
4. DelosDB may adapt public providers into Derby modules internally.
5. Public SPI contracts must be annotated with `@PublicSpi` or `@ExperimentalSpi`.
6. Derby monitor-facing code should be marked `@LegacyInternal` or `@InternalApi` as the boundary becomes explicit.

## Initial annotation direction

The following inherited monitor APIs are candidates for `@LegacyInternal` marking:

```text
org.apache.derby.iapi.services.monitor.Monitor
org.apache.derby.iapi.services.monitor.ModuleFactory
org.apache.derby.iapi.services.monitor.ModuleControl
org.apache.derby.iapi.services.monitor.PersistentService
org.apache.derby.impl.services.monitor.BaseMonitor
```

This does not mean they are obsolete. It means they are not the public DelosDB SPI.
They remain valid internal infrastructure until and unless a specific subsystem is
adapted behind a DelosDB contract.

## Sequencing

Recommended sequence:

```text
Done:  delosdb-spi stability annotation vocabulary
Now:   document the Derby Monitor bridge decision
Next:  mark monitor-facing APIs as @LegacyInternal / @InternalApi
Done:  add internal ExtensionRegistry skeleton with no provider contracts yet
Done:  register built-in provider descriptors above the Monitor bridge
Done:  add experimental IndexProvider contract skeleton
Now:   connect built-in btree identity to an internal IndexProvider adapter
Later: add SQL/catalog/optimizer bridges after lifecycle boundaries are proven
Rule:  preserve Derby compatibility by defaulting unnamed index providers to btree
```

The first real provider contract is deliberately small. The experimental
`IndexProvider` contract currently covers provider identity, capabilities, and
optional cost estimation only. It does not expose runtime open/create/drop hooks,
Derby access methods, or optimizer implementation classes.


## Built-in provider descriptors and adapters

The first registry-backed provider identity is internal only:

```text
ExtensionType.INDEX / btree / builtin
```

This records the default Derby B-tree index family as a DelosDB provider
descriptor without exposing Derby index, store, optimizer, or monitor classes as
public SPI. It gives later `CREATE INDEX ... USING btree` work a stable internal
name to resolve while keeping the implementation behind the bridge.

The built-in `btree` identity is now backed by an internal `IndexProvider`
adapter. The adapter reports conservative B-tree capabilities and deliberately
returns no provider-specific cost estimate so Derby's existing costing path remains
unchanged until the optimizer bridge is introduced.

This is not provider discovery, SQL syntax, catalog persistence, or optimizer
integration. It only connects the built-in descriptor to the experimental SPI shape
while keeping Derby implementation classes behind the bridge.


### Built-in index provider resolution

The first provider bridge remains deliberately internal:

```text
ExtensionRegistry descriptor:  INDEX / btree / ENABLED
        ↓
IndexProviderResolver
        ↓
BuiltInBTreeIndexProvider
        ↓
existing Derby B-tree implementation, still reached through Derby internals
```

This resolver is not public plugin loading. It exists only to prove that DelosDB
can resolve a stable provider identity (`btree`) to an internal `IndexProvider`
adapter without exposing Derby Monitor, store, optimizer, or conglomerate APIs.

The resolver must stay behind the engine boundary until catalog lifecycle,
missing-provider diagnostics, and optimizer integration are designed.

### Derby-compatible default provider

DelosDB treats the built-in `btree` provider as the default index provider.
Existing Derby syntax remains unchanged:

```sql
CREATE INDEX idx ON t(c);
```

Future DelosDB syntax is additive and must resolve to the same provider when the
provider is `btree`:

```sql
CREATE INDEX idx ON t(c) USING btree;
```

The detailed compatibility policy is recorded in `docs/DERBY-COMPATIBILITY.md`.

## Non-goals

This decision does not introduce:

- a new dependency-injection framework;
- a Spring/CDI-style runtime container;
- public access to Derby monitor internals;
- a storage provider contract;
- SQL syntax for selecting an index provider;
- optimizer integration with `IndexProvider`;
- old harness execution;
- Ant as a supported workflow.

## Summary

Derby's monitor remains the internal container. DelosDB SPI becomes the public
extension surface. The bridge layer is responsible for translating stable provider
contracts into Derby's existing lifecycle and service model.
