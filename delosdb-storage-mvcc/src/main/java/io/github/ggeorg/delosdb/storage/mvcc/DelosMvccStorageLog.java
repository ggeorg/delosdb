package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Append-only recovery log for the experimental Delos MVCC provider.
 *
 * <p>This is intentionally provider-local. It is not Derby WAL, does not write
 * Derby log records, and does not reinterpret Derby heap pages. The first
 * durable shape is narrow and matches the current SQL bridge row model:
 * {@code Long} row keys and {@code List<Object>} row values containing null,
 * integer, long, and string values.</p>
 */
final class DelosMvccStorageLog {
    private static final DelosMvccStorageLog DISABLED = new DelosMvccStorageLog(null);
    private static final String LOG_FILE_NAME = "delos-mvcc-storage.log";
    private static final String VERSION = "1";
    private static final String CREATE_TABLE = "CREATE_TABLE";
    private static final String INSERT = "INSERT";
    private static final String UPDATE = "UPDATE";
    private static final String DELETE = "DELETE";
    private static final String COMMIT = "COMMIT";
    private static final String ABORT = "ABORT";

    private final Path logFile;

    private DelosMvccStorageLog(Path logFile) {
        this.logFile = logFile;
    }

    static DelosMvccStorageLog disabled() {
        return DISABLED;
    }

    static DelosMvccStorageLog open(Path storageDirectory) {
        Objects.requireNonNull(storageDirectory, "storageDirectory");
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create delos_mvcc storage directory: " + storageDirectory, e);
        }
        return new DelosMvccStorageLog(storageDirectory.resolve(LOG_FILE_NAME));
    }

    boolean isEnabled() {
        return logFile != null;
    }

    synchronized RecoveryImage recover() {
        return recoverUsingDurableStatuses(null);
    }

    synchronized RecoveryImage recoverUsingDurableStatuses(MvccTransactionStatusStore transactionStatusStore) {
        if (!isEnabled() || !Files.exists(logFile)) {
            return RecoveryImage.empty();
        }

        String content;
        try {
            content = Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read delos_mvcc storage log: " + logFile, e);
        }
        if (content.isEmpty()) {
            return RecoveryImage.empty();
        }

        boolean hasCompleteFinalLine = content.endsWith("\n") || content.endsWith("\r");
        String[] lines = content.split("\\R", -1);
        int lastLineIndex = lines.length - 1;
        if (hasCompleteFinalLine && lastLineIndex >= 0 && lines[lastLineIndex].isEmpty()) {
            lastLineIndex--;
        }

        Set<VersionedTableMetadata> tables = new LinkedHashSet<>();
        Map<Long, List<RecoveredChange>> changesByTransaction = new LinkedHashMap<>();
        Map<Long, TerminalState> terminalStates = new LinkedHashMap<>();
        List<Long> terminalOrder = new ArrayList<>();

        for (int i = 0; i <= lastLineIndex; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                parseRecord(line, i, tables, changesByTransaction, terminalStates, terminalOrder);
            } catch (IllegalStateException e) {
                if (i == lastLineIndex && !hasCompleteFinalLine) {
                    break;
                }
                throw e;
            }
        }

        List<CommittedTransaction> committed = transactionStatusStore != null && transactionStatusStore.isEnabled()
                ? committedTransactionsFromDurableStatus(changesByTransaction, transactionStatusStore)
                : committedTransactionsFromStorageLog(changesByTransaction, terminalStates, terminalOrder);
        return new RecoveryImage(List.copyOf(tables), List.copyOf(committed));
    }

    private static List<CommittedTransaction> committedTransactionsFromStorageLog(
            Map<Long, List<RecoveredChange>> changesByTransaction,
            Map<Long, TerminalState> terminalStates,
            List<Long> terminalOrder) {
        List<CommittedTransaction> committed = new ArrayList<>();
        for (Long txId : terminalOrder) {
            if (terminalStates.get(txId) != TerminalState.COMMITTED) {
                continue;
            }
            List<RecoveredChange> changes = changesByTransaction.getOrDefault(txId, List.of());
            if (!changes.isEmpty()) {
                committed.add(new CommittedTransaction(txId, List.copyOf(changes)));
            }
        }
        return List.copyOf(committed);
    }

    private static List<CommittedTransaction> committedTransactionsFromDurableStatus(
            Map<Long, List<RecoveredChange>> changesByTransaction,
            MvccTransactionStatusStore transactionStatusStore) {
        Map<MvccTransactionId, MvccTransactionStatusRecord> statuses = transactionStatusStore.recoverStatuses();
        List<MvccTransactionStatusRecord> committedStatuses = new ArrayList<>();
        for (Map.Entry<Long, List<RecoveredChange>> entry : changesByTransaction.entrySet()) {
            MvccTransactionStatusRecord status = statuses.get(new MvccTransactionId(entry.getKey()));
            if (status != null && status.status() == MvccTransactionStatus.COMMITTED && !entry.getValue().isEmpty()) {
                committedStatuses.add(status);
            }
        }
        committedStatuses.sort((left, right) -> {
            int byCommitSequence = left.commitSequence().compareTo(right.commitSequence());
            if (byCommitSequence != 0) {
                return byCommitSequence;
            }
            return left.transactionId().compareTo(right.transactionId());
        });

        List<CommittedTransaction> committed = new ArrayList<>();
        for (MvccTransactionStatusRecord status : committedStatuses) {
            long txId = status.transactionId().value();
            committed.add(new CommittedTransaction(txId, List.copyOf(changesByTransaction.get(txId))));
        }
        return List.copyOf(committed);
    }

    void appendCreateTable(VersionedTableMetadata metadata) {
        if (!isEnabled()) {
            return;
        }
        append(CREATE_TABLE, encode(metadata.schemaName()), encode(metadata.tableName()));
    }

    void appendInsert(VersionedTableMetadata metadata, long txId, Object key, Object value) {
        if (!isEnabled()) {
            return;
        }
        append(INSERT, Long.toString(txId), encode(metadata.schemaName()), encode(metadata.tableName()),
                Long.toString(MvccSqlStorageContract.requireLongRowKey(key, "storage log")),
                encodeValues(MvccSqlStorageContract.requireSqlRowValue(value, "storage log")));
    }

    void appendUpdate(VersionedTableMetadata metadata, long txId, Object key, Object value) {
        if (!isEnabled()) {
            return;
        }
        append(UPDATE, Long.toString(txId), encode(metadata.schemaName()), encode(metadata.tableName()),
                Long.toString(MvccSqlStorageContract.requireLongRowKey(key, "storage log")),
                encodeValues(MvccSqlStorageContract.requireSqlRowValue(value, "storage log")));
    }

    void appendDelete(VersionedTableMetadata metadata, long txId, Object key) {
        if (!isEnabled()) {
            return;
        }
        append(DELETE, Long.toString(txId), encode(metadata.schemaName()), encode(metadata.tableName()),
                Long.toString(MvccSqlStorageContract.requireLongRowKey(key, "storage log")));
    }

    void appendCommit(long txId) {
        if (!isEnabled()) {
            return;
        }
        append(COMMIT, Long.toString(txId));
    }

    void appendAbort(long txId) {
        if (!isEnabled()) {
            return;
        }
        append(ABORT, Long.toString(txId));
    }

    synchronized void rewriteCheckpoint(List<VersionedTableMetadata> tables, List<CheckpointRow> rows) {
        if (!isEnabled()) {
            return;
        }
        Objects.requireNonNull(tables, "tables");
        Objects.requireNonNull(rows, "rows");

        StringBuilder content = new StringBuilder();
        for (VersionedTableMetadata metadata : tables) {
            appendLine(content, CREATE_TABLE, encode(metadata.schemaName()), encode(metadata.tableName()));
        }
        if (!rows.isEmpty()) {
            long checkpointTxId = 1L;
            for (CheckpointRow row : rows) {
                appendLine(content, INSERT,
                        Long.toString(checkpointTxId),
                        encode(row.metadata().schemaName()),
                        encode(row.metadata().tableName()),
                        Long.toString(row.key()),
                        encodeValues(row.values()));
            }
            appendLine(content, COMMIT, Long.toString(checkpointTxId));
        }
        writeAtomically(content.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void parseRecord(
            String line,
            int lineIndex,
            Set<VersionedTableMetadata> tables,
            Map<Long, List<RecoveredChange>> changesByTransaction,
            Map<Long, TerminalState> terminalStates,
            List<Long> terminalOrder) {
        String[] parts = line.split("\\t", -1);
        require(parts.length >= 2, lineIndex, "record has too few fields");
        require(VERSION.equals(parts[0]), lineIndex, "unsupported log version: " + parts[0]);
        String type = parts[1];
        switch (type) {
        case CREATE_TABLE -> {
            require(parts.length == 4, lineIndex, "CREATE_TABLE requires schema and table");
            tables.add(new VersionedTableMetadata(decode(parts[2]), decode(parts[3])));
        }
        case INSERT, UPDATE -> {
            require(parts.length == 7, lineIndex, type + " requires tx, schema, table, key, values");
            long txId = parseLong(parts[2], lineIndex, "transaction id");
            VersionedTableMetadata metadata = new VersionedTableMetadata(decode(parts[3]), decode(parts[4]));
            tables.add(metadata);
            changesByTransaction.computeIfAbsent(txId, ignored -> new ArrayList<>())
                    .add(new RecoveredChange(type, metadata, parseLong(parts[5], lineIndex, "row key"), decodeValues(parts[6])));
        }
        case DELETE -> {
            require(parts.length == 6, lineIndex, "DELETE requires tx, schema, table, key");
            long txId = parseLong(parts[2], lineIndex, "transaction id");
            VersionedTableMetadata metadata = new VersionedTableMetadata(decode(parts[3]), decode(parts[4]));
            tables.add(metadata);
            changesByTransaction.computeIfAbsent(txId, ignored -> new ArrayList<>())
                    .add(new RecoveredChange(type, metadata, parseLong(parts[5], lineIndex, "row key"), List.of()));
        }
        case COMMIT -> {
            require(parts.length == 3, lineIndex, "COMMIT requires tx");
            recordTerminalState(parseLong(parts[2], lineIndex, "transaction id"), TerminalState.COMMITTED, terminalStates, terminalOrder);
        }
        case ABORT -> {
            require(parts.length == 3, lineIndex, "ABORT requires tx");
            recordTerminalState(parseLong(parts[2], lineIndex, "transaction id"), TerminalState.ABORTED, terminalStates, terminalOrder);
        }
        default -> throw corrupt(lineIndex, "unknown record type: " + type);
        }
    }

    private static void recordTerminalState(
            long txId,
            TerminalState state,
            Map<Long, TerminalState> terminalStates,
            List<Long> terminalOrder) {
        if (!terminalStates.containsKey(txId)) {
            terminalStates.put(txId, state);
            terminalOrder.add(txId);
        }
    }

    private synchronized void append(String type, String... fields) {
        StringBuilder line = new StringBuilder();
        appendLine(line, type, fields);
        byte[] bytes = line.toString().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append delos_mvcc storage log record: " + type, e);
        }
    }

    private void writeAtomically(byte[] bytes) {
        Path temp = logFile.resolveSibling(logFile.getFileName() + ".checkpoint.tmp");
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write delos_mvcc checkpoint log: " + temp, e);
        }
        try {
            Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveFailure) {
            try {
                Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailure) {
                moveFailure.addSuppressed(atomicMoveFailure);
                throw new UncheckedIOException("Could not replace delos_mvcc storage log with checkpoint: " + logFile, moveFailure);
            }
        }
    }

    private static void appendLine(StringBuilder content, String type, String... fields) {
        content.append(VERSION).append('\t').append(type);
        for (String field : fields) {
            content.append('\t').append(field);
        }
        content.append('\n');
    }

    private static String encodeValues(List<Object> values) {
        List<String> encoded = new ArrayList<>(values.size());
        for (Object value : values) {
            encoded.add(encodeValue(value));
        }
        return String.join(";", encoded);
    }

    private static List<Object> decodeValues(String encodedValues) {
        if (encodedValues.isEmpty()) {
            return List.of();
        }
        String[] parts = encodedValues.split(";", -1);
        List<Object> values = new ArrayList<>(parts.length);
        for (String part : parts) {
            values.add(decodeValue(part));
        }
        return Collections.unmodifiableList(values);
    }

    private static String encodeValue(Object value) {
        if (value == null) {
            return "N";
        }
        if (value instanceof Integer intValue) {
            return "I" + intValue;
        }
        if (value instanceof Long longValue) {
            return "L" + longValue;
        }
        if (value instanceof String stringValue) {
            return "S" + encode(stringValue);
        }
        throw new UnsupportedOperationException("Unsupported durable delos_mvcc value type: " + value.getClass().getName());
    }

    private static Object decodeValue(String encodedValue) {
        if (encodedValue.equals("N")) {
            return null;
        }
        if (encodedValue.startsWith("I")) {
            return Integer.valueOf(encodedValue.substring(1));
        }
        if (encodedValue.startsWith("L")) {
            return Long.valueOf(encodedValue.substring(1));
        }
        if (encodedValue.startsWith("S")) {
            return decode(encodedValue.substring(1));
        }
        throw new IllegalStateException("Corrupt delos_mvcc value in storage log: " + encodedValue);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static long parseLong(String value, int lineIndex, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(lineIndex, "invalid " + fieldName + ": " + value);
        }
    }

    private static void require(boolean condition, int lineIndex, String message) {
        if (!condition) {
            throw corrupt(lineIndex, message);
        }
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return new IllegalStateException("Corrupt delos_mvcc storage log at line " + (lineIndex + 1) + ": " + message);
    }

    record RecoveryImage(List<VersionedTableMetadata> tables, List<CommittedTransaction> committedTransactions) {
        static RecoveryImage empty() {
            return new RecoveryImage(List.of(), List.of());
        }
    }

    record CommittedTransaction(long sourceTransactionId, List<RecoveredChange> changes) {
    }

    record RecoveredChange(String operation, VersionedTableMetadata metadata, long key, List<Object> values) {
        boolean isInsert() {
            return INSERT.equals(operation);
        }

        boolean isUpdate() {
            return UPDATE.equals(operation);
        }

        boolean isDelete() {
            return DELETE.equals(operation);
        }
    }

    record CheckpointRow(VersionedTableMetadata metadata, long key, List<Object> values) {
        CheckpointRow {
            metadata = Objects.requireNonNull(metadata, "metadata");
            values = MvccSqlStorageContract.copySqlRowValues(Objects.requireNonNull(values, "values"));
        }
    }

    private enum TerminalState {
        COMMITTED,
        ABORTED
    }
}
