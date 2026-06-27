/**
 * Neutral MVCC storage contracts used between storage adapters and the native
 * DelosDB MVCC implementation.
 *
 * <p>This package must not depend on Derby adapter classes, page-volume
 * implementations, WAL file formats, checkpoint file formats, or other concrete
 * storage-engine internals.</p>
 */
package io.github.ggeorg.delosdb.storage.mvcc.api;
