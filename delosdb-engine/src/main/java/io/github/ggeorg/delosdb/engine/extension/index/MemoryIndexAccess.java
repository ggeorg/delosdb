package io.github.ggeorg.delosdb.engine.extension.index;

import io.github.ggeorg.delosdb.spi.annotation.InternalApi;
import io.github.ggeorg.delosdb.spi.index.IndexAccess;
import io.github.ggeorg.delosdb.spi.index.IndexAccessException;
import io.github.ggeorg.delosdb.spi.index.IndexCursor;
import io.github.ggeorg.delosdb.spi.index.IndexKey;
import io.github.ggeorg.delosdb.spi.index.IndexLookup;
import io.github.ggeorg.delosdb.spi.index.IndexOpenRequest;
import io.github.ggeorg.delosdb.spi.index.RowReference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Provider-neutral in-memory index implementation used by the built-in
 * {@code memory} IndexProvider proof.
 */
@InternalApi
final class MemoryIndexAccess implements IndexAccess {
    private final IndexOpenRequest request;
    private final NavigableMap<ByteKey, LinkedHashSet<RowReference>> rows = new TreeMap<>();
    private boolean dropped;

    MemoryIndexAccess(IndexOpenRequest request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    IndexOpenRequest request() {
        return request;
    }

    @Override
    public synchronized void insert(IndexKey key, RowReference rowReference) throws IndexAccessException {
        requireOpen();
        rows.computeIfAbsent(ByteKey.of(key), ignored -> new LinkedHashSet<>()).add(rowReference);
    }

    @Override
    public synchronized void delete(IndexKey key, RowReference rowReference) throws IndexAccessException {
        requireOpen();
        ByteKey byteKey = ByteKey.of(key);
        Set<RowReference> references = rows.get(byteKey);
        if (references == null) {
            return;
        }
        references.remove(rowReference);
        if (references.isEmpty()) {
            rows.remove(byteKey);
        }
    }

    @Override
    public synchronized IndexCursor find(IndexLookup lookup) throws IndexAccessException {
        requireOpen();
        Objects.requireNonNull(lookup, "lookup");
        List<RowReference> snapshot = new ArrayList<>();
        NavigableMap<ByteKey, LinkedHashSet<RowReference>> candidates = candidates(lookup);
        if (lookup.reverse()) {
            candidates = candidates.descendingMap();
        }
        for (LinkedHashSet<RowReference> references : candidates.values()) {
            snapshot.addAll(references);
        }
        return new SnapshotIndexCursor(snapshot);
    }

    @Override
    public synchronized void truncate() throws IndexAccessException {
        requireOpen();
        rows.clear();
    }

    @Override
    public synchronized void drop() throws IndexAccessException {
        requireOpen();
        rows.clear();
        dropped = true;
    }

    @Override
    public synchronized long estimatedRowCount() throws IndexAccessException {
        requireOpen();
        long count = 0L;
        for (Set<RowReference> references : rows.values()) {
            count += references.size();
        }
        return count;
    }

    private NavigableMap<ByteKey, LinkedHashSet<RowReference>> candidates(IndexLookup lookup) {
        if (lookup.lowerBound() == null && lookup.upperBound() == null) {
            return rows;
        }
        if (lookup.lowerBound() != null && lookup.upperBound() != null) {
            return rows.subMap(
                    ByteKey.of(lookup.lowerBound()),
                    lookup.lowerInclusive(),
                    ByteKey.of(lookup.upperBound()),
                    lookup.upperInclusive());
        }
        if (lookup.lowerBound() != null) {
            return rows.tailMap(ByteKey.of(lookup.lowerBound()), lookup.lowerInclusive());
        }
        return rows.headMap(ByteKey.of(lookup.upperBound()), lookup.upperInclusive());
    }

    private void requireOpen() throws IndexAccessException {
        if (dropped) {
            throw new IndexAccessException("In-memory index has been dropped: " + request.metadata().indexName());
        }
    }

    private record ByteKey(byte[] bytes) implements Comparable<ByteKey> {
        private ByteKey {
            bytes = bytes.clone();
        }

        static ByteKey of(IndexKey key) {
            return new ByteKey(key.encodedKey());
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public int compareTo(ByteKey other) {
            int min = Math.min(bytes.length, other.bytes.length);
            for (int i = 0; i < min; i++) {
                int left = Byte.toUnsignedInt(bytes[i]);
                int right = Byte.toUnsignedInt(other.bytes[i]);
                if (left != right) {
                    return Integer.compare(left, right);
                }
            }
            return Integer.compare(bytes.length, other.bytes.length);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ByteKey that && Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

    private static final class SnapshotIndexCursor implements IndexCursor {
        private final List<RowReference> rows;
        private int index = -1;

        private SnapshotIndexCursor(List<RowReference> rows) {
            this.rows = List.copyOf(rows);
        }

        @Override
        public boolean next() {
            if (index + 1 >= rows.size()) {
                return false;
            }
            index++;
            return true;
        }

        @Override
        public RowReference rowReference() throws IndexAccessException {
            if (index < 0 || index >= rows.size()) {
                throw new IndexAccessException("Index cursor is not positioned on a row");
            }
            return rows.get(index);
        }
    }
}
