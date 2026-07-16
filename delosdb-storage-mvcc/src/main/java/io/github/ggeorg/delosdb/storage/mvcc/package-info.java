/**
 * MVCC storage module for DelosDB.
 *
 * <p>This package contains the MVCC kernel used by the Derby-integrated
 * {@code delos_mvcc} storage path. The old stand-alone versioned-storage
 * earlier in-memory-only path has been removed; production SQL entry is through the Derby
 * store/access bridge and {@code DelosStorageProviderFactory}.</p>
 */
package io.github.ggeorg.delosdb.storage.mvcc;
