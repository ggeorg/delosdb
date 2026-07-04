package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;

/**
 * Authoritative transaction-outcome log for strict durable MVCC recovery.
 *
 * <p>The older {@link MvccPageMutationLog} keeps its legacy tolerant recovery
 * contract until the recovery path is migrated deliberately. This log is the
 * new strict authority: if a mutation is replayed through an outcome-log-aware
 * path, its creating transaction must have a committed or aborted outcome here.
 * Unknown outcomes fail loudly instead of being silently exposed.</p>
 */
public final class MvccTransactionOutcomeLog extends AbstractSidecarStore {
    private static final String LOG_VERSION = "1";
    private static final String LOG_NAME = "MVCC transaction outcome log";
    private static final String RECORD_COMMIT = "COMMIT";
    private static final String RECORD_ABORT = "ABORT";
    private static final String RECORD_FSYNC = "FSYNC";


    private MvccTransactionOutcomeLog(Path path) {
        super(path);
    }

    public static MvccTransactionOutcomeLog open(Path path) {
        MvccTransactionOutcomeLog log = new MvccTransactionOutcomeLog(path);
        log.ensureParentDirectory("MVCC transaction outcome log");
        return log;
    }

    public Path path() {
        return sidecarPath();
    }

    public void appendCommit(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
        requireRealTransactionId(transactionId);
        Objects.requireNonNull(commitSequence, "commitSequence");
        if (commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException("commit sequence must be present for committed outcome");
        }
        appendLine(RECORD_COMMIT, Long.toString(transactionId.value()), Long.toString(commitSequence.value()));
    }

    public void appendCommit(long transactionId, long commitSequence) {
        appendCommit(new MvccTransactionId(transactionId), new MvccCommitSequence(commitSequence));
    }

    public void appendAbort(MvccTransactionId transactionId) {
        requireRealTransactionId(transactionId);
        appendLine(RECORD_ABORT, Long.toString(transactionId.value()));
    }

    public void appendAbort(long transactionId) {
        appendAbort(new MvccTransactionId(transactionId));
    }

    /** Records a readable durable-boundary marker. Recovery accepts but ignores it. */
    public void appendFsyncBoundary(long boundaryId) {
        if (boundaryId <= 0L) {
            throw new IllegalArgumentException("fsync boundary id must be positive: " + boundaryId);
        }
        appendLine(RECORD_FSYNC, Long.toString(boundaryId));
    }

    /**
     * Rewrites the outcome log to match a compact committed checkpoint image.
     *
     * <p>The page mutation checkpoint uses the original creating transaction id
     * in each VERSION record. Strict recovery therefore needs the outcome log to
     * retain one committed outcome for every creator transaction that still has a
     * retained version in the checkpoint image.</p>
     */
    public synchronized void rewriteCheckpoint(List<MvccVersionRecord> committedImage) {
        Objects.requireNonNull(committedImage, "committedImage");
        Map<MvccTransactionId, MvccCommitSequence> commitSequences = new LinkedHashMap<>();
        for (MvccVersionRecord record : committedImage) {
            Objects.requireNonNull(record, "committedImage record");
            MvccTupleHeader header = record.header();
            requireRealTransactionId(header.createdByTx());
            if (header.commitSequence().equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("checkpoint image contains uncommitted MVCC version: "
                        + header.versionId());
            }
            MvccCommitSequence existing = commitSequences.putIfAbsent(
                    header.createdByTx(),
                    header.commitSequence());
            if (existing != null && !existing.equals(header.commitSequence())) {
                throw new IllegalArgumentException("checkpoint image contains conflicting commit sequences for "
                        + header.createdByTx() + ": " + existing + " and " + header.commitSequence());
            }
        }

        StringBuilder content = new StringBuilder();
        for (Map.Entry<MvccTransactionId, MvccCommitSequence> entry : commitSequences.entrySet()) {
            appendLine(content, RECORD_COMMIT,
                    Long.toString(entry.getKey().value()),
                    Long.toString(entry.getValue().value()));
        }
        writeAtomically(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    public synchronized Map<MvccTransactionId, Outcome> recoverOutcomes() {
        if (!sidecarExists()) {
            return Map.of();
        }
        String content = readUtf8IfExists(LOG_NAME);
        if (content.isEmpty()) {
            return Map.of();
        }

        Map<MvccTransactionId, Outcome> outcomes = new LinkedHashMap<>();
        for (MvccDurableLineRecords.LineRecord record : MvccDurableLineRecords.completeRecords(content)) {
            parseLine(record.line(), record.lineIndex(), outcomes);
        }
        return Map.copyOf(outcomes);
    }

    public Optional<Outcome> outcomeOf(MvccTransactionId transactionId) {
        Objects.requireNonNull(transactionId, "transactionId");
        return Optional.ofNullable(recoverOutcomes().get(transactionId));
    }

    public Outcome requireOutcome(MvccTransactionId transactionId) {
        return outcomeOf(transactionId).orElseThrow(() -> new MvccUnresolvedTransactionOutcomeException(transactionId));
    }

    /**
     * Applies the strict outcome-log rule to one recovered version record.
     *
     * <p>Committed creators produce the same record stamped with the durable
     * commit sequence; aborted creators produce an empty result; unknown creators
     * fail loudly. A50 will wire this policy into the page recovery runner.</p>
     */
    public Optional<MvccVersionRecord> committedRecordOrEmpty(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        Outcome outcome = requireOutcome(record.header().createdByTx());
        if (outcome.status() == MvccTransactionStatus.ABORTED) {
            return Optional.empty();
        }
        return Optional.of(recordCommittedAt(record, outcome.commitSequence()));
    }

    private void parseLine(String line, int lineIndex, Map<MvccTransactionId, Outcome> outcomes) {
        String[] parts = MvccDurableLineRecords.tabFields(line);
        require(parts.length >= 2, lineIndex, "record has too few fields");
        require(LOG_VERSION.equals(parts[0]), lineIndex, "unsupported transaction outcome log version: " + parts[0]);
        switch (parts[1]) {
        case RECORD_COMMIT -> {
            require(parts.length == 4, lineIndex, "COMMIT requires transaction id and commit sequence");
            MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[2], lineIndex, "transaction id"));
            MvccCommitSequence commitSequence = new MvccCommitSequence(parseLong(parts[3], lineIndex, "commit sequence"));
            requireRealTransactionId(transactionId, lineIndex);
            require(!commitSequence.equals(MvccCommitSequence.NONE), lineIndex, "commit sequence must be present");
            recordOutcome(transactionId, Outcome.committed(commitSequence), outcomes, lineIndex);
        }
        case RECORD_ABORT -> {
            require(parts.length == 3, lineIndex, "ABORT requires transaction id");
            MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[2], lineIndex, "transaction id"));
            requireRealTransactionId(transactionId, lineIndex);
            recordOutcome(transactionId, Outcome.aborted(), outcomes, lineIndex);
        }
        case RECORD_FSYNC -> {
            require(parts.length == 3, lineIndex, "FSYNC requires boundary id");
            long boundaryId = parseLong(parts[2], lineIndex, "fsync boundary id");
            require(boundaryId > 0L, lineIndex, "fsync boundary id must be positive");
        }
        default -> throw corrupt(lineIndex, "unknown transaction outcome log record type: " + parts[1]);
        }
    }

    private static void recordOutcome(
            MvccTransactionId transactionId,
            Outcome outcome,
            Map<MvccTransactionId, Outcome> outcomes,
            int lineIndex) {
        Outcome existing = outcomes.get(transactionId);
        if (existing == null) {
            outcomes.put(transactionId, outcome);
            return;
        }
        if (!existing.equals(outcome)) {
            throw corrupt(lineIndex, "conflicting durable outcome for " + transactionId
                    + ": existing=" + existing + ", new=" + outcome);
        }
    }

    private synchronized void appendLine(String type, String... fields) {
        StringBuilder line = new StringBuilder();
        appendLine(line, type, fields);
        appendUtf8Forced(line.toString(), "MVCC transaction outcome record");
    }

    private static void appendLine(StringBuilder content, String type, String... fields) {
        content.append(LOG_VERSION).append('\t').append(type);
        for (String field : fields) {
            content.append('\t').append(field);
        }
        content.append('\n');
    }

    private synchronized void writeAtomically(byte[] bytes) {
        rewriteBytesAtomicallyForced(bytes, "MVCC transaction outcome checkpoint log");
    }

    private static MvccVersionRecord recordCommittedAt(MvccVersionRecord record, MvccCommitSequence commitSequence) {
        if (!record.header().commitSequence().equals(MvccCommitSequence.NONE)
                && !record.header().commitSequence().equals(commitSequence)) {
            throw new IllegalStateException("transaction outcome log commit sequence " + commitSequence
                    + " conflicts with record commit sequence " + record.header().commitSequence());
        }
        MvccTupleHeader header = record.header();
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        header.rowId(),
                        header.versionId(),
                        header.previousVersionId(),
                        header.createdByTx(),
                        header.deletedByTx(),
                        commitSequence,
                        header.flags()),
                record.payload());
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

    public record Outcome(MvccTransactionStatus status, MvccCommitSequence commitSequence) {
        public Outcome {
            status = Objects.requireNonNull(status, "status");
            commitSequence = Objects.requireNonNull(commitSequence, "commitSequence");
            if (status == MvccTransactionStatus.COMMITTED && commitSequence.equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("committed outcome must carry a commit sequence");
            }
            if (status == MvccTransactionStatus.ABORTED && !commitSequence.equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("aborted outcome must not carry a commit sequence");
            }
            if (status != MvccTransactionStatus.COMMITTED && status != MvccTransactionStatus.ABORTED) {
                throw new IllegalArgumentException(status + " is not a durable terminal transaction outcome");
            }
        }

        public static Outcome committed(MvccCommitSequence commitSequence) {
            return new Outcome(MvccTransactionStatus.COMMITTED, commitSequence);
        }

        public static Outcome aborted() {
            return new Outcome(MvccTransactionStatus.ABORTED, MvccCommitSequence.NONE);
        }
    }
}
