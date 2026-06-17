package io.github.ggeorg.delosdb.storage.mvcc.durable;

/** Result of one durable page-backed MVCC vacuum pass. */
public record MvccVacuumResult(
        int removedVersions,
        int removedIndexCandidates,
        int removedLogicalRows,
        int remainingVersions,
        int remainingLogicalRows) {
    public MvccVacuumResult {
        if (removedVersions < 0
                || removedIndexCandidates < 0
                || removedLogicalRows < 0
                || remainingVersions < 0
                || remainingLogicalRows < 0) {
            throw new IllegalArgumentException("vacuum counts must not be negative");
        }
    }

    MvccVacuumResult withRemovedIndexCandidates(int removedCandidates) {
        if (removedCandidates < 0) {
            throw new IllegalArgumentException("removedCandidates must not be negative: " + removedCandidates);
        }
        return new MvccVacuumResult(
                removedVersions,
                removedCandidates,
                removedLogicalRows,
                remainingVersions,
                remainingLogicalRows);
    }
}
