package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPage;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageFile;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/** Append-only provider-owned durable store for MVCC index candidate tuples. */
public final class MvccDurableIndexStore implements AutoCloseable {
    private static final int SLOT_OVERHEAD_BYTES = 12;

    private final MvccPageFile pageFile;

    private MvccDurableIndexStore(MvccPageFile pageFile) {
        this.pageFile = Objects.requireNonNull(pageFile, "pageFile");
    }

    public static MvccDurableIndexStore open(Path path) throws IOException {
        return new MvccDurableIndexStore(MvccPageFile.open(path));
    }

    public synchronized MvccVersionLocator append(MvccIndexTuple tuple) throws IOException {
        Objects.requireNonNull(tuple, "tuple");
        byte[] encoded = MvccIndexTupleCodec.encode(tuple);
        if (encoded.length > maxSingleRecordBytes()) {
            throw new IllegalArgumentException("MVCC durable index tuple is too large for one page: " + encoded.length);
        }
        MvccPage page = writablePage(encoded.length);
        int slotId = page.appendRecord(encoded);
        pageFile.writePage(page);
        pageFile.force();
        return new MvccVersionLocator(page.pageId(), slotId);
    }

    public synchronized List<MvccIndexTuple> loadAll() throws IOException {
        List<MvccIndexTuple> tuples = new ArrayList<>();
        long count = pageFile.pageCount();
        for (long pageNumber = 0L; pageNumber < count; pageNumber++) {
            MvccPage page = pageFile.readPage(new MvccPageId(pageNumber));
            for (int slot = 0; slot < page.slotCount(); slot++) {
                tuples.add(MvccIndexTupleCodec.decode(page.readRecord(slot)));
            }
        }
        return List.copyOf(tuples);
    }

    public synchronized List<MvccIndexTuple> lookup(String indexName, byte[] indexKey) throws IOException {
        String normalized = normalizeIndexName(indexName);
        byte[] expectedKey = requireIndexKey(indexKey).clone();
        List<MvccIndexTuple> matches = new ArrayList<>();
        for (MvccIndexTuple tuple : loadAll()) {
            if (tuple.indexName().equals(normalized) && Arrays.equals(tuple.indexKey(), expectedKey)) {
                matches.add(tuple);
            }
        }
        return List.copyOf(matches);
    }

    public synchronized long pageCount() throws IOException {
        return pageFile.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        pageFile.close();
    }

    private static int maxSingleRecordBytes() {
        return MvccPage.empty(new MvccPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private static String normalizeIndexName(String indexName) {
        String normalized = Objects.requireNonNull(indexName, "indexName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("index name must not be blank");
        }
        return normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static byte[] requireIndexKey(byte[] indexKey) {
        Objects.requireNonNull(indexKey, "indexKey");
        if (indexKey.length == 0) {
            throw new IllegalArgumentException("index key must not be empty");
        }
        return indexKey;
    }

    private MvccPage writablePage(int encodedRecordLength) throws IOException {
        long count = pageFile.pageCount();
        if (count == 0L) {
            return pageFile.allocatePage();
        }
        MvccPage last = pageFile.readPage(new MvccPageId(count - 1L));
        if (last.freeBytes() >= encodedRecordLength + SLOT_OVERHEAD_BYTES) {
            return last;
        }
        return pageFile.allocatePage();
    }
}
