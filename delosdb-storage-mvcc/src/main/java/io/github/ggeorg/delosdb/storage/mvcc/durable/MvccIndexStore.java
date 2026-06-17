package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiPredicate;

import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPage;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageFile;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/**
 * Durable MVCC index-candidate store used by the A7 vacuum proof.
 *
 * <p>The store deliberately keeps the index as a candidate structure: it never
 * decides visibility. A lookup returns row/version candidates and the
 * page-backed heap/version directory remains the source of truth.</p>
 */
public final class MvccIndexStore implements AutoCloseable {
    private static final int INDEX_PAGE_TYPE = 2;
    private static final int SLOT_OVERHEAD_BYTES = 12;
    private static final String DEFAULT_INDEX_NAME = "IDX";

    private final Path path;
    private MvccPageFile pageFile;

    private MvccIndexStore(Path path, MvccPageFile pageFile) {
        this.path = Objects.requireNonNull(path, "path");
        this.pageFile = Objects.requireNonNull(pageFile, "pageFile");
    }

    public static MvccIndexStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return new MvccIndexStore(path, MvccPageFile.open(path));
    }

    public synchronized void appendCandidate(Object indexKey, MvccIndexTuple tuple) throws IOException {
        Objects.requireNonNull(tuple, "tuple");
        appendTuple(keyedTuple(defaultIndexName(tuple), indexKey, tuple), indexKey);
    }

    public synchronized void appendCandidate(String indexName, Object indexKey, MvccIndexTuple tuple)
            throws IOException {
        Objects.requireNonNull(tuple, "tuple");
        appendTuple(keyedTuple(indexName, indexKey, tuple), indexKey);
    }

    public synchronized void appendCandidate(MvccIndexTuple tuple) throws IOException {
        Objects.requireNonNull(tuple, "tuple");
        appendTuple(tuple, tupleKey(tuple));
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
        List<MvccIndexTuple> matches = new ArrayList<>();
        for (Candidate candidate : readCandidates()) {
            Object key = candidate.indexKey();
            if (inRange(key, fromInclusive, toInclusive)) {
                matches.add(candidate.tuple());
            }
        }
        return List.copyOf(matches);
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
            Object key = candidate.indexKey();
            boolean seen = false;
            for (Object existing : distinct) {
                if (sameKey(existing, key)) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                distinct.add(key);
            }
        }
        return distinct.size();
    }

    public synchronized long pageCount() throws IOException {
        return pageFile.pageCount();
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
        pageFile.close();
    }

    private void appendTuple(MvccIndexTuple tuple, Object indexKey) throws IOException {
        byte[] encoded = encode(indexKey, tuple);
        appendEncoded(encoded);
        pageFile.force();
    }

    private void appendEncoded(byte[] encoded) throws IOException {
        if (encoded.length + SLOT_OVERHEAD_BYTES > maxPayloadBytes()) {
            throw new IllegalArgumentException("durable MVCC index tuple is too large: " + encoded.length);
        }

        long count = pageFile.pageCount();
        MvccPage page;
        if (count == 0L) {
            page = pageFile.allocatePage(INDEX_PAGE_TYPE);
        } else {
            page = pageFile.readPage(new MvccPageId(count - 1L));
            if (page.pageType() != INDEX_PAGE_TYPE || page.freeBytes() < encoded.length + SLOT_OVERHEAD_BYTES) {
                page = pageFile.allocatePage(INDEX_PAGE_TYPE);
            }
        }
        page.appendRecord(encoded);
        pageFile.writePage(page);
    }

    private List<Candidate> readCandidates() throws IOException {
        List<Candidate> candidates = new ArrayList<>();
        long count = pageFile.pageCount();
        for (long pageNumber = 0; pageNumber < count; pageNumber++) {
            MvccPage page = pageFile.readPage(new MvccPageId(pageNumber));
            if (page.pageType() != INDEX_PAGE_TYPE) {
                throw new IllegalStateException("expected MVCC index page type " + INDEX_PAGE_TYPE
                        + ", got " + page.pageType() + " at page " + pageNumber);
            }
            for (int slot = 0; slot < page.slotCount(); slot++) {
                candidates.add(decode(page.readRecord(slot)));
            }
        }
        return candidates;
    }

    private void rewrite(List<Candidate> retained) throws IOException {
        pageFile.close();
        Files.deleteIfExists(path);
        pageFile = MvccPageFile.open(path);
        for (Candidate candidate : retained) {
            appendEncoded(encode(candidate.indexKey(), candidate.tuple()));
        }
        pageFile.force();
    }

    private static MvccIndexTuple keyedTuple(String indexName, Object indexKey, MvccIndexTuple tuple) {
        return MvccIndexTuple.active(
                indexName,
                keyBytes(indexKey),
                tuple.rowId(),
                tuple.versionId(),
                tuple.versionLocator());
    }

    private static String defaultIndexName(MvccIndexTuple tuple) {
        String existing = tuple.indexName();
        return existing == null || existing.isBlank() ? DEFAULT_INDEX_NAME : existing;
    }

    private static Object tupleKey(MvccIndexTuple tuple) {
        return tuple.indexKeyAsUtf8();
    }

    private static byte[] encode(Object indexKey, MvccIndexTuple tuple) {
        try {
            Method encodeTuple = MvccIndexTupleCodec.class.getDeclaredMethod("encode", MvccIndexTuple.class);
            encodeTuple.setAccessible(true);
            return (byte[]) encodeTuple.invoke(null, tuple);
        } catch (NoSuchMethodException missingTupleOnlyCodec) {
            try {
                Method encodeKeyAndTuple = MvccIndexTupleCodec.class.getDeclaredMethod(
                        "encode", Object.class, MvccIndexTuple.class);
                encodeKeyAndTuple.setAccessible(true);
                return (byte[]) encodeKeyAndTuple.invoke(null, indexKey, tuple);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
                throw codecFailure("encode", failure);
            }
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw codecFailure("encode", failure);
        }
    }

    private static Candidate decode(byte[] encoded) {
        try {
            Method decode = MvccIndexTupleCodec.class.getDeclaredMethod("decode", byte[].class);
            decode.setAccessible(true);
            Object decoded = decode.invoke(null, encoded);
            if (decoded instanceof MvccIndexTuple tuple) {
                return new Candidate(tupleKey(tuple), tuple);
            }

            Method indexKey = decoded.getClass().getDeclaredMethod("indexKey");
            Method tuple = decoded.getClass().getDeclaredMethod("tuple");
            indexKey.setAccessible(true);
            tuple.setAccessible(true);
            return new Candidate(indexKey.invoke(decoded), (MvccIndexTuple) tuple.invoke(decoded));
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException failure) {
            throw codecFailure("decode", failure);
        }
    }

    private static IllegalStateException codecFailure(String operation, Exception failure) {
        Throwable cause = failure instanceof InvocationTargetException invocation && invocation.getCause() != null
                ? invocation.getCause()
                : failure;
        return new IllegalStateException("failed to " + operation + " durable MVCC index tuple", cause);
    }

    private static byte[] keyBytes(Object indexKey) {
        Objects.requireNonNull(indexKey, "indexKey");
        if (indexKey instanceof byte[] bytes) {
            return bytes.clone();
        }
        return String.valueOf(indexKey).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean sameKey(Object left, Object right) {
        if (left instanceof byte[] leftBytes && right instanceof byte[] rightBytes) {
            return java.util.Arrays.equals(leftBytes, rightBytes);
        }
        return Objects.equals(normalizeKey(left), normalizeKey(right));
    }

    private static Object normalizeKey(Object value) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean inRange(Object key, Object fromInclusive, Object toInclusive) {
        Object normalizedKey = normalizeKey(key);
        Object normalizedFrom = normalizeKey(fromInclusive);
        Object normalizedTo = normalizeKey(toInclusive);
        if (!(normalizedKey instanceof Comparable comparable)) {
            return Objects.equals(normalizedKey, normalizedFrom) || Objects.equals(normalizedKey, normalizedTo);
        }
        return (normalizedFrom == null || comparable.compareTo(normalizedFrom) >= 0)
                && (normalizedTo == null || comparable.compareTo(normalizedTo) <= 0);
    }

    private static String normalizeIndexName(String indexName) {
        return Objects.requireNonNull(indexName, "indexName").toUpperCase(Locale.ROOT);
    }

    private static int maxPayloadBytes() {
        return MvccPage.PAGE_SIZE - 28 - SLOT_OVERHEAD_BYTES;
    }

    private record Candidate(Object indexKey, MvccIndexTuple tuple) {
        private Candidate {
            tuple = Objects.requireNonNull(tuple, "tuple");
        }
    }

    public record PruneResult(int removedCandidates, int remainingCandidates) {
        public PruneResult {
            if (removedCandidates < 0 || remainingCandidates < 0) {
                throw new IllegalArgumentException("prune counts must not be negative");
            }
        }
    }
}
