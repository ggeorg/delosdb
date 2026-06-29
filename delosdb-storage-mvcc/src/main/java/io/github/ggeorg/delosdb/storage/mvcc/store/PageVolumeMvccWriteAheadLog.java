package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.DelosLogSequenceNumber;

/**
 * Provider-local WAL boundary for page-volume MVCC state.
 *
 * <p>This log is intentionally local to the inherited Derby-facing MVCC state
 * store. It must not depend on the quarantined {@code VersionedStorageProvider}
 * SPI. The authoritative recovery path for page-backed MVCC is the page mutation
 * log plus the transaction outcome log; this class only provides forced write
 * boundaries and page LSNs for the inherited state-store materialization path.</p>
 */
public final class PageVolumeMvccWriteAheadLog {
    private static final String LOG_VERSION = "2";
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
        Path logFile = PageVolumeMvccPaths.writeAheadLogFile(databaseDirectory, storageId);
        if (logFile == null || storageId == null || storageId.isBlank()) {
            return disabled();
        }
        Path parent = logFile.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create MVCC page-volume WAL directory: " + parent, e);
            }
        }
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
        byte[] bytes = encodeLine(lsn, type, transactionId, commitSequence, rowId).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append MVCC page-volume WAL record to: " + path, e);
        }
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
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC page-volume WAL: " + path, e);
        }
        if (content.isEmpty()) {
            return DelosLogSequenceNumber.NONE;
        }

        boolean hasCompleteFinalLine = content.endsWith("\n") || content.endsWith("\r");
        String[] lines = content.split("\\R", -1);
        int lastLineIndex = lines.length - 1;
        if (hasCompleteFinalLine && lastLineIndex >= 0 && lines[lastLineIndex].isEmpty()) {
            lastLineIndex--;
        }
        if (!hasCompleteFinalLine) {
            lastLineIndex--;
        }

        long lastLsn = DelosLogSequenceNumber.NONE.value();
        for (int index = 0; index <= lastLineIndex; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
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
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(lineIndex, "invalid " + fieldName + ": " + value, e);
        }
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return new IllegalStateException("Corrupt MVCC page-volume WAL at line " + (lineIndex + 1) + ": " + message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message, Throwable cause) {
        return new IllegalStateException("Corrupt MVCC page-volume WAL at line " + (lineIndex + 1) + ": " + message, cause);
    }
}
