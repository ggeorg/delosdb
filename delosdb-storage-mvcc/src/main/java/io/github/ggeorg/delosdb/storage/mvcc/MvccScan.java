package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.List;

/**
 * Stable scan cursor over MVCC-visible rows.
 *
 * <p>The scan owns an immutable copy of rows visible at open time. That keeps
 * deterministic while preserving visibility semantics across
 * Derby heap, B-tree, latch, or WAL integration.</p>
 */
public final class MvccScan<K, V> implements AutoCloseable {
    private final List<MvccRow<K, V>> rows;
    private int position = -1;
    private boolean closed;

    private MvccScan(List<MvccRow<K, V>> rows) {
        this.rows = List.copyOf(rows);
    }

    public static <K, V> MvccScan<K, V> fromVisibleRows(List<MvccRow<K, V>> rows) {
        if (rows == null) {
            throw new IllegalArgumentException("rows must not be null");
        }
        return new MvccScan<>(rows);
    }

    public boolean next() {
        requireOpen();
        if (position + 1 >= rows.size()) {
            position = rows.size();
            return false;
        }
        position++;
        return true;
    }

    public MvccRow<K, V> row() {
        requireOpen();
        if (position < 0 || position >= rows.size()) {
            throw new IllegalStateException("scan is not positioned on a row");
        }
        return rows.get(position);
    }

    public int visibleRowCount() {
        return rows.size();
    }

    @Override
    public void close() {
        closed = true;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("scan is closed");
        }
    }
}
