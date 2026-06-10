/**
 * Experimental DelosDB index provider contracts.
 *
 * <p>This package defines the public-facing shape that future DelosDB index
 * providers will implement. It intentionally does not expose Derby access
 * methods, conglomerates, scan controllers, row locations, data value
 * descriptors, optimizer implementations, or monitor/module APIs.</p>
 *
 * <p>The physical access contracts in this package are provider-neutral. Derby
 * storage, transaction, and execution details must be adapted internally by
 * DelosDB before they cross this SPI boundary.</p>
 */
@ExperimentalSpi("Initial IndexProvider and IndexAccess contracts; subject to change before public SPI graduation.")
package io.github.ggeorg.delosdb.spi.index;

import io.github.ggeorg.delosdb.spi.annotation.ExperimentalSpi;
