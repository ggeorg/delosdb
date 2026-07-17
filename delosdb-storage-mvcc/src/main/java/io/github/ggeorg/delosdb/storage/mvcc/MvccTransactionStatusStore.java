package io.github.ggeorg.delosdb.storage.mvcc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.durable.AbstractSidecarStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccAppendOnlyTextLog;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccDurableFiles;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

/**
 * Forced append-only transaction-status store for the MVCC database.
 *
 * <p>This is not WAL. It is the first durable MVCC transaction-status authority
 * used by the live Derby commit/rollback route. Complete final records are
 * authoritative; a torn final line is ignored. Transactions whose latest
 * complete record is ACTIVE are exposed as RECOVERY_PENDING on reopen so their
 * versions are never visible by default.</p>
 */
public class MvccTransactionStatusStore extends AbstractSidecarStore {
    private static final String LOG_VERSION = "1";
    private static final String LOG_NAME = "MVCC transaction status store";
    private static final String RECORD_ACTIVE = "ACTIVE";
    private static final String RECORD_COMMIT = "COMMITTED";
    private static final String RECORD_ABORT = "ABORTED";

    private final MvccAppendOnlyTextLog journal;

    private static final MvccTransactionStatusStore DISABLED = new MvccTransactionStatusStore(Path.of("disabled")) {
        @Override
        public Optional<Path> path() {
            return Optional.empty();
        }

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void recordActive(MvccTransactionId transactionId) {
            requireRealTransactionId(transactionId);
        }

        @Override
        public void recordCommitted(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
            validateCommitted(transactionId, commitSequence);
        }

        @Override
        public void recordCommittedBatch(List<CommittedStatus> commits) {
            validateCommittedBatch(commits);
        }

        @Override
        public void recordAborted(MvccTransactionId transactionId) {
            requireRealTransactionId(transactionId);
        }

        @Override
        public Map<MvccTransactionId, MvccTransactionStatusRecord> recoverStatuses() {
            return Map.of();
        }

        @Override
        public long sizeBytes() {
            return 0L;
        }

        @Override
        public CompactionResult compactRetaining(Set<MvccTransactionId> requiredTransactionIds) {
            Objects.requireNonNull(requiredTransactionIds, "requiredTransactionIds");
            return new CompactionResult(0, 0, 0L, 0L);
        }
    };


    private MvccTransactionStatusStore(Path path) {
        super(path);
        journal = MvccAppendOnlyTextLog.open(path, LOG_NAME);
    }

    public static MvccTransactionStatusStore disabled() {
        return DISABLED;
    }

    public static MvccTransactionStatusStore open(Path path) {
        MvccTransactionStatusStore store = new MvccTransactionStatusStore(path);
        store.ensureParentDirectory("MVCC transaction status");
        return store;
    }

    public Optional<Path> path() {
        return Optional.of(sidecarPath());
    }

    public boolean isEnabled() {
        return true;
    }

    public void recordActive(MvccTransactionId transactionId) {
        requireRealTransactionId(transactionId);
        appendLine(RECORD_ACTIVE, Long.toString(transactionId.value()));
    }

    public void recordCommitted(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
        recordCommittedBatch(List.of(new CommittedStatus(transactionId, commitSequence)));
    }

    /** Publishes one or more ordered COMMITTED records through one forced append. */
    public synchronized void recordCommittedBatch(List<CommittedStatus> commits) {
        commits = validateCommittedBatch(commits);
        if (!isEnabled() || commits.isEmpty()) {
            return;
        }
        StringBuilder batch = new StringBuilder();
        for (CommittedStatus commit : commits) {
            appendLineTo(batch, RECORD_COMMIT,
                    Long.toString(commit.transactionId().value()),
                    Long.toString(commit.commitSequence().value()));
        }
        journal.append(batch.toString(), "MVCC transaction status commit group");
    }

    public void recordAborted(MvccTransactionId transactionId) {
        requireRealTransactionId(transactionId);
        appendLine(RECORD_ABORT, Long.toString(transactionId.value()));
    }

    public synchronized Map<MvccTransactionId, MvccTransactionStatusRecord> recoverStatuses() {
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = recoverDurableStatuses();
        if (statuses.isEmpty()) {
            return Map.of();
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> recovered = new LinkedHashMap<>();
        for (Map.Entry<MvccTransactionId, MvccTransactionStatusRecord> entry : statuses.entrySet()) {
            MvccTransactionStatusRecord record = entry.getValue();
            if (record.status() == MvccTransactionStatus.ACTIVE) {
                recovered.put(entry.getKey(), MvccTransactionStatusRecord.recoveryPending(entry.getKey()));
            } else {
                recovered.put(entry.getKey(), record);
            }
        }
        return Map.copyOf(recovered);
    }

    public long sizeBytes() {
        if (!isEnabled() || !journal.exists()) {
            return 0L;
        }
        try {
            return Files.size(journal.path());
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not inspect MVCC transaction status store: " + journal.path(),
                    failure);
        }
    }

    /** Deletes this journal and any interrupted atomic-rewrite sibling. */
    public synchronized void deleteIfExists() {
        if (!isEnabled()) {
            return;
        }
        try {
            MvccDurableFiles.deleteWithTemporarySibling(journal.path(), ".tmp");
        } catch (IOException failure) {
            throw new UncheckedIOException(
                    "Could not delete MVCC transaction status store: " + journal.path(),
                    failure);
        }
    }

    /**
     * Rewrites the database transaction-status journal to the minimum recovery
     * authority still required by unresolved prepared mutations.
     *
     * <p>The exact status with the greatest transaction id and the committed
     * status with the greatest commit sequence are retained as allocation
     * watermarks even when no pending mutation references them.</p>
     */
    public synchronized CompactionResult compactRetaining(
            Set<MvccTransactionId> requiredTransactionIds) {
        requiredTransactionIds = Set.copyOf(
                Objects.requireNonNull(requiredTransactionIds, "requiredTransactionIds"));
        if (!isEnabled()) {
            return new CompactionResult(0, 0, 0L, 0L);
        }

        List<MvccDurableLineRecords.LineRecord> records = journal.completeRecords();
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses =
                recoverDurableStatuses(records);
        long beforeBytes = sizeBytes();
        if (statuses.isEmpty()) {
            deleteIfExists();
            return new CompactionResult(records.size(), 0, beforeBytes, 0L);
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> retained =
                new LinkedHashMap<>();
        for (MvccTransactionId transactionId : requiredTransactionIds) {
            MvccTransactionStatusRecord status = statuses.get(transactionId);
            if (status != null) {
                retained.put(transactionId, status);
            }
        }

        statuses.values().stream()
                .max(Comparator.comparingLong(status -> status.transactionId().value()))
                .ifPresent(status -> retained.put(status.transactionId(), status));
        statuses.values().stream()
                .filter(status -> status.status() == MvccTransactionStatus.COMMITTED)
                .max(Comparator.comparingLong(status -> status.commitSequence().value()))
                .ifPresent(status -> retained.put(status.transactionId(), status));

        StringBuilder compacted = new StringBuilder();
        retained.values().stream()
                .sorted(Comparator.comparingLong(status -> status.transactionId().value()))
                .forEach(status -> appendStatusLine(compacted, status));
        String compactedContent = compacted.toString();
        rewriteUtf8AtomicallyForced(
                compactedContent,
                "MVCC transaction status compaction");
        long afterBytes = compactedContent.getBytes(StandardCharsets.UTF_8).length;
        return new CompactionResult(
                records.size(), retained.size(), beforeBytes, afterBytes);
    }

    private Map<MvccTransactionId, MvccTransactionStatusRecord> recoverDurableStatuses() {
        return recoverDurableStatuses(journal.completeRecords());
    }

    private Map<MvccTransactionId, MvccTransactionStatusRecord> recoverDurableStatuses(
            List<MvccDurableLineRecords.LineRecord> records) {
        if (records.isEmpty()) {
            return Map.of();
        }
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses =
                new LinkedHashMap<>();
        for (MvccDurableLineRecords.LineRecord record : records) {
            parseLine(record.line(), record.lineIndex(), statuses);
        }
        return Map.copyOf(statuses);
    }

    private static void appendStatusLine(
            StringBuilder target,
            MvccTransactionStatusRecord status) {
        switch (status.status()) {
        case ACTIVE -> appendLineTo(
                target,
                RECORD_ACTIVE,
                Long.toString(status.transactionId().value()));
        case COMMITTED -> appendLineTo(
                target,
                RECORD_COMMIT,
                Long.toString(status.transactionId().value()),
                Long.toString(status.commitSequence().value()));
        case ABORTED -> appendLineTo(
                target,
                RECORD_ABORT,
                Long.toString(status.transactionId().value()));
        case RECOVERY_PENDING -> throw new IllegalArgumentException(
                "RECOVERY_PENDING is a derived reopen state and cannot be compacted directly");
        }
    }

    private void parseLine(
            String line,
            int lineIndex,
            Map<MvccTransactionId, MvccTransactionStatusRecord> statuses) {
        String[] parts = MvccDurableLineRecords.tabFields(line);
        require(parts.length >= 2, lineIndex, "record has too few fields");
        require(LOG_VERSION.equals(parts[0]), lineIndex, "unsupported transaction status store version: " + parts[0]);
        switch (parts[1]) {
        case RECORD_ACTIVE -> {
            require(parts.length == 3, lineIndex, "ACTIVE requires transaction id");
            MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[2], lineIndex, "transaction id"));
            requireRealTransactionId(transactionId, lineIndex);
            recordStatus(statuses, MvccTransactionStatusRecord.active(transactionId), lineIndex);
        }
        case RECORD_COMMIT -> {
            require(parts.length == 4, lineIndex, "COMMITTED requires transaction id and commit sequence");
            MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[2], lineIndex, "transaction id"));
            MvccCommitSequence commitSequence = new MvccCommitSequence(parseLong(parts[3], lineIndex, "commit sequence"));
            requireRealTransactionId(transactionId, lineIndex);
            require(!commitSequence.equals(MvccCommitSequence.NONE), lineIndex, "commit sequence must be present");
            recordStatus(statuses, MvccTransactionStatusRecord.committed(transactionId, commitSequence), lineIndex);
        }
        case RECORD_ABORT -> {
            require(parts.length == 3, lineIndex, "ABORTED requires transaction id");
            MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[2], lineIndex, "transaction id"));
            requireRealTransactionId(transactionId, lineIndex);
            recordStatus(statuses, MvccTransactionStatusRecord.aborted(transactionId), lineIndex);
        }
        default -> throw corrupt(lineIndex, "unknown transaction status record type: " + parts[1]);
        }
    }

    private static void recordStatus(
            Map<MvccTransactionId, MvccTransactionStatusRecord> statuses,
            MvccTransactionStatusRecord record,
            int lineIndex) {
        MvccTransactionStatusRecord existing = statuses.get(record.transactionId());
        if (existing == null) {
            statuses.put(record.transactionId(), record);
            return;
        }
        if (existing.status() == MvccTransactionStatus.ACTIVE
                && (record.status() == MvccTransactionStatus.COMMITTED
                || record.status() == MvccTransactionStatus.ABORTED)) {
            statuses.put(record.transactionId(), record);
            return;
        }
        if (existing.equals(record)) {
            return;
        }
        throw corrupt(lineIndex, "conflicting durable status for " + record.transactionId()
                + ": existing=" + existing + ", new=" + record);
    }

    private synchronized void appendLine(String type, String... fields) {
        StringBuilder line = new StringBuilder();
        appendLineTo(line, type, fields);
        journal.append(line.toString(), "MVCC transaction status record");
    }

    private static void appendLineTo(StringBuilder target, String type, String... fields) {
        target.append(LOG_VERSION).append('\t').append(type);
        for (String field : fields) {
            target.append('\t').append(field);
        }
        target.append('\n');
    }

    private static void validateCommitted(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
        requireRealTransactionId(transactionId);
        Objects.requireNonNull(commitSequence, "commitSequence");
        if (commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException("commit sequence must be present for committed status");
        }
    }

    private static List<CommittedStatus> validateCommittedBatch(List<CommittedStatus> commits) {
        commits = List.copyOf(Objects.requireNonNull(commits, "commits"));
        MvccCommitSequence previous = MvccCommitSequence.NONE;
        for (CommittedStatus commit : commits) {
            commit = Objects.requireNonNull(commit, "commits entry");
            validateCommitted(commit.transactionId(), commit.commitSequence());
            if (!previous.equals(MvccCommitSequence.NONE)
                    && commit.commitSequence().compareTo(previous) <= 0) {
                throw new IllegalArgumentException("commit sequences must increase in a status batch");
            }
            previous = commit.commitSequence();
        }
        return commits;
    }

    public record CommittedStatus(
            MvccTransactionId transactionId,
            MvccCommitSequence commitSequence) {
        public CommittedStatus {
            validateCommitted(transactionId, commitSequence);
        }
    }

    public record CompactionResult(
            int recordsBefore,
            int recordsAfter,
            long bytesBefore,
            long bytesAfter) {
        public CompactionResult {
            if (recordsBefore < 0 || recordsAfter < 0
                    || bytesBefore < 0L || bytesAfter < 0L) {
                throw new IllegalArgumentException(
                        "transaction status compaction counts must not be negative");
            }
        }
    }

    private static long parseLong(String value, int lineIndex, String fieldName) {
        return MvccDurableLineRecords.parseLong(value, lineIndex, fieldName, LOG_NAME);
    }

    private static void requireRealTransactionId(MvccTransactionId transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        if (transactionId.isNone()) {
            throw new IllegalArgumentException("transaction id must be present");
        }
    }

    private static void requireRealTransactionId(MvccTransactionId transactionId, int lineIndex) {
        require(!transactionId.isNone(), lineIndex, "transaction id must be present");
    }

    private static void require(boolean condition, int lineIndex, String message) {
        MvccDurableLineRecords.require(condition, LOG_NAME, lineIndex, message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return MvccDurableLineRecords.corrupt(LOG_NAME, lineIndex, message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message, Throwable cause) {
        return MvccDurableLineRecords.corrupt(LOG_NAME, lineIndex, message, cause);
    }
}
