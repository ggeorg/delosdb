# DelosDB SPI stability markers

DelosDB uses explicit stability annotations before exposing extension contracts.
This keeps inherited Derby internals separate from DelosDB platform APIs.

## Markers

- `@PublicSpi` — extension contracts intended for external provider implementations once stable.
- `@ExperimentalSpi` — early extension contracts that may change before graduation.
- `@InternalApi` — DelosDB implementation APIs with no compatibility guarantee.
- `@LegacyInternal` — inherited Derby internals retained during modernization.

## Current rule

The initial `delosdb-spi` module contains markers only. Provider contracts such as
`StorageProvider`, `IndexProvider`, optimizer hooks, and extension registries should
be added in separate reviewed increments.
