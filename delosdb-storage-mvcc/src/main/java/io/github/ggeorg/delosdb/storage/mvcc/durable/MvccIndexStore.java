package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.FileChannelPageVolume;

/**
 * Durable MVCC index-candidate store.
 *
 * <p>The index is deliberately not the visibility authority. It stores and
 * returns candidates only; the page-backed row/version directory decides whether
 * a candidate is visible for a snapshot and whether the visible payload still
 * has the requested index key.</p>
 */
public final class MvccIndexStore implements AutoCloseable {
    private static final int INDEX_PAGE_TYPE = 2;
    private static final int SLOT_OVERHEAD_BYTES = 12;
    private static final String DEFAULT_INDEX_NAME = "IDX";

    private final Path path;
    private DelosPageVolume pageVolume;

    private MvccIndexStore(Path path, DelosPageVolume pageVolume) {
        this.path = Objects.requireNonNull(path, "path");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
    }

    public static MvccIndexStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        MvccIndexStore store = new MvccIndexStore(path, FileChannelPageVolume.open(path));
        store.readCandidates(); // validate existing durable bytes eagerly
        return store;
    }

    public synchronized void appendCandidate(Object indexKey, MvccIndexTuple tuple) throws IOException {
        Objects.requireNonNull(indexKey, "indexKey");
        appendTuple(keyedTuple(defaultIndexName(tuple), indexKey, tuple), indexKey);
    }

    public synchronized void appendCandidate(String indexName, Object indexKey, MvccIndexTuple tuple)
            throws IOException {
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(indexKey, "indexKey");
        appendTuple(keyedTuple(indexName, indexKey, tuple), indexKey);
    }

    public synchronized void appendCandidate(MvccIndexTuple tuple) throws IOException {
        appendTuple(Objects.requireNonNull(tuple, "tuple"), tuple.indexKeyAsUtf8());
    }

    public synchronized List<MvccIndexTuple> lookupCandidates(Object indexKey) throws IOException {
        Objects.requireNonNull(indexKey, "indexKey");
        List<MvccIndexTuple> matches = new ArrayList<>();
        for (Candidate candidate : readCandidates()) {
            if (sameKey(candidate.indexKey(), indexKey)) {
                matches.add(candidate.tuple());
            }
        }
        return List.copyOf(matches);
    }

    public synchronized List<MvccIndexTuple> lookupCandidates(String indexName, Object indexKey) throws IOException {
        Objects.requireNonNull(indexName, "indexName");
        Objects.requireNonNull(indexKey, "indexKey");
        String normalized = normalizeIndexName(indexName);
        List<MvccIndexTuple> matches = new ArrayList<>();
        for (Candidate candidate : readCandidates()) {
            MvccIndexTuple tuple = candidate.tuple();
            if (tuple.indexName().equals(normalized) && sameKey(candidate.indexKey(), indexKey)) {
                matches.add(tuple);
            }
        }
        return List.copyOf(matches);
    }

    public synchronized List<MvccIndexTuple> lookupRangeCandidates(Object fromInclusive, Object toInclusive)
            throws IOException {
        return lookupRangeCandidates(fromInclusive, true, toInclusive, true);
    }

    public synchronized List<MvccIndexTuple> lookupRangeCandidates(
            Object fromKey,
            boolean fromInclusive,
            Object toKey,
            boolean toInclusive) throws IOException {
        List<Candidate> matches = new ArrayList<>();
        for (Candidate candidate : readCandidates()) {
            if (inRange(candidate.indexKey(), fromKey, fromInclusive, toKey, toInclusive)) {
                matches.add(candidate);
            }
        }
        matches.sort((left, right) -> compareKeys(left.indexKey(), right.indexKey()));
        List<MvccIndexTuple> tuples = new ArrayList<>(matches.size());
        for (Candidate candidate : matches) {
            tuples.add(candidate.tuple());
        }
        return List.copyOf(tuples);
    }

    public synchronized int candidateCount() throws IOException {
        return readCandidates().size();
    }

    public synchronized int candidateCount(Object indexKey) throws IOException {
        return lookupCandidates(indexKey).size();
    }

    public synchronized int candidateCount(String indexName, Object indexKey) throws IOException {
        return lookupCandidates(indexName, indexKey).size();
    }

    public synchronized int indexedKeyCount() throws IOException {
        List<Object> distinct = new ArrayList<>();
        for (Candidate candidate : readCandidates()) {
            boolean seen = false;
            for (Object existing : distinct) {
                if (sameKey(existing, candidate.indexKey())) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct.add(candidate.indexKey());
            }
        }
        return distinct.size();
    }

    public synchronized long pageCount() throws IOException {
        return pageVolume.pageCount();
    }

    public synchronized PruneResult pruneCandidates(BiPredicate<Object, MvccIndexTuple> keepPredicate)
            throws IOException {
        Objects.requireNonNull(keepPredicate, "keepPredicate");
        List<Candidate> before = readCandidates();
        List<Candidate> retained = new ArrayList<>();
        for (Candidate candidate : before) {
            if (keepPredicate.test(candidate.indexKey(), candidate.tuple())) {
                retained.add(candidate);
            }
        }
        rewrite(retained);
        return new PruneResult(before.size() - retained.size(), retained.size());
    }

    @Override
    public synchronized void close() throws IOException {
        pageVolume.close();
    }

    private void appendTuple(MvccIndexTuple tuple, Object indexKey) throws IOException {
        byte[] encoded = MvccIndexTupleCodec.encode(indexKey, tuple);
        appendEncoded(encoded);
        pageVolume.force();
    }

    private void appendEncoded(byte[] encoded) throws IOException {
        if (encoded.length + SLOT_OVERHEAD_BYTES > maxPayloadBytes()) {
            throw new IllegalArgumentException("durable MVCC index tuple is too large: " + encoded.length);
        }

        long count = pageVolume.pageCount();
        DelosPage page;
        if (count == 0L) {
            page = pageVolume.allocatePage(INDEX_PAGE_TYPE);
        } else {
            page = pageVolume.readPage(new DelosPageId(count - 1L));
            if (page.pageType() != INDEX_PAGE_TYPE || page.freeBytes() < encoded.length + SLOT_OVERHEAD_BYTES) {
                page = pageVolume.allocatePage(INDEX_PAGE_TYPE);
            }
        }
        page.appendRecord(encoded);
        pageVolume.writePage(page);
    }

    private List<Candidate> readCandidates() throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        long count = pageVolume.pageCount();
        for (long pageNumber = 0; pageNumber < count; pageNumber++) {
            DelosPage page = pageVolume.readPage(new DelosPageId(pageNumber));
            if (page.pageType() != INDEX_PAGE_TYPE) {
                throw new IllegalStateException("expected MVCC index page type " + INDEX_PAGE_TYPE
                        + ", got " + page.pageType() + " at page " + pageNumber);
            }
            for (int slot = 0; slot < page.slotCount(); slot++) {
                MvccIndexTupleCodec.DecodedIndexTuple decoded = MvccIndexTupleCodec.decodeWithKey(page.readRecord(slot));
                candidates.add(new Candidate(decoded.indexKey(), decoded.tuple()));
            }
        }
        return candidates;
    }

    private void rewrite(List<Candidate> retained) throws IOException {
        pageVolume.close();
        Files.deleteIfExists(path);
        pageVolume = FileChannelPageVolume.open(path);
        for (Candidate candidate : retained) {
            appendEncoded(MvccIndexTupleCodec.encode(candidate.indexKey(), candidate.tuple()));
        }
        pageVolume.force();
    }

    private static MvccIndexTuple keyedTuple(String indexName, Object indexKey, MvccIndexTuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        return MvccIndexTuple.active(
                indexName,
                keyBytes(indexKey),
                tuple.rowId(),
                tuple.versionId(),
                tuple.versionLocator());
    }

    private static String defaultIndexName(MvccIndexTuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        return tuple.indexName().isBlank() ? DEFAULT_INDEX_NAME : tuple.indexName();
    }

    private static byte[] keyBytes(Object indexKey) {
        if (indexKey instanceof String value) {
            return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (indexKey instanceof Integer value) {
            return java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
        }
        if (indexKey instanceof Long value) {
            return java.nio.ByteBuffer.allocate(Long.BYTES).putLong(value).array();
        }
        if (indexKey instanceof byte[] value) {
            return value.clone();
        }
        throw new IllegalArgumentException("unsupported durable MVCC index key type: "
                + Objects.requireNonNull(indexKey, "indexKey").getClass().getName());
    }

    private static boolean sameKey(Object left, Object right) {
        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return Arrays.equals(leftBytes, rightBytes);
        }
        return Objects.equals(left, right);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compareKeys(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (left instanceof Comparable comparable && left.getClass().isInstance(right)) {
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static boolean inRange(
            Object key,
            Object fromKey,
            boolean fromInclusive,
            Object toKey,
            boolean toInclusive) {
        if (fromKey != null) {
            int lower = compareKeys(key, fromKey);
            if (lower < 0 || (lower == 0 && !fromInclusive)) {
                return false;
            }
        }
        if (toKey != null) {
            int upper = compareKeys(key, toKey);
            if (upper > 0 || (upper == 0 && !toInclusive)) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeIndexName(String indexName) {
        return Objects.requireNonNull(indexName, "indexName").toUpperCase(Locale.ROOT);
    }

    public record PruneResult(int removedCandidates, int remainingCandidates) {
        public PruneResult {
            if (removedCandidates < 0 || remainingCandidates < 0) {
                throw new IllegalArgumentException("prune counts must not be negative");
            }
        }
    }

    private static int maxPayloadBytes() {
        return DelosPage.empty(new DelosPageId(0L), INDEX_PAGE_TYPE).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private record Candidate(Object indexKey, MvccIndexTuple tuple) {
        private Candidate {
            tuple = Objects.requireNonNull(tuple, "tuple");
        }
    }
}
