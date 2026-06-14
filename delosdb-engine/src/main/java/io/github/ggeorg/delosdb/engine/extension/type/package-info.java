/**
 * Internal TypeProvider adapters for DelosDB.
 *
 * <p>The v0 type provider layer is metadata-only. It classifies Derby's built-in
 * SQL type catalog as a provider family without exposing parser, binder,
 * DataTypeDescriptor, DataValueDescriptor, or storage-format internals as SPI.</p>
 */
@InternalApi
package io.github.ggeorg.delosdb.engine.extension.type;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
