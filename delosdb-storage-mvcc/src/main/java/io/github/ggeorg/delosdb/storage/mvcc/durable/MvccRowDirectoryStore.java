package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccDurableLineRecords;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/**
 * Forced append-only row-directory head store for the page-backed MVCC table.
 *
 * <p>The store records the current
 * logical-row head locator after each committed insert/update/delete. Version
 * pages remain the source of row payloads and historical version records; this
 * sidecar makes the {@code MvccRowId -> head MvccVersionLocator} mapping
 * explicit and durable instead of only an in-memory rebuild artifact.</p>
 */
public final class MvccRowDirectoryStore extends AbstractSidecarStore implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final String FIELD_SEPARATOR = "\t";
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();


    private MvccRowDirectoryStore(Path path) {
        super(path);
    }

    public static MvccRowDirectoryStore open(Path path) {
        MvccRowDirectoryStore store = new MvccRowDirectoryStore(path);
        store.ensureParentDirectory("MVCC row-directory");
        return store;
    }

    public Path path() {
        return sidecarPath();
    }

    public synchronized void recordHead(RowHeadRecord record) throws IOException {
        recordHeads(List.of(Objects.requireNonNull(record, "record")));
    }

    /**
     * Appends all row-head publications from one committed transaction with one
     * forced sidecar append. Version pages remain the recovery authority, so a
     * torn final batch is discarded and reconciled from page state on reopen.
     */
    public synchronized void recordHeads(Iterable<RowHeadRecord> records) throws IOException {
        Objects.requireNonNull(records, "records");
        StringBuilder content = new StringBuilder();
        for (RowHeadRecord record : records) {
            content.append(encode(Objects.requireNonNull(record, "records entry")))
                    .append(System.lineSeparator());
        }
        if (content.length() > 0) {
            appendUtf8Forced(content.toString(), "MVCC row-directory transaction batch");
        }
    }

    public synchronized boolean hasRecords() throws IOException {
        return sidecarHasBytes();
    }

    public synchronized Map<MvccRowId, RowHeadRecord> recoverHeads() throws IOException {
        Map<MvccRowId, RowHeadRecord> heads = new LinkedHashMap<>();
        String content = readUtf8IfExists("MVCC row-directory");
        List<MvccDurableLineRecords.LineRecord> completeRecords =
                MvccDurableLineRecords.completeRecords(content, false);
        for (MvccDurableLineRecords.LineRecord lineRecord : completeRecords) {
            RowHeadRecord record = decode(lineRecord.line(), lineRecord.lineIndex() + 1);
            heads.put(record.rowId(), record);
        }
        if (!content.isEmpty() && !content.endsWith("\n") && !content.endsWith("\r")) {
            rewriteHeads(heads.values());
        }
        return heads;
    }

    public synchronized Optional<RowHeadRecord> headForRowId(MvccRowId rowId) throws IOException {
        Objects.requireNonNull(rowId, "rowId");
        return Optional.ofNullable(recoverHeads().get(rowId));
    }

    public synchronized void bootstrapIfEmpty(Iterable<RowHeadRecord> records) throws IOException {
        Objects.requireNonNull(records, "records");
        if (hasRecords()) {
            return;
        }
        for (RowHeadRecord record : records) {
            recordHead(record);
        }
    }

    public synchronized void rewriteHeads(Iterable<RowHeadRecord> records) throws IOException {
        Objects.requireNonNull(records, "records");
        StringBuilder content = new StringBuilder();
        for (RowHeadRecord record : records) {
            Objects.requireNonNull(record, "record");
            content.append(encode(record)).append(System.lineSeparator());
        }
        rewriteUtf8AtomicallyForced(content.toString(), "MVCC row-directory heads");
        forceParentDirectoryIfSupported();
    }

    @Override
    public void close() {
        // The store opens and forces the file per append.
    }

    private static String encode(RowHeadRecord record) {
        return FORMAT_VERSION
                + FIELD_SEPARATOR + record.rowId().value()
                + FIELD_SEPARATOR + KEY_ENCODER.encodeToString(record.key().getBytes(StandardCharsets.UTF_8))
                + FIELD_SEPARATOR + record.headVersionId().value()
                + FIELD_SEPARATOR + record.previousVersionId().value()
                + FIELD_SEPARATOR + record.headLocator().pageId().value()
                + FIELD_SEPARATOR + record.headLocator().slotId()
                + FIELD_SEPARATOR + (record.tombstone() ? "1" : "0");
    }

    private static RowHeadRecord decode(String line, int lineNumber) {
        String[] fields = line.split(FIELD_SEPARATOR, -1);
        if (fields.length != 8) {
            throw new IllegalStateException("Invalid MVCC row-directory record at line " + lineNumber
                    + ": expected 8 fields but found " + fields.length);
        }
        int version = parseInt(fields[0], "format version", lineNumber);
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException("Unsupported MVCC row-directory format version at line "
                    + lineNumber + ": " + version);
        }
        String key = new String(KEY_DECODER.decode(fields[2]), StandardCharsets.UTF_8);
        return new RowHeadRecord(
                new MvccRowId(parseLong(fields[1], "row id", lineNumber)),
                key,
                new MvccVersionId(parseLong(fields[3], "head version id", lineNumber)),
                new MvccVersionId(parseLong(fields[4], "previous version id", lineNumber)),
                new MvccVersionLocator(
                        new DelosPageId(parseLong(fields[5], "head page id", lineNumber)),
                        parseInt(fields[6], "head slot id", lineNumber)),
                parseTombstone(fields[7], lineNumber));
    }

    private static long parseLong(String value, String name, int lineNumber) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid MVCC row-directory " + name + " at line "
                    + lineNumber + ": " + value, e);
        }
    }

    private static int parseInt(String value, String name, int lineNumber) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Invalid MVCC row-directory " + name + " at line "
                    + lineNumber + ": " + value, e);
        }
    }

    private static boolean parseTombstone(String value, int lineNumber) {
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalStateException("Invalid MVCC row-directory tombstone flag at line "
                + lineNumber + ": " + value);
    }

    public record RowHeadRecord(
            MvccRowId rowId,
            String key,
            MvccVersionId headVersionId,
            MvccVersionId previousVersionId,
            MvccVersionLocator headLocator,
            boolean tombstone) {
        public RowHeadRecord {
            rowId = Objects.requireNonNull(rowId, "rowId");
            key = MvccRowPayload.requireKey(key);
            headVersionId = Objects.requireNonNull(headVersionId, "headVersionId");
            previousVersionId = Objects.requireNonNull(previousVersionId, "previousVersionId");
            headLocator = Objects.requireNonNull(headLocator, "headLocator");
            if (rowId.isNone()) {
                throw new IllegalArgumentException("row-directory head cannot use row:none");
            }
            if (headVersionId.isNone()) {
                throw new IllegalArgumentException("row-directory head cannot use version:none");
            }
        }
    }
}
