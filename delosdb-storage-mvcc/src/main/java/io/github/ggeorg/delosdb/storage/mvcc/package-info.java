/**
 * Experimental MVCC storage module for DelosDB.
 *
 * <p>This package contains the in-memory MVCC kernel and its first adapter to
 * the {@code VersionedStorageProvider} SPI. It remains deliberately independent
 * of Derby heap, B-tree, locking, and log internals.</p>
 */
package io.github.ggeorg.delosdb.storage.mvcc;
