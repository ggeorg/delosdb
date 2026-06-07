/**
 * Experimental DelosDB index provider contracts.
 *
 * <p>This package defines the public-facing shape that future DelosDB index
 * providers will implement. It intentionally does not expose Derby access
 * methods, conglomerates, scan controllers, optimizer implementations, or
 * monitor/module APIs.</p>
 */
@ExperimentalSpi("Initial IndexProvider contract skeleton; subject to change before public SPI graduation.")
package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;
