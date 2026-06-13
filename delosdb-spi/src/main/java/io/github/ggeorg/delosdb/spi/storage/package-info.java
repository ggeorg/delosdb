/**
 * Experimental DelosDB table storage provider SPI.
 *
 * <p>StorageProvider v0 is metadata-only. It identifies table storage provider
 * families such as the built-in {@code heap} provider without exposing Derby raw
 * store implementation classes.</p>
 */
@ExperimentalSpi("Initial StorageProvider contract; subject to change before public SPI graduation.")
package io.github.ggeorg.delosdb.spi.storage;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;
