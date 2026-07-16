package io.github.ggeorg.delosdb.storage.mvcc;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.durable.AbstractSidecarStore;
import io.github.ggeorg.delosdb.storage.mvcc.durable.MvccAppendOnlyTextLog;
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
        if (!journal.exists()) {
            return Map.of();
        }

        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = new LinkedHashMap<>();
        for (MvccDurableLineRecords.LineRecord record : journal.completeRecords()) {
            parseLine(record.line(), record.lineIndex(), statuses);
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
