package io.github.ggeorg.delosdb.storage.mvcc;

/** Result of one MVCC cleanup/vacuum pass. */
public record MvccCleanupResult(
        int removedVersions,
        int removedIndexCandidates,
        int removedLogicalRows) {
    public MvccCleanupResult(int removedVersions) {
        this(removedVersions, 0, 0);
    }

    public MvccCleanupResult {
        if (removedVersions < 0) {
            throw new IllegalArgumentException("removedVersions must be non-negative: " + removedVersions);
        }
        if (removedIndexCandidates < 0) {
            throw new IllegalArgumentException("removedIndexCandidates must be non-negative: " + removedIndexCandidates);
        }
        if (removedLogicalRows < 0) {
            throw new IllegalArgumentException("removedLogicalRows must be non-negative: " + removedLogicalRows);
        }
    }

    public MvccCleanupResult plus(MvccCleanupResult other) {
        return new MvccCleanupResult(
                removedVersions + other.removedVersions,
                removedIndexCandidates + other.removedIndexCandidates,
                removedLogicalRows + other.removedLogicalRows);
    }
}
