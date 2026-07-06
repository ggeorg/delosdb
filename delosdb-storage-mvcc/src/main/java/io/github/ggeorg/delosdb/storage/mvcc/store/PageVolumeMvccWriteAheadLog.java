package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;
import io.github.ggeorg.delosdb.storage.mvcc.durable.AbstractSidecarStore;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

/**
 * Provider-local WAL boundary for page-volume MVCC state.
 *
 * <p>This log is intentionally local to the inherited Derby-facing MVCC state
 * store. It must not depend on the removed stand-alone versioned-storage
 * prototype SPI. The authoritative recovery path for page-backed MVCC is the page mutation
 * log plus the transaction outcome log; this class only provides forced write
 * boundaries and page LSNs for the inherited state-store materialization path.</p>
 */
public final class PageVolumeMvccWriteAheadLog {
    private static final String LOG_VERSION = "2";
    private static final String LOG_NAME = "MVCC page-volume WAL";
    private static final PageVolumeMvccWriteAheadLog DISABLED =
            new PageVolumeMvccWriteAheadLog(null, "disabled", 1L, false);

    private final Path path;
    private final String storageId;
    private final boolean enabled;
    private long nextLsnValue;

    private PageVolumeMvccWriteAheadLog(Path path, String storageId, long nextLsnValue, boolean enabled) {
        this.path = path;
        this.storageId = Objects.requireNonNull(storageId, "storageId");
        this.nextLsnValue = nextLsnValue;
        this.enabled = enabled;
    }

    public static PageVolumeMvccWriteAheadLog open(Path databaseDirectory, String storageId) {
        if (databaseDirectory == null || PageVolumeMvccPaths.isMissingStorageId(storageId)) {
            return disabled();
        }
        Path logFile = PageVolumeMvccPaths.writeAheadLogFile(databaseDirectory, storageId);
        if (logFile == null) {
            return disabled();
        }
        AbstractSidecarStore.ensureParentDirectory(logFile, "MVCC page-volume WAL");
        return new PageVolumeMvccWriteAheadLog(logFile, storageId, recoverLastLsn(logFile).value() + 1L, true);
    }

    public static PageVolumeMvccWriteAheadLog disabled() {
        return DISABLED;
    }

    public Path path() {
        return path;
    }

    public boolean enabled() {
        return enabled;
    }

    public void appendBegin(long transactionId) {
        if (enabled()) {
            append("BEGIN", transactionId, 0L, 0L);
        }
    }

    public DelosLogSequenceNumber appendInsertVersion(long transactionId, long rowId) {
        return appendVersion("INSERT_VERSION", transactionId, rowId);
    }

    public DelosLogSequenceNumber appendUpdateVersion(long transactionId, long rowId) {
        return appendVersion("UPDATE_VERSION", transactionId, rowId);
    }

    public DelosLogSequenceNumber appendDeleteVersion(long transactionId, long rowId) {
        return appendVersion("DELETE_VERSION", transactionId, rowId);
    }

    public void appendCommit(long transactionId, long commitSequence) {
        if (enabled()) {
            append("COMMIT", transactionId, commitSequence, 0L);
        }
    }

    public void appendAbort(long transactionId) {
        if (enabled()) {
            append("ABORT", transactionId, 0L, 0L);
        }
    }

    private DelosLogSequenceNumber appendVersion(String type, long transactionId, long rowId) {
        if (!enabled()) {
            return DelosLogSequenceNumber.NONE;
        }
        if (rowId <= 0L) {
            throw new IllegalArgumentException("MVCC page-volume WAL row id must be positive: " + rowId);
        }
        return append(type, transactionId, 0L, rowId);
    }

    private synchronized DelosLogSequenceNumber append(String type, long transactionId, long commitSequence, long rowId) {
        DelosLogSequenceNumber lsn = new DelosLogSequenceNumber(nextLsnValue++);
        AbstractSidecarStore.appendUtf8Forced(
                path,
                encodeLine(lsn, type, transactionId, commitSequence, rowId),
                "MVCC page-volume WAL record");
        return lsn;
    }

    private String encodeLine(
            DelosLogSequenceNumber lsn,
            String type,
            long transactionId,
            long commitSequence,
            long rowId) {
        return LOG_VERSION
                + '\t' + lsn.value()
                + '\t' + type
                + '\t' + transactionId
                + '\t' + commitSequence
                + '\t' + storageId
                + '\t' + rowId
                + '\n';
    }

    private static DelosLogSequenceNumber recoverLastLsn(Path path) {
        if (path == null || !Files.exists(path)) {
            return DelosLogSequenceNumber.NONE;
        }
        String content = AbstractSidecarStore.readUtf8IfExists(path, LOG_NAME);
        if (content.isEmpty()) {
            return DelosLogSequenceNumber.NONE;
        }

        long lastLsn = DelosLogSequenceNumber.NONE.value();
        for (MvccDurableLineRecords.LineRecord lineRecord
                : MvccDurableLineRecords.completeRecords(content, false)) {
            int index = lineRecord.lineIndex();
            String[] parts = MvccDurableLineRecords.tabFields(lineRecord.line());
            if (parts.length < 2) {
                throw corrupt(index, "record must contain at least version and LSN");
            }
            if (!LOG_VERSION.equals(parts[0]) && !"1".equals(parts[0])) {
                throw corrupt(index, "unsupported MVCC page-volume WAL version: " + parts[0]);
            }
            long lsn = parseLong(parts[1], index, "lsn");
            if (lsn <= lastLsn) {
                throw corrupt(index, "LSN must increase monotonically: previous=" + lastLsn + ", current=" + lsn);
            }
            lastLsn = lsn;
        }
        return new DelosLogSequenceNumber(lastLsn);
    }

    private static long parseLong(String value, int lineIndex, String fieldName) {
        return MvccDurableLineRecords.parseLong(value, lineIndex, fieldName, LOG_NAME);
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return MvccDurableLineRecords.corrupt(LOG_NAME, lineIndex, message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message, Throwable cause) {
        return MvccDurableLineRecords.corrupt(LOG_NAME, lineIndex, message, cause);
    }
}
