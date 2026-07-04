package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccTupleHeader;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecord;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionRecordCodec;

/**
 * Provider-local page-storage-aware recovery log for page-backed MVCC tables.
 *
 * <p>This is deliberately smaller than Derby WAL, but it follows the same core
 * rule as a real MVCC engine: version writes are not visible after recovery
 * unless the log also contains a commit record for the creating transaction.
 * The durable page file stores physical {@link MvccVersionRecord} instances;
 * this log stores the same record bytes plus transaction terminal records.</p>
 */
public final class MvccPageMutationLog extends AbstractSidecarStore {
    private static final String LOG_VERSION = "1";
    private static final String LOG_NAME = "MVCC page mutation log";
    private static final String RECORD_VERSION = "VERSION";
    private static final String RECORD_COMMIT = "COMMIT";
    private static final String RECORD_ABORT = "ABORT";
    private static final String RECORD_FSYNC = "FSYNC";


    private MvccPageMutationLog(Path path) {
        super(path);
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
        if (commitSequence <= 0L) {
            throw new IllegalArgumentException("commit sequence must be positive: " + commitSequence);
        }
        appendLine(RECORD_COMMIT, Long.toString(transactionId), Long.toString(commitSequence));
    }

    public void appendAbort(long transactionId) {
        requireTransactionId(transactionId);
        appendLine(RECORD_ABORT, Long.toString(transactionId));
    }

    /**
     * Records an explicit durable-boundary marker in the page mutation log.
     *
     * <p>The append operation itself is forced to stable storage by
     * {@link #appendLine(String, String...)}, so this marker is a readable
     * contract boundary for recovery tests and later checkpoint logic. Recovery
     * accepts the marker but never turns it into a row version.</p>
     */
    public void appendFsyncBoundary(long boundaryId) {
        if (boundaryId <= 0L) {
            throw new IllegalArgumentException("fsync boundary id must be positive: " + boundaryId);
        }
        appendLine(RECORD_FSYNC, Long.toString(boundaryId));
    }

    /**
     * Replaces the log with a compact committed image. The image is represented
     * as one synthetic committed transaction so normal recovery logic can replay
     * it without a separate checkpoint record format.
     */
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

    public synchronized List<MvccVersionRecord> recoverCommittedRecords() {
        if (!sidecarExists()) {
            return List.of();
        }
        String content = readUtf8IfExists(LOG_NAME);
        if (content.isEmpty()) {
            return List.of();
        }

        Map<Long, List<MvccVersionRecord>> versionsByTransaction = new LinkedHashMap<>();
        Map<Long, TerminalState> terminalStates = new LinkedHashMap<>();
        List<Long> terminalOrder = new ArrayList<>();

        for (MvccDurableLineRecords.LineRecord record : MvccDurableLineRecords.completeRecords(content)) {
            parseLine(record.line(), record.lineIndex(), versionsByTransaction, terminalStates, terminalOrder);
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
     * Replays raw version mutations through the strict A49/A50 transaction outcome log.
     *
     * <p>This is deliberately separate from {@link #recoverCommittedRecords()},
     * which keeps the legacy page-mutation-log behavior. The strict path treats
     * the transaction outcome log as authoritative: committed creators
     * materialize records, aborted creators are suppressed, and unknown creators
     * fail loudly.</p>
     */
    public synchronized List<MvccVersionRecord> recoverRecordsThroughOutcomeLog(
            MvccTransactionOutcomeLog outcomeLog) {
        Objects.requireNonNull(outcomeLog, "outcomeLog");
        if (!sidecarExists()) {
            return List.of();
        }
        String content = readUtf8IfExists(LOG_NAME);
        if (content.isEmpty()) {
            return List.of();
        }

        List<MvccVersionRecord> recovered = new ArrayList<>();
        for (MvccDurableLineRecords.LineRecord lineRecord : MvccDurableLineRecords.completeRecords(content)) {
            int index = lineRecord.lineIndex();
            String[] parts = MvccDurableLineRecords.tabFields(lineRecord.line());
            require(parts.length >= 2, index, "record has too few fields");
            require(LOG_VERSION.equals(parts[0]), index, "unsupported page mutation log version: " + parts[0]);
            switch (parts[1]) {
            case RECORD_VERSION -> {
                require(parts.length == 4, index, "VERSION requires transaction id and record bytes");
                long loggedTransactionId = parseLong(parts[2], index, "transaction id");
                requireTransactionId(loggedTransactionId);
                MvccVersionRecord record = decodeRecord(parts[3], index);
                require(record.header().createdByTx().value() == loggedTransactionId, index,
                        "VERSION transaction id must match record creator transaction id");
                outcomeLog.committedRecordOrEmpty(record).ifPresent(recovered::add);
            }
            case RECORD_COMMIT, RECORD_ABORT, RECORD_FSYNC -> {
                // Accepted for backward-compatible logs, but ignored here: the
                // strict path is governed only by MvccTransactionOutcomeLog.
            }
            default -> throw corrupt(index, "unknown page mutation log record type: " + parts[1]);
            }
        }
        return List.copyOf(recovered);
    }

    private void parseLine(
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
        case RECORD_FSYNC -> {
            require(parts.length == 3, lineIndex, "FSYNC requires boundary id");
            long boundaryId = parseLong(parts[2], lineIndex, "fsync boundary id");
            require(boundaryId > 0L, lineIndex, "fsync boundary id must be positive");
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

    private synchronized void appendLine(String type, String... fields) {
        StringBuilder line = new StringBuilder(LOG_VERSION).append('	').append(type);
        for (String field : fields) {
            line.append('	').append(field);
        }
        line.append('\n');
        appendUtf8Forced(line.toString(), "MVCC page mutation log record");
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

    private static MvccVersionRecord recordCommittedAt(MvccVersionRecord record, MvccCommitSequence commitSequence) {
        MvccTupleHeader header = record.header();
        if (!header.commitSequence().equals(MvccCommitSequence.NONE)) {
            // Checkpoint rewrites encode an already-committed image under a
            // synthetic page-log transaction. The synthetic COMMIT marks the
            // checkpoint image durable; it must not replace the original row
            // version's commit sequence.
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

    private static void requireTransactionId(long transactionId) {
        if (transactionId <= 0L) {
            throw new IllegalArgumentException("transaction id must be positive: " + transactionId);
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
}
