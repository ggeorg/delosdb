package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

/**
 * Small provider-local MVCC log record used by the MODULE5J WAL/pageLSN
 * skeleton. The records establish ordering and force points only; they are not
 * a complete redo/undo format and are deliberately separate from Derby WAL.
 */
public record MvccLogRecord(
        DelosLogSequenceNumber lsn,
        Type type,
        MvccTransactionId transactionId,
        MvccCommitSequence commitSequence,
        VersionedTableMetadata table,
        Long rowKey) {
    public MvccLogRecord {
        lsn = Objects.requireNonNull(lsn, "lsn");
        type = Objects.requireNonNull(type, "type");
        transactionId = Objects.requireNonNull(transactionId, "transactionId");
        commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
        if (transactionId.isNone()) {
            throw new IllegalArgumentException("MVCC log record transaction id must be present");
        }
        switch (type) {
        case BEGIN_TXN, ABORT_TXN -> {
            requireNoCommitSequence(type, commitSequence);
            requireNoVersionIdentity(type, table, rowKey);
        }
        case COMMIT_TXN -> {
            if (commitSequence.equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("COMMIT_TXN log record must carry a commit sequence");
            }
            requireNoVersionIdentity(type, table, rowKey);
        }
        case INSERT_VERSION, DELETE_VERSION, UPDATE_VERSION -> {
            requireNoCommitSequence(type, commitSequence);
            Objects.requireNonNull(table, "table");
            Objects.requireNonNull(rowKey, "rowKey");
        }
        }
    }

    public enum Type {
        BEGIN_TXN,
        INSERT_VERSION,
        DELETE_VERSION,
        UPDATE_VERSION,
        COMMIT_TXN,
        ABORT_TXN
    }

    public MvccLogRecord withLsn(DelosLogSequenceNumber assignedLsn) {
        return new MvccLogRecord(assignedLsn, type, transactionId, commitSequence, table, rowKey);
    }

    public static MvccLogRecord begin(MvccTransactionId transactionId) {
        return new MvccLogRecord(
                DelosLogSequenceNumber.NONE,
                Type.BEGIN_TXN,
                transactionId,
                MvccCommitSequence.NONE,
                null,
                null);
    }

    public static MvccLogRecord insertVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            long rowKey) {
        return version(Type.INSERT_VERSION, transactionId, table, rowKey);
    }

    public static MvccLogRecord updateVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            long rowKey) {
        return version(Type.UPDATE_VERSION, transactionId, table, rowKey);
    }

    public static MvccLogRecord deleteVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            long rowKey) {
        return version(Type.DELETE_VERSION, transactionId, table, rowKey);
    }

    public static MvccLogRecord commit(
            MvccTransactionId transactionId,
            MvccCommitSequence commitSequence) {
        return new MvccLogRecord(
                DelosLogSequenceNumber.NONE,
                Type.COMMIT_TXN,
                transactionId,
                commitSequence,
                null,
                null);
    }

    public static MvccLogRecord abort(MvccTransactionId transactionId) {
        return new MvccLogRecord(
                DelosLogSequenceNumber.NONE,
                Type.ABORT_TXN,
                transactionId,
                MvccCommitSequence.NONE,
                null,
                null);
    }

    private static MvccLogRecord version(
            Type type,
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            long rowKey) {
        return new MvccLogRecord(
                DelosLogSequenceNumber.NONE,
                type,
                transactionId,
                MvccCommitSequence.NONE,
                table,
                rowKey);
    }

    private static void requireNoCommitSequence(Type type, MvccCommitSequence commitSequence) {
        if (!commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException(type + " log record must not carry a commit sequence");
        }
    }

    private static void requireNoVersionIdentity(Type type, VersionedTableMetadata table, Long rowKey) {
        if (table != null || rowKey != null) {
            throw new IllegalArgumentException(type + " log record must not carry table/key identity");
        }
    }
}
