# Phase 1 IndexAccess SPI Checkpoint

Status: initial physical index access contracts added to `delosdb-spi`; built-in `btree` bridge added structurally for first-release compatibility.

## Why this exists

`IndexProvider` v0 proved provider identity, metadata persistence, capability
reporting, cost diagnostics, opt-in cost consumption, and controlled
registration. That is enough for the optimizer seam, but it is not a complete
physical index provider model.

A real index provider also needs an access lifecycle:

```text
create/open physical index
insert/delete/update entries
scan or lookup entries through a cursor
truncate/drop/rebuild state
return row references back to the engine
```

The new `IndexAccess` contracts introduce that shape without wiring it into
Derby execution yet.

## Added SPI types

```text
IndexAccess
IndexCursor
IndexAccessException
IndexKey
IndexLookup
IndexOpenMode
IndexOpenRequest
RowReference
```

`IndexProvider.openAccess(IndexOpenRequest)` is optional and defaults to
`Optional.empty()`. Existing providers can remain metadata/cost-only while the
Derby adapter layer is built.

## Boundary rule

The physical SPI must not expose Derby, H2, MapDB, or other backend internals.
In particular, public providers must not depend on:

```text
Conglomerate
TransactionController
ScanController
RowLocation
DataValueDescriptor
StoreCostController
FromBaseTable
OptimizerImpl
```

The bridge direction remains:

```text
Derby internals
  -> DelosDB internal adapters
  -> provider-neutral IndexAccess SPI
```

## What is deliberately not complete

This checkpoint does not add:

```text
hash provider
MapDB dependency
H2 code
public provider discovery
ServiceLoader
new physical storage
SQL provider-name expansion
executor cursor bridge
mutation hooks from Derby DML into external providers
```

## Next choices

The built-in `btree` provider is now represented through the new `IndexAccess` shape. Hash indexing is deferred for the first release so DelosDB can keep Derby-compatible behavior while exposing modern Java 21 modular seams. The clean next move is to pause index depth and move sideways to another SPI module such as `FunctionProvider` or a small storage-location abstraction inspired by H2 `FilePath`, without introducing a new physical storage engine yet.
