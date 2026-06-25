package io.github.ggeorg.delosdb.storage.mvcc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Small forced append-only transaction-status store for MODULE5H.
 *
 * <p>This is not WAL. It is the first durable MVCC transaction-status authority
 * used by the live Derby commit/rollback route. Complete final records are
 * authoritative; a torn final line is ignored. Transactions whose latest
 * complete record is ACTIVE are exposed as RECOVERY_PENDING on reopen so their
 * versions are never visible by default.</p>
 */
public class MvccTransactionStatusStore {
    private static final String LOG_VERSION = "1";
    private static final String RECORD_ACTIVE = "ACTIVE";
    private static final String RECORD_COMMIT = "COMMITTED";
    private static final String RECORD_ABORT = "ABORTED";

    private static final MvccTransactionStatusStore DISABLED = new MvccTransactionStatusStore(null) {
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
        public void recordAborted(MvccTransactionId transactionId) {
            requireRealTransactionId(transactionId);
        }

        @Override
        public Map<MvccTransactionId, MvccTransactionStatusRecord> recoverStatuses() {
            return Map.of();
        }
    };

    private final Path path;

    private MvccTransactionStatusStore(Path path) {
        this.path = path;
    }

    public static MvccTransactionStatusStore disabled() {
        return DISABLED;
    }

    public static MvccTransactionStatusStore open(Path path) {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create MVCC transaction status directory: " + parent, e);
            }
        }
        return new MvccTransactionStatusStore(path);
    }

    public Optional<Path> path() {
        return Optional.of(path);
    }

    public boolean isEnabled() {
        return true;
    }

    public void recordActive(MvccTransactionId transactionId) {
        requireRealTransactionId(transactionId);
        appendLine(RECORD_ACTIVE, Long.toString(transactionId.value()));
    }

    public void recordCommitted(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
        validateCommitted(transactionId, commitSequence);
        appendLine(RECORD_COMMIT, Long.toString(transactionId.value()), Long.toString(commitSequence.value()));
    }

    public void recordAborted(MvccTransactionId transactionId) {
        requireRealTransactionId(transactionId);
        appendLine(RECORD_ABORT, Long.toString(transactionId.value()));
    }

    public synchronized Map<MvccTransactionId, MvccTransactionStatusRecord> recoverStatuses() {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC transaction status store: " + path, e);
        }
        if (content.isEmpty()) {
            return Map.of();
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

        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = new LinkedHashMap<>();
        for (int index = 0; index <= lastLineIndex; index++) {
            String line = lines[index].trim();
            if (line.isEmpty()) {
                continue;
            }
            parseLine(line, index, statuses);
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
        String[] parts = line.split("\\t", -1);
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
        StringBuilder line = new StringBuilder(LOG_VERSION).append('\t').append(type);
        for (String field : fields) {
            line.append('\t').append(field);
        }
        line.append('\n');
        byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append MVCC transaction status record to: " + path, e);
        }
    }

    private static void validateCommitted(MvccTransactionId transactionId, MvccCommitSequence commitSequence) {
        requireRealTransactionId(transactionId);
        Objects.requireNonNull(commitSequence, "commitSequence");
        if (commitSequence.equals(MvccCommitSequence.NONE)) {
            throw new IllegalArgumentException("commit sequence must be present for committed status");
        }
    }

    private static long parseLong(String value, int lineIndex, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(lineIndex, "invalid " + fieldName + ": " + value, e);
        }
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
        if (!condition) {
            throw corrupt(lineIndex, message);
        }
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return new IllegalStateException("Corrupt MVCC transaction status store at line "
                + (lineIndex + 1) + ": " + message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message, Throwable cause) {
        return new IllegalStateException("Corrupt MVCC transaction status store at line "
                + (lineIndex + 1) + ": " + message, cause);
    }
}
