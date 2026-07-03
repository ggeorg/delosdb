package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Durable sidecar free-space map for page-backed MVCC data pages. */
final class MvccFreeSpaceMapStore {
    private static final int MAGIC = 0x444d4653; // DMFS
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES * 3 + Long.BYTES;
    private static final int ENTRY_BYTES = Long.BYTES + Integer.BYTES;
    private static final int CHECKSUM_BYTES = Integer.BYTES;

    private final Path path;

    private MvccFreeSpaceMapStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    static MvccFreeSpaceMapStore open(Path path) {
        return new MvccFreeSpaceMapStore(path);
    }

    Path path() {
        return path;
    }

    boolean exists() {
        return Files.exists(path);
    }

    Snapshot read() throws IOException {
        if (!Files.exists(path)) {
            return Snapshot.empty();
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_BYTES + CHECKSUM_BYTES) {
            throw new IllegalStateException("MVCC free-space map is truncated: " + path);
        }
        int storedChecksum = ByteBuffer.wrap(bytes, bytes.length - CHECKSUM_BYTES, CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        int actualChecksum = MvccSidecarFiles.checksum(bytes, 0, bytes.length - CHECKSUM_BYTES);
        if (storedChecksum != actualChecksum) {
            throw new IllegalStateException("MVCC free-space map checksum mismatch: " + path);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, bytes.length - CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalStateException("Unexpected MVCC free-space map magic: " + magic);
        }
        int version = buffer.getInt();
        if (version != VERSION) {
            throw new IllegalStateException("Unsupported MVCC free-space map version: " + version);
        }
        long pageCount = buffer.getLong();
        int entryCount = buffer.getInt();
        if (entryCount < 0) {
            throw new IllegalStateException("Invalid MVCC free-space map entry count: " + entryCount);
        }
        int expectedBytes = HEADER_BYTES + Math.multiplyExact(entryCount, ENTRY_BYTES) + CHECKSUM_BYTES;
        if (expectedBytes != bytes.length) {
            throw new IllegalStateException("Invalid MVCC free-space map length: " + path);
        }
        NavigableMap<Long, Integer> freeBytesByPageId = new TreeMap<>();
        for (int index = 0; index < entryCount; index++) {
            long pageId = buffer.getLong();
            int freeBytes = buffer.getInt();
            if (pageId < 0L || pageId >= pageCount) {
                throw new IllegalStateException("MVCC free-space map contains out-of-range page "
                        + pageId + " for pageCount=" + pageCount);
            }
            if (freeBytes < 0) {
                throw new IllegalStateException("MVCC free-space map contains negative free bytes for page "
                        + pageId + ": " + freeBytes);
            }
            Integer previous = freeBytesByPageId.put(pageId, freeBytes);
            if (previous != null) {
                throw new IllegalStateException("MVCC free-space map contains duplicate page id " + pageId);
            }
        }
        return new Snapshot(pageCount, freeBytesByPageId);
    }

    void rewrite(long pageCount, NavigableMap<Long, Integer> freeBytesByPageId) throws IOException {
        Objects.requireNonNull(freeBytesByPageId, "freeBytesByPageId");
        if (pageCount < 0L) {
            throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
        }
        for (var entry : freeBytesByPageId.entrySet()) {
            Long pageId = entry.getKey();
            Integer freeBytes = entry.getValue();
            if (pageId == null || pageId < 0L || pageId >= pageCount) {
                throw new IllegalArgumentException("Free-space map page id " + pageId
                        + " is outside pageCount=" + pageCount);
            }
            if (freeBytes == null || freeBytes < 0) {
                throw new IllegalArgumentException("Free-space map free bytes for page "
                        + pageId + " must be non-negative: " + freeBytes);
            }
        }
        int payloadLength = HEADER_BYTES + Math.multiplyExact(freeBytesByPageId.size(), ENTRY_BYTES);
        ByteBuffer buffer = ByteBuffer.allocate(payloadLength + CHECKSUM_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(VERSION);
        buffer.putLong(pageCount);
        buffer.putInt(freeBytesByPageId.size());
        for (var entry : freeBytesByPageId.entrySet()) {
            buffer.putLong(entry.getKey());
            buffer.putInt(entry.getValue());
        }
        buffer.putInt(MvccSidecarFiles.checksum(buffer.array(), 0, payloadLength));

        MvccSidecarFiles.rewriteAtomically(path, buffer.array());
    }

    void delete() throws IOException {
        MvccSidecarFiles.deleteWithRewriteSibling(path);
    }

    record Snapshot(long pageCount, NavigableMap<Long, Integer> freeBytesByPageId) {
        Snapshot {
            if (pageCount < 0L) {
                throw new IllegalArgumentException("pageCount must not be negative: " + pageCount);
            }
            freeBytesByPageId = Collections.unmodifiableNavigableMap(
                    new TreeMap<>(Objects.requireNonNull(freeBytesByPageId, "freeBytesByPageId")));
        }

        static Snapshot empty() {
            return new Snapshot(0L, new TreeMap<>());
        }
    }
}
