package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.durable.AbstractSidecarStore;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

/**
 * Provider-local subsystem recovery metadata for inherited MVCC page volumes.
 *
 * <p>This is deliberately not a PostgreSQL WAL clone and it is not yet the redo
 * executor. It records the subsystem boundaries that a later recovery/checkpoint
 * phase can replay independently: row pages, ordered index pages, overflow
 * chunks, free-space map, transaction outcomes, and checkpoints.</p>
 */
public final class MvccSubsystemRecoveryRecordStore {
    private static final String LOG_VERSION = "1";
    private static final String LOG_NAME = "MVCC subsystem recovery records";

    private final Path path;
    private final String storageId;
    private final boolean enabled;
    private long nextSequence;

    private MvccSubsystemRecoveryRecordStore(Path path, String storageId, long nextSequence, boolean enabled) {
        this.path = path;
        this.storageId = Objects.requireNonNull(storageId, "storageId");
        this.nextSequence = nextSequence;
        this.enabled = enabled;
    }

    public static MvccSubsystemRecoveryRecordStore open(Path databaseDirectory, String storageId) {
        if (databaseDirectory == null || PageVolumeMvccPaths.isMissingStorageId(storageId)) {
            return disabled();
        }
        Path file = PageVolumeMvccPaths.subsystemRecoveryRecordsFile(databaseDirectory, storageId);
        if (file == null) {
            return disabled();
        }
        AbstractSidecarStore.ensureParentDirectory(file, "MVCC subsystem recovery");
        return new MvccSubsystemRecoveryRecordStore(file, storageId, recoverLastSequence(file) + 1L, true);
    }

    public static MvccSubsystemRecoveryRecordStore disabled() {
        return new MvccSubsystemRecoveryRecordStore(null, "disabled", 1L, false);
    }

    public Path path() {
        return path;
    }

    public boolean enabled() {
        return enabled;
    }

    public void appendRowPageRedo(long transactionId, long commitSequence, long pageCount, long physicalVersionCount) {
        append(Subsystem.ROW_PAGE, "redo", transactionId, commitSequence, pageCount, physicalVersionCount);
    }

    public void appendIndexPageRedo(long orderedIndexPageCount, long orderedIndexEntryCount) {
        append(Subsystem.INDEX_PAGE, "redo", 0L, 0L, orderedIndexPageCount, orderedIndexEntryCount);
    }

    public void appendOverflowPageRedo(long overflowPageCount, long overflowValueBytes) {
        append(Subsystem.OVERFLOW_PAGE, "redo", 0L, 0L, overflowPageCount, overflowValueBytes);
    }

    public void appendFreeSpaceMapRedo(long freeSpaceMapPageCount, long freeSpaceMapUpdateCount) {
        append(Subsystem.FREE_SPACE_MAP, "redo", 0L, 0L, freeSpaceMapPageCount, freeSpaceMapUpdateCount);
    }

    public void appendTransactionOutcomeRedo(long transactionId, long commitSequence) {
        append(Subsystem.TRANSACTION_OUTCOME, "redo", transactionId, commitSequence, 0L, 0L);
    }

    public void appendCheckpoint(long physicalVersionCount, long logicalRowCount) {
        append(Subsystem.CHECKPOINT, "checkpoint", 0L, 0L, physicalVersionCount, logicalRowCount);
    }

    public Diagnostics diagnostics() {
        if (!enabled() || !Files.exists(path)) {
            return Diagnostics.empty(path);
        }
        return diagnostics(AbstractSidecarStore.readUtf8IfExists(path, LOG_NAME), path);
    }

    private synchronized void append(
            Subsystem subsystem,
            String action,
            long transactionId,
            long commitSequence,
            long primaryValue,
            long secondaryValue) {
        if (!enabled()) {
            return;
        }
        RecoveryRecord record = new RecoveryRecord(
                nextSequence++, subsystem, action, transactionId, commitSequence, primaryValue, secondaryValue);
        AbstractSidecarStore.appendUtf8Forced(path, encode(record), "MVCC subsystem recovery record");
    }

    private String encode(RecoveryRecord record) {
        return LOG_VERSION
                + '\t' + record.sequence()
                + '\t' + storageId
                + '\t' + record.subsystem().name()
                + '\t' + record.action()
                + '\t' + record.transactionId()
                + '\t' + record.commitSequence()
                + '\t' + record.primaryValue()
                + '\t' + record.secondaryValue()
                + '\n';
    }

    private static Diagnostics diagnostics(String content, Path path) {
        long recordCount = 0L;
        long lastSequence = 0L;
        Map<Subsystem, Long> counts = new EnumMap<>(Subsystem.class);
        List<String> summaries = new ArrayList<>();
        for (MvccDurableLineRecords.LineRecord lineRecord
                : MvccDurableLineRecords.completeRecords(content, false)) {
            int lineIndex = lineRecord.lineIndex();
            String[] parts = MvccDurableLineRecords.tabFields(lineRecord.line());
            if (parts.length != 9) {
                throw corrupt(lineIndex, "record must contain 9 tab-separated fields");
            }
            if (!LOG_VERSION.equals(parts[0])) {
                throw corrupt(lineIndex, "unsupported MVCC subsystem recovery record version: " + parts[0]);
            }
            long sequence = parseLong(parts[1], lineIndex, "sequence");
            if (sequence <= lastSequence) {
                throw corrupt(lineIndex, "sequence must increase monotonically: previous="
                        + lastSequence + ", current=" + sequence);
            }
            lastSequence = sequence;
            Subsystem subsystem;
            try {
                subsystem = Subsystem.valueOf(parts[3]);
            } catch (IllegalArgumentException e) {
                throw corrupt(lineIndex, "unknown MVCC subsystem recovery record type: " + parts[3], e);
            }
            long transactionId = parseLong(parts[5], lineIndex, "transactionId");
            long commitSequence = parseLong(parts[6], lineIndex, "commitSequence");
            long primary = parseLong(parts[7], lineIndex, "primaryValue");
            long secondary = parseLong(parts[8], lineIndex, "secondaryValue");
            counts.merge(subsystem, 1L, Long::sum);
            recordCount++;
            summaries.add(sequence + "|" + parts[3] + "|" + parts[4]
                    + "|tx=" + transactionId
                    + "|commit=" + commitSequence
                    + "|primary=" + primary
                    + "|secondary=" + secondary);
        }
        return new Diagnostics(path, recordCount, lastSequence, counts, List.copyOf(summaries));
    }

    private static long recoverLastSequence(Path path) {
        if (path == null || !Files.exists(path)) {
            return 0L;
        }
        return diagnostics(AbstractSidecarStore.readUtf8IfExists(path, LOG_NAME), path).lastSequence();
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

    public enum Subsystem {
        ROW_PAGE,
        INDEX_PAGE,
        OVERFLOW_PAGE,
        FREE_SPACE_MAP,
        TRANSACTION_OUTCOME,
        CHECKPOINT
    }

    private record RecoveryRecord(
            long sequence,
            Subsystem subsystem,
            String action,
            long transactionId,
            long commitSequence,
            long primaryValue,
            long secondaryValue) {
        private RecoveryRecord {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("recovery record sequence must be positive: " + sequence);
            }
            subsystem = Objects.requireNonNull(subsystem, "subsystem");
            action = Objects.requireNonNull(action, "action");
            if (transactionId < 0L || commitSequence < 0L || primaryValue < 0L || secondaryValue < 0L) {
                throw new IllegalArgumentException("recovery record values must not be negative");
            }
        }
    }

    public record Diagnostics(
            Path path,
            long recordCount,
            long lastSequence,
            Map<Subsystem, Long> counts,
            List<String> summaries) {
        public Diagnostics {
            if (recordCount < 0L || lastSequence < 0L) {
                throw new IllegalArgumentException("recovery diagnostics counts must not be negative");
            }
            counts = Map.copyOf(Objects.requireNonNull(counts, "counts"));
            summaries = List.copyOf(Objects.requireNonNull(summaries, "summaries"));
        }

        public static Diagnostics empty(Path path) {
            return new Diagnostics(path, 0L, 0L, Map.of(), List.of());
        }

        public long count(Subsystem subsystem) {
            return counts.getOrDefault(Objects.requireNonNull(subsystem, "subsystem"), 0L);
        }

        public boolean has(Subsystem subsystem) {
            return count(subsystem) > 0L;
        }
    }
}
