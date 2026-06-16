/**
 * Durable record formats for the experimental DelosDB MVCC storage engine.
 *
 * <p>These classes are deliberately below SQL execution. They define the bytes
 * that later page-backed MVCC tables will store in provider-owned page files.</p>
 */
package io.github.ggeorg.delosdb.storage.mvcc.format;
