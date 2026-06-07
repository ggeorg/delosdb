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
Then:  register built-in provider descriptors above the Monitor bridge
Then:  add the first small @ExperimentalSpi provider contract
Later: add IndexProvider and StorageProvider bridges after lifecycle boundaries are proven
```

The first real provider contract should be deliberately small. A lightweight
`ExtensionProvider` or `FunctionProvider` is safer than starting with `StorageProvider`
or `IndexProvider`, because storage and indexing touch boot, catalog metadata,
optimizer behavior, locking, recovery, and execution.

## Non-goals

This decision does not introduce:

- a new dependency-injection framework;
- a Spring/CDI-style runtime container;
- public access to Derby monitor internals;
- a storage provider contract;
- an index provider contract;
- old harness execution;
- Ant as a supported workflow.

## Summary

Derby's monitor remains the internal container. DelosDB SPI becomes the public
extension surface. The bridge layer is responsible for translating stable provider
contracts into Derby's existing lifecycle and service model.
