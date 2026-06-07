# DelosDB SPI stability markers

DelosDB uses explicit stability annotations before exposing extension contracts.
This keeps inherited Derby internals separate from DelosDB platform APIs.

## Markers

- `@PublicSpi` — extension contracts intended for external provider implementations once stable.
- `@ExperimentalSpi` — early extension contracts that may change before graduation.
- `@InternalApi` — DelosDB implementation APIs with no compatibility guarantee.
- `@LegacyInternal` — inherited Derby internals retained during modernization.

## Current rule

The `delosdb-spi` module starts with stability markers and now contains the first
contract-only experimental index provider shape. This does not make Derby access
methods, optimizer classes, monitor/module APIs, or storage internals public SPI.

The initial `io.github.ggeorg.delosdb.spi.index` package is intentionally small:

- provider identity
- provider-neutral index metadata
- provider capability reporting
- optional provider cost estimates

It intentionally does not include:

- runtime index open/create/drop hooks
- SQL `CREATE INDEX ... USING` support
- catalog persistence
- `ServiceLoader` discovery
- Derby B-tree adapters
- optimizer integration

Those must be added in separate reviewed increments after the registry and bridge
boundaries are proven.

Before adding real provider behavior, DelosDB records the Derby monitor bridge
decision in `docs/DERBY-MONITOR-BRIDGE.md`: public SPI contracts sit above Derby's
existing monitor/module system and must not expose monitor internals directly.
