package io.github.ggeorg.delosdb.storage.mvcc;

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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;

/**
 * Forced append-only provider-local MVCC log writer for MODULE5J.
 *
 * <p>This class adds the first WAL-like discipline for Delos MVCC: BEGIN,
 * version, COMMIT, and ABORT records have monotonically increasing LSNs and are
 * forced to disk before callers can trust the dependent status/page action. It
 * is intentionally not Derby WAL and not full ARIES.</p>
 */
public class MvccLogWriter {
    private static final String LOG_VERSION = "1";

    private static final MvccLogWriter DISABLED = new MvccLogWriter(null, 1L) {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public Optional<Path> path() {
            return Optional.empty();
        }

        @Override
        public synchronized MvccLogRecord append(MvccLogRecord record) {
            Objects.requireNonNull(record, "record");
            return record.withLsn(DelosLogSequenceNumber.NONE);
        }

        @Override
        public synchronized List<MvccLogRecord> recoverRecords() {
            return List.of();
        }
    };

    private final Path path;
    private long nextLsnValue;

    private MvccLogWriter(Path path, long nextLsnValue) {
        this.path = path;
        this.nextLsnValue = nextLsnValue;
    }

    public static MvccLogWriter disabled() {
        return DISABLED;
    }

    public static MvccLogWriter open(Path path) {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create MVCC log directory: " + parent, e);
            }
        }
        return new MvccLogWriter(path, recoverLastLsn(path).value() + 1L);
    }

    public boolean isEnabled() {
        return true;
    }

    public Optional<Path> path() {
        return Optional.of(path);
    }

    public MvccLogRecord appendBegin(MvccTransactionId transactionId) {
        return append(MvccLogRecord.begin(transactionId));
    }

    public MvccLogRecord appendInsertVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            Object rowKey) {
        return append(MvccLogRecord.insertVersion(transactionId, table, requireLongKey(rowKey)));
    }

    public MvccLogRecord appendUpdateVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            Object rowKey) {
        return append(MvccLogRecord.updateVersion(transactionId, table, requireLongKey(rowKey)));
    }

    public MvccLogRecord appendDeleteVersion(
            MvccTransactionId transactionId,
            VersionedTableMetadata table,
            Object rowKey) {
        return append(MvccLogRecord.deleteVersion(transactionId, table, requireLongKey(rowKey)));
    }

    public MvccLogRecord appendCommit(
            MvccTransactionId transactionId,
            MvccCommitSequence commitSequence) {
        return append(MvccLogRecord.commit(transactionId, commitSequence));
    }

    public MvccLogRecord appendAbort(MvccTransactionId transactionId) {
        return append(MvccLogRecord.abort(transactionId));
    }

    public synchronized MvccLogRecord append(MvccLogRecord record) {
        Objects.requireNonNull(record, "record");
        DelosLogSequenceNumber lsn = new DelosLogSequenceNumber(nextLsnValue++);
        MvccLogRecord assigned = record.withLsn(lsn);
        byte[] bytes = encodeLine(assigned).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append MVCC log record to: " + path, e);
        }
        return assigned;
    }

    public synchronized List<MvccLogRecord> recoverRecords() {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        String content;
        try {
            content = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read MVCC log: " + path, e);
        }
        if (content.isEmpty()) {
            return List.of();
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

        List<MvccLogRecord> records = new ArrayList<>();
        DelosLogSequenceNumber previous = DelosLogSequenceNumber.NONE;
        for (int index = 0; index <= lastLineIndex; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            MvccLogRecord record = parseLine(line, index);
            if (record.lsn().compareTo(previous) <= 0) {
                throw corrupt(index, "LSN must increase monotonically: previous=" + previous
                        + ", current=" + record.lsn());
            }
            previous = record.lsn();
            records.add(record);
        }
        return List.copyOf(records);
    }

    private static DelosLogSequenceNumber recoverLastLsn(Path path) {
        if (!Files.exists(path)) {
            return DelosLogSequenceNumber.NONE;
        }
        List<MvccLogRecord> records = new MvccLogWriter(path, 1L).recoverRecords();
        if (records.isEmpty()) {
            return DelosLogSequenceNumber.NONE;
        }
        return records.get(records.size() - 1).lsn();
    }

    private static String encodeLine(MvccLogRecord record) {
        StringBuilder line = new StringBuilder(LOG_VERSION)
                .append('\t').append(record.lsn().value())
                .append('\t').append(record.type().name())
                .append('\t').append(record.transactionId().value())
                .append('\t').append(record.commitSequence().value());
        if (record.table() == null) {
            line.append("\t\t");
        } else {
            line.append('\t').append(encode(record.table().schemaName()))
                    .append('\t').append(encode(record.table().tableName()));
        }
        line.append('\t').append(record.rowKey() == null ? "" : record.rowKey().toString());
        line.append('\n');
        return line.toString();
    }

    private static MvccLogRecord parseLine(String line, int lineIndex) {
        String[] parts = line.split("\\t", -1);
        require(parts.length == 8, lineIndex, "record must have 8 fields");
        require(LOG_VERSION.equals(parts[0]), lineIndex, "unsupported MVCC log version: " + parts[0]);
        DelosLogSequenceNumber lsn = new DelosLogSequenceNumber(parseLong(parts[1], lineIndex, "lsn"));
        require(!lsn.isNone(), lineIndex, "LSN must be present");
        MvccLogRecord.Type type;
        try {
            type = MvccLogRecord.Type.valueOf(parts[2]);
        } catch (IllegalArgumentException e) {
            throw corrupt(lineIndex, "unknown MVCC log record type: " + parts[2], e);
        }
        MvccTransactionId transactionId = new MvccTransactionId(parseLong(parts[3], lineIndex, "transaction id"));
        MvccCommitSequence commitSequence = new MvccCommitSequence(parseLong(parts[4], lineIndex, "commit sequence"));
        VersionedTableMetadata table = parts[5].isEmpty() && parts[6].isEmpty()
                ? null
                : new VersionedTableMetadata(decode(parts[5]), decode(parts[6]));
        Long rowKey = parts[7].isEmpty() ? null : parseLong(parts[7], lineIndex, "row key");
        return new MvccLogRecord(lsn, type, transactionId, commitSequence, table, rowKey);
    }

    private static long requireLongKey(Object rowKey) {
        if (rowKey instanceof Long longKey) {
            return longKey;
        }
        throw new UnsupportedOperationException("MODULE5J MVCC log currently supports Long row keys only");
    }

    private static long parseLong(String value, int lineIndex, String fieldName) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(lineIndex, "invalid " + fieldName + ": " + value, e);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, int lineIndex, String message) {
        if (!condition) {
            throw corrupt(lineIndex, message);
        }
    }

    private static IllegalStateException corrupt(int lineIndex, String message) {
        return new IllegalStateException("Corrupt MVCC log at line " + (lineIndex + 1) + ": " + message);
    }

    private static IllegalStateException corrupt(int lineIndex, String message, Throwable cause) {
        return new IllegalStateException("Corrupt MVCC log at line " + (lineIndex + 1) + ": " + message, cause);
    }
}
