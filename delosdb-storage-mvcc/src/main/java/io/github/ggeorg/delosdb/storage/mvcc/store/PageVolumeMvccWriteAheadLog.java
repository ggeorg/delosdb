package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccLogWriter;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

/** Provider-local WAL boundary for page-volume MVCC state. */
public final class PageVolumeMvccWriteAheadLog {
    private final Path path;
    private final VersionedTableMetadata metadata;
    private final MvccLogWriter writer;

    private PageVolumeMvccWriteAheadLog(Path path, VersionedTableMetadata metadata, MvccLogWriter writer) {
        this.path = path;
        this.metadata = metadata;
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    public static PageVolumeMvccWriteAheadLog open(Path databaseDirectory, String storageId) {
        Path logFile = PageVolumeMvccPaths.writeAheadLogFile(databaseDirectory, storageId);
        if (logFile == null || storageId == null || storageId.isBlank()) {
            return disabled();
        }
        return new PageVolumeMvccWriteAheadLog(
                logFile,
                new VersionedTableMetadata("INHERITED_MVCC", storageId.toUpperCase(java.util.Locale.ROOT)),
                MvccLogWriter.open(logFile));
    }

    public static PageVolumeMvccWriteAheadLog disabled() {
        return new PageVolumeMvccWriteAheadLog(null, null, MvccLogWriter.disabled());
    }

    public Path path() {
        return path;
    }

    public boolean enabled() {
        return writer.isEnabled();
    }

    public void appendBegin(long transactionId) {
        if (enabled()) {
            writer.appendBegin(new MvccTransactionId(transactionId));
        }
    }

    public DelosLogSequenceNumber appendInsertVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendInsertVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    public DelosLogSequenceNumber appendUpdateVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendUpdateVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    public DelosLogSequenceNumber appendDeleteVersion(long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        return writer.appendDeleteVersion(new MvccTransactionId(transactionId), metadata, rowId).lsn();
    }

    public void appendCommit(long transactionId, long commitSequence) {
        if (enabled()) {
            writer.appendCommit(new MvccTransactionId(transactionId), new MvccCommitSequence(commitSequence));
        }
    }

    public void appendAbort(long transactionId) {
        if (enabled()) {
            writer.appendAbort(new MvccTransactionId(transactionId));
        }
    }
}
