/**
 * Experimental MVCC core model for DelosDB.
 *
 * <p>This package is deliberately independent of Derby heap, B-tree, locking,
 * and log internals. It proves transaction ids, snapshots, row-version chains,
 * visibility, and cleanup rules before those concepts are wired into any
 * storage-provider or SQL executor path.</p>
 */
package io.github.ggeorg.delosdb.storage.mvcc;
