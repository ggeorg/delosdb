package io.github.ggeorg.delosdb.storage.mvcc;

/** Result of one MVCC cleanup pass. */
public record MvccCleanupResult(int removedVersions) {
    public MvccCleanupResult {
        if (removedVersions < 0) {
            throw new IllegalArgumentException("removedVersions must be non-negative: " + removedVersions);
        }
    }

    public MvccCleanupResult plus(MvccCleanupResult other) {
        return new MvccCleanupResult(removedVersions + other.removedVersions);
    }
}
