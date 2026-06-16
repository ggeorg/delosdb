package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

/**
 * Durable metadata stored with every MVCC row version.
 *
 * <p>This mirrors the PostgreSQL-guided rule for the prototype: visibility is a
 * tuple/version property, not an index property. Indexes can point to row or
 * version candidates, but snapshots must recheck this metadata before returning
 * a row.</p>
 */
public record MvccTupleHeader(
        MvccRowId rowId,
        MvccVersionId versionId,
        MvccVersionId previousVersionId,
        MvccTransactionId createdByTx,
        MvccTransactionId deletedByTx,
        MvccCommitSequence commitSequence,
        int flags) {
    public MvccTupleHeader {
        rowId = Objects.requireNonNull(rowId, "rowId");
        versionId = Objects.requireNonNull(versionId, "versionId");
        previousVersionId = Objects.requireNonNull(previousVersionId, "previousVersionId");
        createdByTx = Objects.requireNonNull(createdByTx, "createdByTx");
        deletedByTx = Objects.requireNonNull(deletedByTx, "deletedByTx");
        commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
        if (rowId.isNone()) {
            throw new IllegalArgumentException("row id must be present for a durable MVCC version record");
        }
        if (versionId.isNone()) {
            throw new IllegalArgumentException("version id must be present for a durable MVCC version record");
        }
        if (previousVersionId.equals(versionId)) {
            throw new IllegalArgumentException("previous version id must not equal the current version id: "
                    + versionId.value());
        }
        if (createdByTx.isNone()) {
            throw new IllegalArgumentException("creator transaction id must be present for a durable MVCC version record");
        }
        MvccVersionRecordFlags.validate(flags);
        if (isTombstone(flags) && deletedByTx.isNone()) {
            throw new IllegalArgumentException("tombstone records must carry the deleting transaction id");
        }
    }

    public boolean hasPreviousVersion() {
        return !previousVersionId.isNone();
    }

    public boolean isTombstone() {
        return isTombstone(flags);
    }

    private static boolean isTombstone(int flags) {
        return (flags & MvccVersionRecordFlags.TOMBSTONE) != 0;
    }
}
