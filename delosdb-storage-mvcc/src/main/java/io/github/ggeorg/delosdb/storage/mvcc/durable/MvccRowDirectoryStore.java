package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/**
 * Forced append-only row-directory head store for the page-backed MVCC table.
 *
 * <p>The store is intentionally small in MODULE5M: it records the current
 * logical-row head locator after each committed insert/update/delete. Version
 * pages remain the source of row payloads and historical version records; this
 * sidecar makes the {@code MvccRowId -> head MvccVersionLocator} mapping
 * explicit and durable instead of only an in-memory rebuild artifact.</p>
 */
public final class MvccRowDirectoryStore implements AutoCloseable {
    private static final int FORMAT_VERSION = 1;
    private static final String FIELD_SEPARATOR = "\t";
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder KEY_DECODER = Base64.getUrlDecoder();

    private final Path path;

    private MvccRowDirectoryStore(Path path) {
        this.path = Objects.requireNonNull(path, "path");
    }

    public static MvccRowDirectoryStore open(Path path) {
        Objects.requireNonNull(path, "path");
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Could not create MVCC row-directory parent: " + parent, e);
            }
        }
        return new MvccRowDirectoryStore(path);
    }

    public Path path() {
        return path;
    }

    public synchronized void recordHead(RowHeadRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        byte[] encoded = (encode(record) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(encoded));
            channel.force(true);
        }
    }

    public synchronized boolean hasRecords() throws IOException {
        return Files.exists(path) && Files.size(path) > 0L;
    }

    public synchronized Map<MvccRowId, RowHeadRecord> recoverHeads() throws IOException {
        Map<MvccRowId, RowHeadRecord> heads = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return heads;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank()) {
                continue;
            }
            RowHeadRecord record = decode(line, lineNumber + 1);
            heads.put(record.rowId(), record);
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
        Path rewritePath = path.resolveSibling(path.getFileName() + ".rewrite");
        Files.deleteIfExists(rewritePath);
        try (FileChannel channel = FileChannel.open(
                rewritePath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        MvccRowDirectoryStore rewrite = new MvccRowDirectoryStore(rewritePath);
        for (RowHeadRecord record : records) {
            rewrite.recordHead(record);
        }
        moveRewriteIntoPlace(rewritePath);
        forceParentDirectory();
    }

    private void moveRewriteIntoPlace(Path rewritePath) throws IOException {
        try {
            Files.move(rewritePath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Keep the same-directory rewrite path portable for filesystems that do not expose
            // atomic move through the JDK. The rewrite file was forced before this fallback and
            // the parent directory is forced afterwards when the platform supports it.
            Files.move(rewritePath, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void forceParentDirectory() throws IOException {
        try (FileChannel parent = FileChannel.open(path.getParent() == null ? Path.of(".") : path.getParent(), StandardOpenOption.READ)) {
            parent.force(true);
        } catch (IOException ignored) {
            // Some platforms do not support forcing a directory. The row-directory file itself is already forced.
        }
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
