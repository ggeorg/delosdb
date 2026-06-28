/**
 * Quarantined experimental versioned-storage SPI for storage implementations
 * that own row versions and snapshot visibility.
 *
 * <p>This package remains as a compatibility/proof surface while the active
 * Derby SQL storage path moves through
 * {@code org.apache.derby.iapi.store.types.DelosStorageProviderFactory}.
 * Production engine code must not use this package as the primary MVCC storage
 * provider boundary.</p>
 */
package io.github.ggeorg.delosdb.spi.storage.versioned;
