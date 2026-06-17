package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BiPredicate;

import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPage;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageFile;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/**
 * Durable append-only index candidate store for the experimental page-backed
 * delos_mvcc engine.
 *
 * <p>The store maps an index key to one or more {@link MvccIndexTuple}
 * candidates. It does not decide tuple visibility. Callers must resolve each
 * candidate through the page-backed heap/version directory and recheck the
 * currently visible indexed value.</p>
 */
public final class MvccIndexStore implements AutoCloseable {
    private static final int SLOT_OVERHEAD_BYTES = 12;

    private final Path path;
    private MvccPageFile pageFile;
    private NavigableMap<Object, List<MvccIndexTuple>> candidatesByKey;

    private MvccIndexStore(Path path, MvccPageFile pageFile, NavigableMap<Object, List<MvccIndexTuple>> candidatesByKey) {
        this.path = Objects.requireNonNull(path, "path");
        this.pageFile = Objects.requireNonNull(pageFile, "pageFile");
        this.candidatesByKey = Objects.requireNonNull(candidatesByKey, "candidatesByKey");
    }

    public static MvccIndexStore open(Path path) throws IOException {
        MvccPageFile pageFile = MvccPageFile.open(path);
        try {
            return new MvccIndexStore(path, pageFile, loadCandidates(pageFile));
        } catch (RuntimeException | IOException failure) {
            try {
                pageFile.close();
            } catch (IOException suppressed) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    public synchronized void appendCandidate(Object indexKey, MvccIndexTuple tuple) throws IOException {
        Objects.requireNonNull(tuple, "tuple");
        byte[] encoded = MvccIndexTupleCodec.encode(indexKey, tuple);
        if (encoded.length > maxSingleTupleBytes()) {
            throw new IllegalArgumentException("MVCC index tuple is too large for one page: " + encoded.length);
        }

        MvccIndexPage page = writablePage(encoded.length);
        page.appendCandidate(indexKey, tuple);
        pageFile.writePage(page.page());
        pageFile.force();
        addCandidate(candidatesByKey, indexKey, tuple);
    }

    public synchronized List<MvccIndexTuple> lookupCandidates(Object indexKey) {
        List<MvccIndexTuple> candidates = candidatesByKey.get(indexKey);
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    public synchronized List<MvccIndexTuple> lookupRangeCandidates(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive) {
        List<MvccIndexTuple> candidates = new ArrayList<>();
        for (Map.Entry<Object, List<MvccIndexTuple>> entry
                : rangeEntries(lowerBound, lowerInclusive, upperBound, upperInclusive).entrySet()) {
            candidates.addAll(entry.getValue());
        }
        return List.copyOf(candidates);
    }

    /**
     * Rewrites the index file with only candidates accepted by {@code keep}.
     * This is deliberately coarse and proof-oriented; Phase A7 can replace it
     * with page-local vacuum once durable heap/index cleanup is designed.
     */
    public synchronized PruneResult pruneCandidates(BiPredicate<Object, MvccIndexTuple> keep) throws IOException {
        Objects.requireNonNull(keep, "keep");
        NavigableMap<Object, List<MvccIndexTuple>> kept = new TreeMap<>(MvccIndexStore::compareIndexKeys);
        int removed = 0;
        for (Map.Entry<Object, List<MvccIndexTuple>> entry : candidatesByKey.entrySet()) {
            for (MvccIndexTuple tuple : entry.getValue()) {
                if (keep.test(entry.getKey(), tuple)) {
                    addCandidate(kept, entry.getKey(), tuple);
                } else {
                    removed++;
                }
            }
        }
        if (removed == 0) {
            return new PruneResult(0, candidateCount());
        }

        Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
        Files.deleteIfExists(rewritePath);
        try (MvccPageFile rewrite = MvccPageFile.open(rewritePath)) {
            for (Map.Entry<Object, List<MvccIndexTuple>> entry : kept.entrySet()) {
                for (MvccIndexTuple tuple : entry.getValue()) {
                    appendCandidateTo(rewrite, entry.getKey(), tuple);
                }
            }
            rewrite.force();
        }

        pageFile.close();
        Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
        pageFile = MvccPageFile.open(path);
        candidatesByKey = loadCandidates(pageFile);
        return new PruneResult(removed, candidateCount());
    }

    public synchronized int indexedKeyCount() {
        return candidatesByKey.size();
    }

    public synchronized int candidateCount() {
        int total = 0;
        for (List<MvccIndexTuple> bucket : candidatesByKey.values()) {
            total += bucket.size();
        }
        return total;
    }

    public synchronized int candidateCount(Object indexKey) {
        List<MvccIndexTuple> candidates = candidatesByKey.get(indexKey);
        return candidates == null ? 0 : candidates.size();
    }

    public synchronized long pageCount() throws IOException {
        return pageFile.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        pageFile.close();
    }

    private MvccIndexPage writablePage(int encodedTupleLength) throws IOException {
        long count = pageFile.pageCount();
        if (count == 0) {
            return MvccIndexPage.from(pageFile.allocatePage(MvccIndexPage.PAGE_TYPE));
        }
        MvccIndexPage last = MvccIndexPage.from(pageFile.readPage(new MvccPageId(count - 1L)));
        if (last.freeBytes() >= encodedTupleLength + SLOT_OVERHEAD_BYTES) {
            return last;
        }
        return MvccIndexPage.from(pageFile.allocatePage(MvccIndexPage.PAGE_TYPE));
    }

    private NavigableMap<Object, List<MvccIndexTuple>> rangeEntries(
            Object lowerBound,
            boolean lowerInclusive,
            Object upperBound,
            boolean upperInclusive) {
        if (lowerBound != null && upperBound != null && compareIndexKeys(lowerBound, upperBound) > 0) {
            return new TreeMap<>(MvccIndexStore::compareIndexKeys);
        }
        if (lowerBound == null && upperBound == null) {
            return candidatesByKey;
        }
        if (lowerBound == null) {
            return candidatesByKey.headMap(upperBound, upperInclusive);
        }
        if (upperBound == null) {
            return candidatesByKey.tailMap(lowerBound, lowerInclusive);
        }
        return candidatesByKey.subMap(lowerBound, lowerInclusive, upperBound, upperInclusive);
    }

    private static NavigableMap<Object, List<MvccIndexTuple>> loadCandidates(MvccPageFile pageFile) throws IOException {
        NavigableMap<Object, List<MvccIndexTuple>> loaded = new TreeMap<>(MvccIndexStore::compareIndexKeys);
        long pageCount = pageFile.pageCount();
        for (long pageNumber = 0; pageNumber < pageCount; pageNumber++) {
            MvccPage rawPage = pageFile.readPage(new MvccPageId(pageNumber));
            MvccIndexPage page = MvccIndexPage.from(rawPage);
            for (MvccIndexTupleCodec.DecodedIndexTuple decoded : page.candidates()) {
                addCandidate(loaded, decoded.indexKey(), decoded.tuple());
            }
        }
        return loaded;
    }

    private static void appendCandidateTo(MvccPageFile target, Object indexKey, MvccIndexTuple tuple) throws IOException {
        byte[] encoded = MvccIndexTupleCodec.encode(indexKey, tuple);
        if (encoded.length > maxSingleTupleBytes()) {
            throw new IllegalArgumentException("MVCC index tuple is too large for one page: " + encoded.length);
        }
        long count = target.pageCount();
        MvccIndexPage page;
        if (count == 0) {
            page = MvccIndexPage.from(target.allocatePage(MvccIndexPage.PAGE_TYPE));
        } else {
            page = MvccIndexPage.from(target.readPage(new MvccPageId(count - 1L)));
            if (!page.canFit(encoded)) {
                page = MvccIndexPage.from(target.allocatePage(MvccIndexPage.PAGE_TYPE));
            }
        }
        page.appendCandidate(indexKey, tuple);
        target.writePage(page.page());
    }

    private static int maxSingleTupleBytes() {
        return MvccIndexPage.empty(new MvccPageId(0L)).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private static void addCandidate(
            Map<Object, List<MvccIndexTuple>> candidatesByKey,
            Object indexKey,
            MvccIndexTuple tuple) {
        candidatesByKey.computeIfAbsent(indexKey, ignored -> new ArrayList<>()).add(tuple);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int compareIndexKeys(Object left, Object right) {
        if (left == right) {
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
        int classCompare = left.getClass().getName().compareTo(right.getClass().getName());
        if (classCompare != 0) {
            return classCompare;
        }
        return Comparator.comparing(Object::toString).compare(left, right);
    }

    public record PruneResult(int removedCandidates, int remainingCandidates) {
    }
}
