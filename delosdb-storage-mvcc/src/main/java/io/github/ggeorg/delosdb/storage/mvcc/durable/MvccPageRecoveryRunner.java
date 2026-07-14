package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/** Applies committed page-mutation log records into a page-backed MVCC table store. */
public final class MvccPageRecoveryRunner {
    private final MvccPageMutationLog log;
    private final PageBackedMvccTableStore store;

    public MvccPageRecoveryRunner(MvccPageMutationLog log, PageBackedMvccTableStore store) {
        this.log = Objects.requireNonNull(log, "log");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Opens the durable log and page store the same way a boot path will, then
     * applies committed mutations into the store.
     */
    public static RecoveryResult recover(Path logPath, Path tablePath) throws IOException {
        Objects.requireNonNull(logPath, "logPath");
        Objects.requireNonNull(tablePath, "tablePath");
        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tablePath)) {
            return new MvccPageRecoveryRunner(MvccPageMutationLog.open(logPath), store).recover();
        }
    }

    /**
     * Opens the durable logs and page store, then applies mutations through the
     * strict transaction-outcome path introduced after A49.
     */
    public static RecoveryResult recoverStrict(Path logPath, Path outcomeLogPath, Path tablePath) throws IOException {
        Objects.requireNonNull(logPath, "logPath");
        Objects.requireNonNull(outcomeLogPath, "outcomeLogPath");
        Objects.requireNonNull(tablePath, "tablePath");
        try (PageBackedMvccTableStore store = PageBackedMvccTableStore.open(tablePath)) {
            return new MvccPageRecoveryRunner(MvccPageMutationLog.open(logPath), store)
                    .recoverStrict(MvccTransactionOutcomeLog.open(outcomeLogPath));
        }
    }

    public RecoveryResult recover() throws IOException {
        Set<MvccVersionId> existingVersionIds = new HashSet<>();
        for (PageBackedMvccTableStore.StoredVersionRecord stored : store.loadAll()) {
            existingVersionIds.add(stored.record().header().versionId());
        }

        int applied = 0;
        int skipped = 0;
        return applyRecords(log.recoverCommittedRecords(), existingVersionIds, applied, skipped);
    }

    /**
     * Applies raw page mutations only when the separate transaction outcome log
     * proves their creating transaction reached a durable terminal outcome.
     */
    public RecoveryResult recoverStrict(MvccTransactionOutcomeLog outcomeLog) throws IOException {
        Objects.requireNonNull(outcomeLog, "outcomeLog");
        var outcomes = outcomeLog.recoverOutcomes();
        Set<MvccVersionId> existingVersionIds = new HashSet<>();
        for (PageBackedMvccTableStore.StoredVersionRecord stored : store.loadAll()) {
            if (outcomeLog.committedRecordOrEmpty(stored.record(), outcomes).isEmpty()) {
                throw new IllegalStateException("durable page contains a version from an aborted transaction: "
                        + stored.record().header().createdByTx());
            }
            existingVersionIds.add(stored.record().header().versionId());
        }
        return applyRecords(log.recoverRecordsThroughOutcomeLog(outcomeLog), existingVersionIds, 0, 0);
    }

    private RecoveryResult applyRecords(
            Iterable<MvccVersionRecord> records,
            Set<MvccVersionId> existingVersionIds,
            int initialApplied,
            int initialSkipped) throws IOException {
        int applied = initialApplied;
        int skipped = initialSkipped;
        for (MvccVersionRecord record : records) {
            if (existingVersionIds.add(record.header().versionId())) {
                store.append(record);
                applied++;
            } else {
                skipped++;
            }
        }
        return new RecoveryResult(applied, skipped);
    }

    public record RecoveryResult(int appliedRecords, int skippedExistingRecords) {
        public RecoveryResult {
            if (appliedRecords < 0 || skippedExistingRecords < 0) {
                throw new IllegalArgumentException("recovery counts must not be negative");
            }
        }
    }
}
