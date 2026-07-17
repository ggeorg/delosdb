package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

/**
 * Provider-local page-storage-aware recovery log for page-backed MVCC tables.
 *
 * <p>Legacy callers may still append one VERSION and terminal record at a time.
 * The transaction path uses {@link #appendPreparedTransaction(long, long, List)}:
 * all payload records plus a PREPARED marker are forced by one append before the
 * database transaction-status store publishes COMMITTED. The per-table outcome
 * log normally mirrors that decision before page materialization; recovery may
 * use an explicitly correlated COMMITTED status when that local mirror is
 * missing.</p>
 */
public final class MvccPageMutationLog extends AbstractSidecarStore {
    private static final String LOG_VERSION = "1";
    private static final String LOG_NAME = "MVCC page mutation log";
    private static final String RECORD_BEGIN = "BEGIN";
    private static final String RECORD_VERSION = "VERSION";
    private static final String RECORD_PREPARED = "PREPARED";
    private static final String RECORD_COMMIT = "COMMIT";
    private static final String RECORD_ABORT = "ABORT";
    private static final String RECORD_FSYNC = "FSYNC";

    private final MvccAppendOnlyTextLog journal;

    private MvccPageMutationLog(Path path) {
        super(path);
        journal = MvccAppendOnlyTextLog.open(path, LOG_NAME);
    }

    public static MvccPageMutationLog open(Path path) {
        MvccPageMutationLog log = new MvccPageMutationLog(path);
        log.ensureParentDirectory("MVCC page mutation log");
        return log;
    }

    public Path path() {
        return sidecarPath();
    }

    public void appendVersion(long transactionId, MvccVersionRecord record) {
        requireTransactionId(transactionId);
        Objects.requireNonNull(record, "record");
        appendLine(RECORD_VERSION, Long.toString(transactionId), encodeRecord(record));
    }

    public void appendCommit(long transactionId, long commitSequence) {
        requireTransactionId(transactionId);
        requireCommitSequence(commitSequence);
        appendLine(RECORD_COMMIT, Long.toString(transactionId), Long.toString(commitSequence));
    }

    /**
     * Forces one complete transaction payload batch.
     *
     * <p>The PREPARED marker is not the commit authority. It proves that all
     * expected payload records reached the mutation log. The database
     * transaction-status COMMITTED record is published afterwards. The local
     * outcome log is then written before page materialization and remains the
     * ordinary page-recovery authority.</p>
     */
    public synchronized void appendPreparedTransaction(
            long transactionId,
            long commitSequence,
            List<MvccVersionRecord> records) {
        appendPreparedTransaction(transactionId, commitSequence, 0L, records);
    }

    /**
     * Appends a complete page transaction and, when present, its database-level
     * transaction-status correlation. Page and database transaction ids use
     * independent namespaces, so recovery must never infer one from the other.
     */
    public synchronized void appendPreparedTransaction(
            long transactionId,
            long commitSequence,
            long statusTransactionId,
            List<MvccVersionRecord> records) {
        requireTransactionId(transactionId);
        requireCommitSequence(commitSequence);
        if (statusTransactionId < 0L) {
            throw new IllegalArgumentException("status transaction id must not be negative: "
                    + statusTransactionId);
        }
        records = List.copyOf(Objects.requireNonNull(records, "records"));
        if (records.isEmpty()) {
            throw new IllegalArgumentException("prepared MVCC transaction must contain at least one version");
        }
        StringBuilder batch = new StringBuilder();
        appendPreparedBoundary(batch, RECORD_BEGIN, transactionId, commitSequence,
                records.size(), statusTransactionId);
        for (MvccVersionRecord record : records) {
            Objects.requireNonNull(record, "records entry");
            require(record.header().createdByTx().value() == transactionId,
                    "prepared transaction id must match every record creator");
            MvccCommitSequence recordSequence = record.header().commitSequence();
            require(recordSequence.equals(MvccCommitSequence.NONE)
                            || recordSequence.value() == commitSequence,
                    "prepared transaction commit sequence must match every record");
            appendLine(batch, RECORD_VERSION, Long.toString(transactionId), encodeRecord(record));
        }
        appendPreparedBoundary(batch, RECORD_PREPARED, transactionId, commitSequence,
                records.size(), statusTransactionId);
        journal.append(batch.toString(), "MVCC prepared page-mutation transaction");
    }

    public void appendAbort(long transactionId) {
        requireTransactionId(transactionId);
        appendLine(RECORD_ABORT, Long.toString(transactionId));
    }

    public void appendFsyncBoundary(long boundaryId) {
        if (boundaryId <= 0L) {
            throw new IllegalArgumentException("fsync boundary id must be positive: " + boundaryId);
        }
        appendLine(RECORD_FSYNC, Long.toString(boundaryId));
    }

    /** Replaces the log with a compact committed image using the legacy format. */
    public synchronized void rewriteCheckpoint(List<MvccVersionRecord> committedImage) {
        Objects.requireNonNull(committedImage, "committedImage");
        StringBuilder content = new StringBuilder();
        Map<Long, MvccCommitSequence> commitSequencesByTransaction = new LinkedHashMap<>();
        for (MvccVersionRecord record : committedImage) {
            Objects.requireNonNull(record, "committedImage record");
            MvccTupleHeader header = record.header();
            long transactionId = header.createdByTx().value();
            requireTransactionId(transactionId);
            if (header.commitSequence().equals(MvccCommitSequence.NONE)) {
                throw new IllegalArgumentException("checkpoint image contains uncommitted MVCC version: "
                        + header.versionId());
            }
            MvccCommitSequence existing = commitSequencesByTransaction.putIfAbsent(
                    transactionId,
                    header.commitSequence());
            if (existing != null && !existing.equals(header.commitSequence())) {
                throw new IllegalArgumentException("checkpoint image contains conflicting commit sequences for tx "
                        + transactionId + ": " + existing + " and " + header.commitSequence());
            }
            appendLine(content, RECORD_VERSION, Long.toString(transactionId), encodeRecord(record));
        }
        for (Map.Entry<Long, MvccCommitSequence> entry : commitSequencesByTransaction.entrySet()) {
            appendLine(content, RECORD_COMMIT,
                    Long.toString(entry.getKey()),
                    Long.toString(entry.getValue().value()));
        }
        writeAtomically(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns whether the log contains the new complete-payload transaction format. */
    public synchronized boolean hasPreparedTransactions() {
        if (!journal.exists()) {
            return false;
        }
        for (MvccDurableLineRecords.LineRecord record : journal.completeRecords()) {
            String[] parts = MvccDurableLineRecords.tabFields(record.line());
            if (parts.length >= 2 && RECORD_BEGIN.equals(parts[1])) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<MvccVersionRecord> recoverCommittedRecords() {
        if (!journal.exists()) {
            return List.of();
        }

        Map<Long, List<MvccVersionRecord>> versionsByTransaction = new LinkedHashMap<>();
        Map<Long, TerminalState> terminalStates = new LinkedHashMap<>();
        List<Long> terminalOrder = new ArrayList<>();

        for (MvccDurableLineRecords.LineRecord record : journal.completeRecords()) {
            parseLegacyLine(record.line(), record.lineIndex(), versionsByTransaction, terminalStates, terminalOrder);
        }

        List<MvccVersionRecord> committed = new ArrayList<>();
        for (long transactionId : terminalOrder) {
            TerminalState terminal = terminalStates.get(transactionId);
            if (terminal == null || !terminal.committed()) {
                continue;
            }
            for (MvccVersionRecord record : versionsByTransaction.getOrDefault(transactionId, List.of())) {
                committed.add(recordCommittedAt(record, terminal.commitSequence()));
            }
        }
        return List.copyOf(committed);
    }

    /**
     * Replays version payloads through the local transaction outcome and, for
     * explicitly correlated prepared batches only, the database transaction
     * status authority.
     *
     * <p>Legacy VERSION-only records keep the old strict rule: an unknown local
     * outcome is an error. New BEGIN/PREPARED batches are different: a complete
     * prepared batch without either a local outcome or a correlated terminal
     * database status is pre-commit and is ignored. A committed decision requires
     * a complete matching batch and exact record count.</p>
     */
    public synchronized List<MvccVersionRecord> recoverRecordsThroughOutcomeLog(
            MvccTransactionOutcomeLog outcomeLog) {
        return recoverRecordsThroughOutcomeLog(outcomeLog, Map.of());
    }

    public synchronized List<MvccVersionRecord> recoverRecordsThroughOutcomeLog(
            MvccTransactionOutcomeLog outcomeLog,
            Map<MvccTransactionId, MvccTransactionStatusRecord> statusFallback) {
        Objects.requireNonNull(outcomeLog, "outcomeLog");
        if (!journal.exists()) {
            return List.of();
        }

        Map<MvccTransactionId, MvccTransactionOutcomeLog.Outcome> outcomes =
                outcomeLog.recoverOutcomes();
        Map<Long, StrictTransaction> transactions = new LinkedHashMap<>();
        for (MvccDurableLineRecords.LineRecord lineRecord : journal.completeRecords()) {
            parseStrictLine(lineRecord.line(), lineRecord.lineIndex(), transactions);
        }

        List<MvccVersionRecord> recovered = new ArrayList<>();
        for (StrictTransaction transaction : transactions.values()) {
            if (transaction.batched()) {
                recoverPreparedTransaction(transaction, outcomeLog, outcomes, statusFallback, recovered);
            } else {
                recoverLegacyTransaction(transaction, outcomeLog, outcomes, recovered);
            }
        }
        return List.copyOf(recovered);
    }

    private static void recoverPreparedTransaction(
            StrictTransaction transaction,
            MvccTransactionOutcomeLog outcomeLog,
            Map<MvccTransactionId, MvccTransactionOutcomeLog.Outcome> outcomes,
            Map<MvccTransactionId, MvccTransactionStatusRecord> statusFallback,
            List<MvccVersionRecord> recovered) {
        Optional<MvccTransactionOutcomeLog.Outcome> outcome =
                Optional.ofNullable(outcomes.get(new MvccTransactionId(transaction.transactionId())));
        if (outcome.isEmpty() && transaction.statusTransactionId() > 0L) {
            MvccTransactionStatusRecord status = statusFallback.get(
                    new MvccTransactionId(transaction.statusTransactionId()));
            if (status != null && status.status() == MvccTransactionStatus.COMMITTED) {
                require(status.commitSequence().value() == transaction.commitSequence(),
                        transaction.lastLineIndex(),
                        "database transaction commit sequence does not match prepared mutation for transaction "
                                + transaction.transactionId());
                outcomeLog.appendCommit(transaction.transactionId(), status.commitSequence().value());
                outcome = Optional.of(MvccTransactionOutcomeLog.Outcome.committed(status.commitSequence()));
            } else if (status != null && status.status() == MvccTransactionStatus.ABORTED) {
                outcomeLog.appendAbort(transaction.transactionId());
                outcome = Optional.of(MvccTransactionOutcomeLog.Outcome.aborted());
            }
        }
        if (!transaction.prepared()) {
            if (outcome.isPresent() && outcome.get().status() == MvccTransactionStatus.COMMITTED) {
                throw corrupt(transaction.lastLineIndex(),
                        "committed outcome has no complete prepared mutation batch for transaction "
                                + transaction.transactionId());
            }
            return;
        }
        require(transaction.expectedRecordCount() == transaction.records().size(),
                transaction.lastLineIndex(),
                "prepared mutation count mismatch for transaction " + transaction.transactionId()
                        + ": expected=" + transaction.expectedRecordCount()
                        + ", actual=" + transaction.records().size());
        if (outcome.isEmpty()) {
            return;
        }
        MvccTransactionOutcomeLog.Outcome terminal = outcome.get();
        if (terminal.status() == MvccTransactionStatus.ABORTED) {
            return;
        }
        require(terminal.commitSequence().value() == transaction.commitSequence(),
                transaction.lastLineIndex(),
                "prepared mutation commit sequence does not match outcome for transaction "
                        + transaction.transactionId());
        for (MvccVersionRecord record : transaction.records()) {
            recovered.add(recordCommittedAt(record, terminal.commitSequence()));
        }
    }

    private static void recoverLegacyTransaction(
            StrictTransaction transaction,
            MvccTransactionOutcomeLog outcomeLog,
            Map<MvccTransactionId, MvccTransactionOutcomeLog.Outcome> outcomes,
            List<MvccVersionRecord> recovered) {
        for (MvccVersionRecord record : transaction.records()) {
            outcomeLog.committedRecordOrEmpty(record, outcomes).ifPresent(recovered::add);
        }
    }

    private void parseStrictLine(
            String line,
            int lineIndex,
            Map<Long, StrictTransaction> transactions) {
        String[] parts = MvccDurableLineRecords.tabFields(line);
        require(parts.length >= 2, lineIndex, "record has too few fields");
        require(LOG_VERSION.equals(parts[0]), lineIndex, "unsupported page mutation log version: " + parts[0]);
        switch (parts[1]) {
        case RECORD_BEGIN -> {
            require(parts.length == 5 || parts.length == 6, lineIndex,
                    "BEGIN requires page transaction id, commit sequence, record count,"
                            + " and optional status transaction id");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            long commitSequence = parseLong(parts[3], lineIndex, "commit sequence");
            int expectedCount = parseCount(parts[4], lineIndex);
            long statusTransactionId = parts.length == 6
                    ? parseLong(parts[5], lineIndex, "status transaction id")
                    : 0L;
            require(statusTransactionId >= 0L, lineIndex,
                    "status transaction id must not be negative");
            StrictTransaction transaction = transactions.computeIfAbsent(
                    transactionId, StrictTransaction::new);
            transaction.begin(commitSequence, expectedCount, statusTransactionId, lineIndex);
        }
        case RECORD_VERSION -> {
            require(parts.length == 4, lineIndex, "VERSION requires transaction id and record bytes");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            requireTransactionId(transactionId);
            MvccVersionRecord record = decodeRecord(parts[3], lineIndex);
            require(record.header().createdByTx().value() == transactionId, lineIndex,
                    "VERSION transaction id must match record creator transaction id");
            transactions.computeIfAbsent(transactionId, StrictTransaction::new)
                    .addRecord(record, lineIndex);
        }
        case RECORD_PREPARED -> {
            require(parts.length == 5 || parts.length == 6, lineIndex,
                    "PREPARED requires page transaction id, commit sequence, record count,"
                            + " and optional status transaction id");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            long commitSequence = parseLong(parts[3], lineIndex, "commit sequence");
            int expectedCount = parseCount(parts[4], lineIndex);
            long statusTransactionId = parts.length == 6
                    ? parseLong(parts[5], lineIndex, "status transaction id")
                    : 0L;
            require(statusTransactionId >= 0L, lineIndex,
                    "status transaction id must not be negative");
            transactions.computeIfAbsent(transactionId, StrictTransaction::new)
                    .prepared(commitSequence, expectedCount, statusTransactionId, lineIndex);
        }
        case RECORD_COMMIT, RECORD_ABORT, RECORD_FSYNC -> {
            // Legacy terminal and explicit force markers are not the strict
            // transaction fence. The separate outcome log remains authoritative.
        }
        default -> throw corrupt(lineIndex, "unknown page mutation log record type: " + parts[1]);
        }
    }

    private void parseLegacyLine(
            String line,
            int lineIndex,
            Map<Long, List<MvccVersionRecord>> versionsByTransaction,
            Map<Long, TerminalState> terminalStates,
            List<Long> terminalOrder) {
        String[] parts = MvccDurableLineRecords.tabFields(line);
        require(parts.length >= 2, lineIndex, "record has too few fields");
        require(LOG_VERSION.equals(parts[0]), lineIndex, "unsupported page mutation log version: " + parts[0]);
        switch (parts[1]) {
        case RECORD_VERSION -> {
            require(parts.length == 4, lineIndex, "VERSION requires transaction id and record bytes");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            versionsByTransaction.computeIfAbsent(transactionId, ignored -> new ArrayList<>())
                    .add(decodeRecord(parts[3], lineIndex));
        }
        case RECORD_COMMIT -> {
            require(parts.length == 4, lineIndex, "COMMIT requires transaction id and commit sequence");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            long commitSequence = parseLong(parts[3], lineIndex, "commit sequence");
            require(commitSequence > 0L, lineIndex, "commit sequence must be positive");
            recordTerminalState(transactionId, TerminalState.committed(new MvccCommitSequence(commitSequence)),
                    terminalStates, terminalOrder);
        }
        case RECORD_ABORT -> {
            require(parts.length == 3, lineIndex, "ABORT requires transaction id");
            long transactionId = parseLong(parts[2], lineIndex, "transaction id");
            recordTerminalState(transactionId, TerminalState.aborted(), terminalStates, terminalOrder);
        }
        case RECORD_BEGIN, RECORD_PREPARED, RECORD_FSYNC -> {
            // New transaction-batch markers and force markers are irrelevant to
            // the compatibility recovery path.
        }
        default -> throw corrupt(lineIndex, "unknown page mutation log record type: " + parts[1]);
        }
    }

    private static void recordTerminalState(
            long transactionId,
            TerminalState terminalState,
            Map<Long, TerminalState> terminalStates,
            List<Long> terminalOrder) {
        if (!terminalStates.containsKey(transactionId)) {
            terminalStates.put(transactionId, terminalState);
            terminalOrder.add(transactionId);
        }
    }

    private static void appendPreparedBoundary(
            StringBuilder content,
            String type,
            long transactionId,
            long commitSequence,
            int recordCount,
            long statusTransactionId) {
        if (statusTransactionId > 0L) {
            appendLine(content, type,
                    Long.toString(transactionId),
                    Long.toString(commitSequence),
                    Integer.toString(recordCount),
                    Long.toString(statusTransactionId));
        } else {
            appendLine(content, type,
                    Long.toString(transactionId),
                    Long.toString(commitSequence),
                    Integer.toString(recordCount));
        }
    }

    private synchronized void appendLine(String type, String... fields) {
        StringBuilder line = new StringBuilder();
        appendLine(line, type, fields);
        journal.append(line.toString(), "MVCC page mutation log record");
    }

    private static void appendLine(StringBuilder content, String type, String... fields) {
        content.append(LOG_VERSION).append('\t').append(type);
        for (String field : fields) {
            content.append('\t').append(field);
        }
        content.append('\n');
    }

    private synchronized void writeAtomically(byte[] bytes) {
        rewriteBytesAtomicallyForced(bytes, "MVCC page mutation checkpoint log");
    }

    private static String encodeRecord(MvccVersionRecord record) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(MvccVersionRecordCodec.encode(record));
    }

    private static MvccVersionRecord decodeRecord(String encoded, int lineIndex) {
        try {
            return MvccVersionRecordCodec.decode(Base64.getUrlDecoder().decode(encoded));
        } catch (IllegalArgumentException e) {
            throw corrupt(lineIndex, "invalid encoded MVCC version record", e);
        }
    }

    private static MvccVersionRecord recordCommittedAt(
            MvccVersionRecord record,
            MvccCommitSequence commitSequence) {
        MvccTupleHeader header = record.header();
        if (!header.commitSequence().equals(MvccCommitSequence.NONE)) {
            if (!header.commitSequence().equals(commitSequence)) {
                throw new IllegalStateException("mutation record commit sequence " + header.commitSequence()
                        + " conflicts with durable outcome " + commitSequence);
            }
            return record;
        }
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

    private static int parseCount(String value, int lineIndex) {
        long count = parseLong(value, lineIndex, "expected record count");
        require(count > 0L && count <= Integer.MAX_VALUE, lineIndex,
                "expected record count must be a positive integer");
        return (int) count;
    }

    private static void requireTransactionId(long transactionId) {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transaction id must be positive: " + transactionId);
        }
    }

    private static void requireCommitSequence(long commitSequence) {
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commit sequence must be positive: " + commitSequence);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
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

    private record TerminalState(boolean committed, MvccCommitSequence commitSequence) {
        private static TerminalState committed(MvccCommitSequence commitSequence) {
            return new TerminalState(true, Objects.requireNonNull(commitSequence, "commitSequence"));
        }

        private static TerminalState aborted() {
            return new TerminalState(false, MvccCommitSequence.NONE);
        }
    }

    private static final class StrictTransaction {
        private final long transactionId;
        private final List<MvccVersionRecord> records = new ArrayList<>();
        private boolean batched;
        private boolean prepared;
        private long commitSequence;
        private int expectedRecordCount;
        private long statusTransactionId;
        private int lastLineIndex;

        private StrictTransaction(long transactionId) {
            requireTransactionId(transactionId);
            this.transactionId = transactionId;
        }

        private void begin(
                long sequence,
                int expectedCount,
                long correlatedStatusTransactionId,
                int lineIndex) {
            require(!batched, lineIndex, "duplicate BEGIN for transaction " + transactionId);
            require(records.isEmpty(), lineIndex,
                    "BEGIN must precede VERSION records for transaction " + transactionId);
            require(sequence > 0L, lineIndex, "commit sequence must be positive");
            batched = true;
            commitSequence = sequence;
            expectedRecordCount = expectedCount;
            statusTransactionId = correlatedStatusTransactionId;
            lastLineIndex = lineIndex;
        }

        private void addRecord(MvccVersionRecord record, int lineIndex) {
            require(!prepared, lineIndex,
                    "VERSION must not follow PREPARED for transaction " + transactionId);
            records.add(Objects.requireNonNull(record, "record"));
            lastLineIndex = lineIndex;
        }

        private void prepared(
                long sequence,
                int expectedCount,
                long correlatedStatusTransactionId,
                int lineIndex) {
            require(batched, lineIndex, "PREPARED without BEGIN for transaction " + transactionId);
            require(!prepared, lineIndex, "duplicate PREPARED for transaction " + transactionId);
            require(sequence == commitSequence, lineIndex,
                    "PREPARED commit sequence does not match BEGIN for transaction " + transactionId);
            require(expectedCount == expectedRecordCount, lineIndex,
                    "PREPARED record count does not match BEGIN for transaction " + transactionId);
            require(correlatedStatusTransactionId == statusTransactionId, lineIndex,
                    "PREPARED status transaction id does not match BEGIN for transaction " + transactionId);
            prepared = true;
            lastLineIndex = lineIndex;
        }

        private long transactionId() {
            return transactionId;
        }

        private List<MvccVersionRecord> records() {
            return records;
        }

        private boolean batched() {
            return batched;
        }

        private boolean prepared() {
            return prepared;
        }

        private long commitSequence() {
            return commitSequence;
        }

        private int expectedRecordCount() {
            return expectedRecordCount;
        }

        private long statusTransactionId() {
            return statusTransactionId;
        }

        private int lastLineIndex() {
            return lastLineIndex;
        }
    }
}
