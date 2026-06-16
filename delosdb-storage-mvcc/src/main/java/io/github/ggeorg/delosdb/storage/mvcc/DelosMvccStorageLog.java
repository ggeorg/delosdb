package io.github.ggeorg.delosdb.storage.mvcc;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
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
        if (!isEnabled() || !Files.exists(logFile)) {
            return RecoveryImage.empty();
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read delos_mvcc storage log: " + logFile, e);
        }

        Set<VersionedTableMetadata> tables = new LinkedHashSet<>();
        Map<Long, List<RecoveredChange>> changesByTransaction = new LinkedHashMap<>();
        Set<Long> abortedTransactions = new LinkedHashSet<>();
        List<Long> committedTransactions = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            require(parts.length >= 2, i, "record has too few fields");
            require(VERSION.equals(parts[0]), i, "unsupported log version: " + parts[0]);
            String type = parts[1];
            switch (type) {
            case CREATE_TABLE -> {
                require(parts.length == 4, i, "CREATE_TABLE requires schema and table");
                tables.add(new VersionedTableMetadata(decode(parts[2]), decode(parts[3])));
            }
            case INSERT, UPDATE -> {
                require(parts.length == 7, i, type + " requires tx, schema, table, key, values");
                long txId = parseLong(parts[2], i, "transaction id");
                VersionedTableMetadata metadata = new VersionedTableMetadata(decode(parts[3]), decode(parts[4]));
                tables.add(metadata);
                changesByTransaction.computeIfAbsent(txId, ignored -> new ArrayList<>())
                        .add(new RecoveredChange(type, metadata, parseLong(parts[5], i, "row key"), decodeValues(parts[6])));
            }
            case DELETE -> {
                require(parts.length == 6, i, "DELETE requires tx, schema, table, key");
                long txId = parseLong(parts[2], i, "transaction id");
                VersionedTableMetadata metadata = new VersionedTableMetadata(decode(parts[3]), decode(parts[4]));
                tables.add(metadata);
                changesByTransaction.computeIfAbsent(txId, ignored -> new ArrayList<>())
                        .add(new RecoveredChange(type, metadata, parseLong(parts[5], i, "row key"), List.of()));
            }
            case COMMIT -> {
                require(parts.length == 3, i, "COMMIT requires tx");
                long txId = parseLong(parts[2], i, "transaction id");
                if (!abortedTransactions.contains(txId)) {
                    committedTransactions.add(txId);
                }
            }
            case ABORT -> {
                require(parts.length == 3, i, "ABORT requires tx");
                abortedTransactions.add(parseLong(parts[2], i, "transaction id"));
            }
            default -> throw corrupt(i, "unknown record type: " + type);
            }
        }

        List<CommittedTransaction> committed = new ArrayList<>();
        for (Long txId : committedTransactions) {
            if (abortedTransactions.contains(txId)) {
                continue;
            }
            List<RecoveredChange> changes = changesByTransaction.getOrDefault(txId, List.of());
            if (!changes.isEmpty()) {
                committed.add(new CommittedTransaction(txId, List.copyOf(changes)));
            }
        }
        return new RecoveryImage(List.copyOf(tables), List.copyOf(committed));
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
                Long.toString(requireLongKey(key)), encodeValues(requireSqlRowValue(value)));
    }

    void appendUpdate(VersionedTableMetadata metadata, long txId, Object key, Object value) {
        if (!isEnabled()) {
            return;
        }
        append(UPDATE, Long.toString(txId), encode(metadata.schemaName()), encode(metadata.tableName()),
                Long.toString(requireLongKey(key)), encodeValues(requireSqlRowValue(value)));
    }

    void appendDelete(VersionedTableMetadata metadata, long txId, Object key) {
        if (!isEnabled()) {
            return;
        }
        append(DELETE, Long.toString(txId), encode(metadata.schemaName()), encode(metadata.tableName()),
                Long.toString(requireLongKey(key)));
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

    private synchronized void append(String type, String... fields) {
        StringBuilder line = new StringBuilder(VERSION).append('\t').append(type);
        for (String field : fields) {
            line.append('\t').append(field);
        }
        line.append('\n');
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

    private static long requireLongKey(Object key) {
        if (key instanceof Long longKey) {
            return longKey;
        }
        throw new UnsupportedOperationException("Durable delos_mvcc recovery currently supports Long row keys only");
    }

    private static List<Object> requireSqlRowValue(Object value) {
        if (value instanceof List<?> rawValues) {
            return List.copyOf(rawValues);
        }
        throw new UnsupportedOperationException("Durable delos_mvcc recovery currently supports List<Object> row values only");
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
        return List.copyOf(values);
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
}
