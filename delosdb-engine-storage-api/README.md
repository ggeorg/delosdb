# DelosDB Engine Storage API

Source-owner module for engine-facing storage contracts and Derby-compatible
store value bridge types.

This module owns `org.apache.derby.iapi.store.types.*`.

It is intentionally not the provider SPI. Provider-facing contracts stay in
`delosdb-spi`. It is also not the inherited Derby heap/raw/access contract;
those remain in `delosdb-derby-store-api`.

For now these classes are still patched/packaged into `derby.jar` for Derby
runtime compatibility while source ownership becomes explicit.
