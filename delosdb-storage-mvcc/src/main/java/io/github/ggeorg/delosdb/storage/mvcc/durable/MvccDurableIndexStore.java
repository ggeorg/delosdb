package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * Compatibility facade kept for the original A6 durable-index tests.
 *
 * <p>The canonical implementation is {@link MvccIndexStore}. This class exists
 * only so older proof code that used the more explicit name continues to compile
 * while the implementation is stabilized around one store.</p>
 */
public final class MvccDurableIndexStore implements AutoCloseable {
    private final MvccIndexStore delegate;

    private MvccDurableIndexStore(MvccIndexStore delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public static MvccDurableIndexStore open(Path path) throws IOException {
        return new MvccDurableIndexStore(MvccIndexStore.open(path));
    }

    public void appendCandidate(Object indexKey, MvccIndexTuple tuple) throws IOException {
        delegate.appendCandidate(indexKey, tuple);
    }

    public void appendCandidate(String indexName, Object indexKey, MvccIndexTuple tuple) throws IOException {
        delegate.appendCandidate(indexName, indexKey, tuple);
    }

    public void appendCandidate(MvccIndexTuple tuple) throws IOException {
        delegate.appendCandidate(tuple);
    }

    public List<MvccIndexTuple> lookupCandidates(Object indexKey) throws IOException {
        return delegate.lookupCandidates(indexKey);
    }

    public List<MvccIndexTuple> lookupCandidates(String indexName, Object indexKey) throws IOException {
        return delegate.lookupCandidates(indexName, indexKey);
    }

    public List<MvccIndexTuple> lookupRangeCandidates(Object fromInclusive, Object toInclusive)
            throws IOException {
        return delegate.lookupRangeCandidates(fromInclusive, toInclusive);
    }

    public List<MvccIndexTuple> lookupRangeCandidates(
            Object fromKey,
            boolean fromInclusive,
            Object toKey,
            boolean toInclusive) throws IOException {
        return delegate.lookupRangeCandidates(fromKey, fromInclusive, toKey, toInclusive);
    }

    public int candidateCount() throws IOException {
        return delegate.candidateCount();
    }

    public int candidateCount(Object indexKey) throws IOException {
        return delegate.candidateCount(indexKey);
    }

    public int candidateCount(String indexName, Object indexKey) throws IOException {
        return delegate.candidateCount(indexName, indexKey);
    }

    public int indexedKeyCount() throws IOException {
        return delegate.indexedKeyCount();
    }

    public long pageCount() throws IOException {
        return delegate.pageCount();
    }

    public PruneResult pruneCandidates(BiPredicate<Object, MvccIndexTuple> keepPredicate) throws IOException {
        MvccIndexStore.PruneResult result = delegate.pruneCandidates(keepPredicate);
        return new PruneResult(result.removedCandidates(), result.remainingCandidates());
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    public record PruneResult(int removedCandidates, int remainingCandidates) {
        public PruneResult {
            if (removedCandidates < 0 || remainingCandidates < 0) {
                throw new IllegalArgumentException("prune counts must not be negative");
            }
        }
    }
}
