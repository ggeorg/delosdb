/**
 * Experimental versioned-storage SPI for storage implementations that own row
 * versions and snapshot visibility.
 *
 * <p>This package is intentionally smaller than a complete MVCC database API.
 * It gives DelosDB one narrow extension boundary for table-level versioned
 * storage while the engine continues to preserve Derby-compatible heap storage
 * by default.</p>
 */
package io.github.ggeorg.delosdb.spi.storage.versioned;
