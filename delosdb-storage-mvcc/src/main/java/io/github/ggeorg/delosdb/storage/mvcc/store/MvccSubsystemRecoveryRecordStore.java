package io.github.ggeorg.delosdb.storage.mvcc.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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

    public ReplayPlan replayPlan() {
        if (!enabled() || !Files.exists(path)) {
            return ReplayPlan.empty(path);
        }
        return replayPlan(AbstractSidecarStore.readUtf8IfExists(path, LOG_NAME), path);
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
                nextSequence++, storageId, subsystem, action, transactionId, commitSequence, primaryValue, secondaryValue);
        AbstractSidecarStore.appendUtf8Forced(path, encode(record), "MVCC subsystem recovery record");
    }

    private String encode(RecoveryRecord record) {
        return LOG_VERSION
                + '\t' + record.sequence()
                + '\t' + record.storageId()
                + '\t' + record.subsystem().name()
                + '\t' + record.action()
                + '\t' + record.transactionId()
                + '\t' + record.commitSequence()
                + '\t' + record.primaryValue()
                + '\t' + record.secondaryValue()
                + '\n';
    }

    private static Diagnostics diagnostics(String content, Path path) {
        List<RecoveryRecord> records = parseRecords(content);
        long lastSequence = records.isEmpty() ? 0L : records.get(records.size() - 1).sequence();
        Map<Subsystem, Long> counts = new EnumMap<>(Subsystem.class);
        List<String> summaries = new ArrayList<>();
        for (RecoveryRecord record : records) {
            counts.merge(record.subsystem(), 1L, Long::sum);
            summaries.add(record.sequence() + "|" + record.subsystem().name() + "|" + record.action()
                    + "|tx=" + record.transactionId()
                    + "|commit=" + record.commitSequence()
                    + "|primary=" + record.primaryValue()
                    + "|secondary=" + record.secondaryValue());
        }
        return new Diagnostics(path, records.size(), lastSequence, counts, List.copyOf(summaries));
    }

    private static ReplayPlan replayPlan(String content, Path path) {
        return new ReplayPlan(path, parseRecords(content));
    }

    private static List<RecoveryRecord> parseRecords(String content) {
        long lastSequence = 0L;
        List<RecoveryRecord> records = new ArrayList<>();
        for (MvccDurableLineRecords.LineRecord lineRecord
                : MvccDurableLineRecords.completeRecords(content, false)) {
            RecoveryRecord record = parseRecord(lineRecord.line(), lineRecord.lineIndex());
            if (record.sequence() <= lastSequence) {
                throw corrupt(lineRecord.lineIndex(), "sequence must increase monotonically: previous="
                        + lastSequence + ", current=" + record.sequence());
            }
            lastSequence = record.sequence();
            records.add(record);
        }
        return List.copyOf(records);
    }

    private static RecoveryRecord parseRecord(String line, int lineIndex) {
        String[] parts = MvccDurableLineRecords.tabFields(line);
        if (parts.length != 9) {
            throw corrupt(lineIndex, "record must contain 9 tab-separated fields");
        }
        if (!LOG_VERSION.equals(parts[0])) {
            throw corrupt(lineIndex, "unsupported MVCC subsystem recovery record version: " + parts[0]);
        }
        long sequence = parseLong(parts[1], lineIndex, "sequence");
        Subsystem subsystem;
        try {
            subsystem = Subsystem.valueOf(parts[3]);
        } catch (IllegalArgumentException e) {
            throw corrupt(lineIndex, "unknown MVCC subsystem recovery record type: " + parts[3], e);
        }
        return new RecoveryRecord(
                sequence,
                parts[2],
                subsystem,
                parts[4],
                parseLong(parts[5], lineIndex, "transactionId"),
                parseLong(parts[6], lineIndex, "commitSequence"),
                parseLong(parts[7], lineIndex, "primaryValue"),
                parseLong(parts[8], lineIndex, "secondaryValue"));
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

    public record RecoveryRecord(
            long sequence,
            String storageId,
            Subsystem subsystem,
            String action,
            long transactionId,
            long commitSequence,
            long primaryValue,
            long secondaryValue) {
        public RecoveryRecord {
            if (sequence <= 0L) {
                throw new IllegalArgumentException("recovery record sequence must be positive: " + sequence);
            }
            storageId = Objects.requireNonNull(storageId, "storageId");
            if (storageId.isBlank()) {
                throw new IllegalArgumentException("recovery record storage id must not be blank");
            }
            subsystem = Objects.requireNonNull(subsystem, "subsystem");
            action = Objects.requireNonNull(action, "action");
            if (action.isBlank()) {
                throw new IllegalArgumentException("recovery record action must not be blank");
            }
            if (transactionId < 0L || commitSequence < 0L || primaryValue < 0L || secondaryValue < 0L) {
                throw new IllegalArgumentException("recovery record values must not be negative");
            }
        }
    }

    public record ReplayPlan(Path path, List<RecoveryRecord> records) {
        public ReplayPlan {
            records = List.copyOf(Objects.requireNonNull(records, "records"));
        }

        public static ReplayPlan empty(Path path) {
            return new ReplayPlan(path, List.of());
        }

        public long count(Subsystem subsystem) {
            Objects.requireNonNull(subsystem, "subsystem");
            return records.stream().filter(record -> record.subsystem() == subsystem).count();
        }

        public boolean has(Subsystem subsystem) {
            return count(subsystem) > 0L;
        }

        public void requireCrossSubsystemCompleteness(Set<Subsystem> requiredSubsystems) {
            Objects.requireNonNull(requiredSubsystems, "requiredSubsystems");
            if (records.isEmpty()) {
                return;
            }
            for (Subsystem subsystem : requiredSubsystems) {
                Objects.requireNonNull(subsystem, "requiredSubsystems entry");
                if (!has(subsystem)) {
                    throw new IllegalStateException("MVCC recovery replay is missing required subsystem redo: "
                            + subsystem);
                }
            }
            for (RecoveryRecord rowRecord : records) {
                if (rowRecord.subsystem() != Subsystem.ROW_PAGE || rowRecord.transactionId() == 0L) {
                    continue;
                }
                boolean matchingOutcome = records.stream().anyMatch(candidate ->
                        candidate.subsystem() == Subsystem.TRANSACTION_OUTCOME
                                && candidate.transactionId() == rowRecord.transactionId()
                                && candidate.commitSequence() == rowRecord.commitSequence()
                                && candidate.sequence() >= rowRecord.sequence());
                if (!matchingOutcome) {
                    throw new IllegalStateException("MVCC recovery replay row-page redo has no matching "
                            + "transaction-outcome redo for tx " + rowRecord.transactionId()
                            + " at commit " + rowRecord.commitSequence());
                }
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
