package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;


/**
 * Durable page-backed store for MVCC ordered index entries.
 *
 * <p>Current-committed equality and range scans may use this store as row-id
 * authority for covered paths.  Entries keep a typed textual key envelope so
 * the sidecar does not accidentally apply lexical string ordering to numeric
 * SQL values.</p>
 */
public final class MvccOrderedIndexPageStore implements AutoCloseable {
    private static final int ORDERED_INDEX_PAGE_TYPE = 8;
    private static final int SLOT_OVERHEAD_BYTES = 12;
    private static final int MAGIC = 0x4f495831; // OIX1
    private static final short VERSION = 1;
    private static final DelosPageVolumeFactory FILE_VOLUME_FACTORY = DelosPageVolumeFactories.fileChannel();

    private final Path path;
    private final DelosPageVolumeFactory volumeFactory;
    private DelosPageVolume pageVolume;
    private Snapshot snapshot = new Snapshot(0L, List.of());
    private Set<Integer> columnsWithLegacyKeys = Set.of();
    private Set<Integer> columnsWithOversizedKeys = Set.of();
    private long snapshotLoadCount;
    private long rebuildCount;

    private MvccOrderedIndexPageStore(Path path, DelosPageVolumeFactory volumeFactory, DelosPageVolume pageVolume) {
        this.path = Objects.requireNonNull(path, "path");
        this.volumeFactory = Objects.requireNonNull(volumeFactory, "volumeFactory");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
    }

    public static MvccOrderedIndexPageStore open(Path path) throws IOException {
        return open(path, FILE_VOLUME_FACTORY);
    }

    static MvccOrderedIndexPageStore open(Path path, DelosPageVolumeFactory volumeFactory) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(volumeFactory, "volumeFactory");
        MvccOrderedIndexPageStore store = new MvccOrderedIndexPageStore(
                path, volumeFactory, volumeFactory.open(path));
        try {
            store.read();
            return store;
        } catch (IOException | RuntimeException | Error failure) {
            try {
                store.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    public synchronized Path path() {
        return path;
    }

    public synchronized boolean exists() {
        return Files.exists(path);
    }

    public synchronized void rewrite(List<Entry> entries) throws IOException {
        List<Entry> sorted = sorted(entries);
        Path replacementPath = createReplacementPath();
        DelosPageVolume replacement = null;
        boolean replacementClosed = false;
        long replacementPageCount = 0L;
        boolean replacementInstalled = false;
        try {
            replacement = volumeFactory.open(replacementPath);
            for (Entry entry : sorted) {
                appendEncoded(replacement, encode(entry));
            }
            replacementPageCount = replacement.pageCount();
            replacement.force();
            MvccCommitDurabilityMetrics.recordPageVolumeForce(replacementPageCount);
            replacement.close();
            replacementClosed = true;

            closeCurrentVolume();
            try {
                MvccDurableFiles.moveIntoPlace(replacementPath, path);
                replacementInstalled = true;
                MvccDurableFiles.forceParentDirectoryIfSupported(path);
            } catch (IOException moveFailure) {
                try {
                    ensureVolumeOpen();
                } catch (IOException reopenFailure) {
                    moveFailure.addSuppressed(reopenFailure);
                }
                throw moveFailure;
            }

            // Publishing the forced replacement and its parent-directory
            // entry is the durable success boundary. Reopening the file handle
            // is intentionally lazy: a transient descriptor/open
            // failure after rename must not report the rewrite as failed even
            // though the new sidecar is already authoritative on disk.
            try {
                pageVolume = volumeFactory.open(path);
            } catch (IOException reopenFailure) {
                pageVolume = null;
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (replacement != null && !replacementClosed) {
                try {
                    replacement.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (!replacementInstalled) {
                try {
                    Files.deleteIfExists(replacementPath);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
        installSnapshot(new Snapshot(replacementPageCount, sorted));
        rebuildCount++;
    }

    /**
     * Revalidates the durable sidecar and refreshes the immutable lookup
     * snapshot. Normal equality/range lookups use the installed snapshot and
     * do not reread or decode every page.
     */
    public synchronized Snapshot read() throws IOException {
        Snapshot loaded = readFromVolume();
        installSnapshot(loaded);
        snapshotLoadCount++;
        return loaded;
    }

    public synchronized long pageCount() {
        return snapshot.pageCount();
    }

    public synchronized long entryCount() {
        return snapshot.entries().size();
    }

    public synchronized int distinctKeyCount() {
        int count = 0;
        Entry previous = null;
        for (Entry entry : snapshot.entries()) {
            if (previous == null
                    || previous.column() != entry.column()
                    || !previous.key().equals(entry.key())) {
                count++;
            }
            previous = entry;
        }
        return count;
    }

    public synchronized List<Long> rowIdsFor(int column, String key) {
        requireValidColumn(column);
        String normalizedKey = Entry.normalizeKeyForLookup(Objects.requireNonNull(key, "key"));
        requireTypedLookupCompatible(column, OrderedIndexKeyCodec.isEncoded(normalizedKey));

        List<Entry> entries = snapshot.entries();
        int index = lowerBound(entries, column, normalizedKey);
        List<Long> rowIds = new ArrayList<>();
        while (index < entries.size()) {
            Entry entry = entries.get(index);
            if (entry.column() != column || compareKeys(entry.key(), normalizedKey) != 0) {
                break;
            }
            if (entry.key().equals(normalizedKey)) {
                rowIds.add(entry.rowId());
            }
            index++;
        }
        return List.copyOf(rowIds);
    }

    public synchronized List<Long> rowIdsInRangeFor(
            int column,
            String lowerKey,
            boolean lowerInclusive,
            String upperKey,
            boolean upperInclusive) {
        requireValidColumn(column);
        String normalizedLowerKey = lowerKey == null ? null : Entry.normalizeKeyForLookup(lowerKey);
        String normalizedUpperKey = upperKey == null ? null : Entry.normalizeKeyForLookup(upperKey);
        requireTypedLookupCompatible(
                column,
                OrderedIndexKeyCodec.isEncoded(normalizedLowerKey)
                        || OrderedIndexKeyCodec.isEncoded(normalizedUpperKey));
        requireRangeLookupCompatible(column, normalizedLowerKey, normalizedUpperKey);
        if (normalizedLowerKey != null && normalizedUpperKey != null
                && compareKeys(normalizedLowerKey, normalizedUpperKey) > 0) {
            return List.of();
        }

        List<Entry> entries = snapshot.entries();
        int index = normalizedLowerKey == null
                ? firstEntryForColumn(entries, column)
                : lowerBound(entries, column, normalizedLowerKey);
        List<Long> rowIds = new ArrayList<>();
        while (index < entries.size()) {
            Entry entry = entries.get(index);
            if (entry.column() != column) {
                break;
            }
            if (!withinLowerBound(entry.key(), normalizedLowerKey, lowerInclusive)) {
                index++;
                continue;
            }
            if (!withinUpperBound(entry.key(), normalizedUpperKey, upperInclusive)) {
                break;
            }
            rowIds.add(entry.rowId());
            index++;
        }
        return List.copyOf(rowIds);
    }

    public synchronized long rebuildCount() {
        return rebuildCount;
    }

    public synchronized List<String> entrySummaries() {
        return snapshot.entries().stream()
                .map(entry -> "col:" + entry.column() + "|key:" + OrderedIndexKeyCodec.display(entry.key()) + "|row:" + entry.rowId())
                .toList();
    }

    synchronized long snapshotLoadCountForTesting() {
        return snapshotLoadCount;
    }

    private Snapshot readFromVolume() throws IOException {
        DelosPageVolume volume = ensureVolumeOpen();
        List<Entry> entries = new ArrayList<>();
        long count = volume.pageCount();
        for (long pageNumber = 0L; pageNumber < count; pageNumber++) {
            DelosPage page = volume.readPage(new DelosPageId(pageNumber));
            if (page.pageType() != ORDERED_INDEX_PAGE_TYPE) {
                throw new IllegalStateException("expected MVCC ordered index page type "
                        + ORDERED_INDEX_PAGE_TYPE + ", got " + page.pageType() + " at page " + pageNumber);
            }
            for (int slot = 0; slot < page.slotCount(); slot++) {
                entries.add(decode(page.readRecord(slot)));
            }
        }
        List<Entry> sorted = sorted(entries);
        if (!entries.equals(sorted)) {
            throw new IllegalStateException("MVCC ordered index page entries are not sorted");
        }
        return new Snapshot(count, sorted);
    }

    private void installSnapshot(Snapshot installed) {
        snapshot = Objects.requireNonNull(installed, "installed");
        Set<Integer> legacyColumns = new HashSet<>();
        Set<Integer> oversizedColumns = new HashSet<>();
        for (Entry entry : installed.entries()) {
            if (Entry.isOversizedSurrogate(entry.key())) {
                oversizedColumns.add(entry.column());
            } else if (!OrderedIndexKeyCodec.isEncoded(entry.key())) {
                legacyColumns.add(entry.column());
            }
        }
        columnsWithLegacyKeys = Set.copyOf(legacyColumns);
        columnsWithOversizedKeys = Set.copyOf(oversizedColumns);
    }

    private void requireTypedLookupCompatible(int column, boolean typedLookup) {
        if (typedLookup && columnsWithLegacyKeys.contains(column)) {
            throw new UnsupportedLookupException(
                    "ordered index contains legacy untyped keys for column "
                            + column + "; full committed scan fallback is required");
        }
    }

    private void requireRangeLookupCompatible(
            int column,
            String normalizedLowerKey,
            String normalizedUpperKey) {
        if (columnsWithOversizedKeys.contains(column)
                || Entry.isOversizedSurrogate(normalizedLowerKey)
                || Entry.isOversizedSurrogate(normalizedUpperKey)) {
            throw new UnsupportedLookupException(
                    "ordered index or range bound contains an oversized surrogate key for column "
                            + column + "; full committed scan fallback is required for range lookup");
        }
    }

    private static void requireValidColumn(int column) {
        if (column < 0) {
            throw new IllegalArgumentException("ordered index column must be non-negative: " + column);
        }
    }

    private static int firstEntryForColumn(List<Entry> entries, int column) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (entries.get(middle).column() < column) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    private static int lowerBound(List<Entry> entries, int column, String key) {
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            Entry entry = entries.get(middle);
            int comparison = Integer.compare(entry.column(), column);
            if (comparison == 0) {
                comparison = compareKeys(entry.key(), key);
            }
            if (comparison < 0) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    @Override
    public synchronized void close() throws IOException {
        closeCurrentVolume();
    }

    private DelosPageVolume ensureVolumeOpen() throws IOException {
        if (pageVolume == null) {
            pageVolume = volumeFactory.open(path);
        }
        return pageVolume;
    }

    private void closeCurrentVolume() throws IOException {
        DelosPageVolume current = pageVolume;
        pageVolume = null;
        if (current != null) {
            current.close();
        }
    }

    private static void appendEncoded(DelosPageVolume volume, byte[] encoded) throws IOException {
        if (encoded.length + SLOT_OVERHEAD_BYTES > maxPayloadBytes()) {
            throw new IllegalArgumentException("ordered MVCC index entry is too large: " + encoded.length);
        }
        long count = volume.pageCount();
        DelosPage page;
        if (count == 0L) {
            page = volume.allocatePage(ORDERED_INDEX_PAGE_TYPE);
        } else {
            page = volume.readPage(new DelosPageId(count - 1L));
            if (page.pageType() != ORDERED_INDEX_PAGE_TYPE || page.freeBytes() < encoded.length + SLOT_OVERHEAD_BYTES) {
                page = volume.allocatePage(ORDERED_INDEX_PAGE_TYPE);
            }
        }
        page.appendRecord(encoded);
        volume.writePage(page);
    }

    private Path createReplacementPath() throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("ordered MVCC index path has no parent: " + path);
        }
        Files.createDirectories(parent);
        return Files.createTempFile(parent, path.getFileName() + ".rewrite-", ".tmp");
    }

    private static byte[] encode(Entry entry) {
        byte[] keyBytes = entry.key().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES
                + Short.BYTES
                + Integer.BYTES
                + Long.BYTES
                + Integer.BYTES
                + keyBytes.length);
        buffer.putInt(MAGIC);
        buffer.putShort(VERSION);
        buffer.putInt(entry.column());
        buffer.putLong(entry.rowId());
        buffer.putInt(keyBytes.length);
        buffer.put(keyBytes);
        return buffer.array();
    }

    private static Entry decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        int minimum = Integer.BYTES + Short.BYTES + Integer.BYTES + Long.BYTES + Integer.BYTES;
        if (buffer.remaining() < minimum) {
            throw new IllegalArgumentException("ordered MVCC index entry is too short: " + encoded.length);
        }
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("bad ordered MVCC index entry magic: 0x"
                    + Integer.toHexString(magic));
        }
        short version = buffer.getShort();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported ordered MVCC index entry version: " + version);
        }
        int column = buffer.getInt();
        long rowId = buffer.getLong();
        int keyLength = buffer.getInt();
        if (keyLength < 0 || keyLength != buffer.remaining()) {
            throw new IllegalArgumentException("invalid ordered MVCC index key length: " + keyLength);
        }
        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        return new Entry(column, new String(keyBytes, StandardCharsets.UTF_8), rowId);
    }

    private static List<Entry> sorted(List<Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        return entries.stream()
                .map(Objects::requireNonNull)
                .sorted(Comparator.comparingInt(Entry::column)
                        .thenComparing(Entry::key, OrderedIndexKeyCodec::compare)
                        .thenComparingLong(Entry::rowId))
                .toList();
    }

    private static int maxPayloadBytes() {
        return DelosPage.empty(new DelosPageId(0L), ORDERED_INDEX_PAGE_TYPE).freeBytes() - SLOT_OVERHEAD_BYTES;
    }

    private static boolean withinLowerBound(String key, String lowerKey, boolean inclusive) {
        if (lowerKey == null) {
            return true;
        }
        int comparison = compareKeys(key, lowerKey);
        return inclusive ? comparison >= 0 : comparison > 0;
    }

    private static boolean withinUpperBound(String key, String upperKey, boolean inclusive) {
        if (upperKey == null) {
            return true;
        }
        int comparison = compareKeys(key, upperKey);
        return inclusive ? comparison <= 0 : comparison < 0;
    }

    private static int compareKeys(String left, String right) {
        return OrderedIndexKeyCodec.compare(left, right);
    }

    public record Entry(int column, String key, long rowId) {
        private static final int OVERSIZED_PREFIX_CHARS = 128;

        public Entry {
            if (column < 0) {
                throw new IllegalArgumentException("ordered index column must be non-negative: " + column);
            }
            key = normalizeKey(Objects.requireNonNull(key, "key"));
            if (rowId <= 0L) {
                throw new IllegalArgumentException("ordered index row id must be positive: " + rowId);
            }
        }

        public static String normalizeKeyForLookup(String key) {
            return normalizeKey(Objects.requireNonNull(key, "key"));
        }

        private static String normalizeKey(String key) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            if (encodedLength(keyBytes.length) + SLOT_OVERHEAD_BYTES <= maxPayloadBytes()) {
                return key;
            }
            String prefix = key.substring(0, Math.min(key.length(), OVERSIZED_PREFIX_CHARS));
            String normalized = "<oversized:" + keyBytes.length + ":" + sha256Hex(keyBytes) + ":" + prefix + ">";
            byte[] normalizedBytes = normalized.getBytes(StandardCharsets.UTF_8);
            if (encodedLength(normalizedBytes.length) + SLOT_OVERHEAD_BYTES <= maxPayloadBytes()) {
                return normalized;
            }
            return "<oversized:" + keyBytes.length + ":" + sha256Hex(keyBytes) + ">";
        }

        private static boolean isOversizedSurrogate(String key) {
            return key != null && key.startsWith("<oversized:") && key.endsWith(">");
        }

        private static int encodedLength(int keyLength) {
            return Integer.BYTES
                    + Short.BYTES
                    + Integer.BYTES
                    + Long.BYTES
                    + Integer.BYTES
                    + keyLength;
        }

        private static String sha256Hex(byte[] bytes) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder builder = new StringBuilder(digest.length * 2);
                for (byte value : digest) {
                    builder.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                    builder.append(Character.forDigit(value & 0x0f, 16));
                }
                return builder.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required for ordered MVCC index key normalization", e);
            }
        }
    }


    /**
     * MVCC-durable interpretation of the typed key envelope.
     *
     * <p>The bridge/storage-API side owns producing typed key envelopes from
     * Derby values.  The durable page store must still be able to sort and
     * range-scan those envelopes, but it must not import Derby/iapi types from
     * the MVCC durable layer.  Keep this decoder local to the durable package
     * boundary so the static storage gate remains meaningful.</p>
     */
    private static final class OrderedIndexKeyCodec {
        private static final String ENVELOPE_PREFIX = "DOK1|";
        private static final char SEPARATOR = '|';
        private static final int KIND_OFFSET = ENVELOPE_PREFIX.length();
        private static final int PAYLOAD_OFFSET = KIND_OFFSET + 2;

        private OrderedIndexKeyCodec() {
        }

        static int compare(String left, String right) {
            EncodedKey leftKey = EncodedKey.parse(left);
            EncodedKey rightKey = EncodedKey.parse(right);
            int kindComparison = Integer.compare(leftKey.kind().order(), rightKey.kind().order());
            if (kindComparison != 0) {
                return kindComparison;
            }
            return switch (leftKey.kind()) {
                case NULL -> 0;
                case INTEGER -> compareIntegers(leftKey.payload(), rightKey.payload());
                case DECIMAL -> compareDecimals(leftKey.payload(), rightKey.payload());
                case FLOAT -> compareFloats(leftKey.payload(), rightKey.payload());
                case TEMPORAL, TEXT, LEGACY -> leftKey.payload().compareTo(rightKey.payload());
            };
        }

        static boolean isEncoded(String key) {
            return EncodedKey.hasTypedEnvelope(key);
        }

        static String display(String key) {
            if (!EncodedKey.hasTypedEnvelope(key)) {
                return key;
            }
            return EncodedKey.parse(key).payload();
        }

        private static int compareIntegers(String left, String right) {
            try {
                return new BigInteger(left).compareTo(new BigInteger(right));
            } catch (NumberFormatException e) {
                return left.compareTo(right);
            }
        }

        private static int compareDecimals(String left, String right) {
            try {
                return new BigDecimal(left).compareTo(new BigDecimal(right));
            } catch (NumberFormatException e) {
                return left.compareTo(right);
            }
        }

        private static int compareFloats(String left, String right) {
            try {
                return Double.compare(Double.parseDouble(left), Double.parseDouble(right));
            } catch (NumberFormatException e) {
                return left.compareTo(right);
            }
        }

        private enum Kind {
            NULL('N', 0),
            INTEGER('I', 1),
            DECIMAL('D', 2),
            FLOAT('F', 3),
            TEMPORAL('T', 4),
            TEXT('S', 5),
            LEGACY('L', 6);

            private final char code;
            private final int order;

            Kind(char code, int order) {
                this.code = code;
                this.order = order;
            }

            int order() {
                return order;
            }

            static Kind fromCode(char code) {
                for (Kind kind : values()) {
                    if (kind.code == code && kind != LEGACY) {
                        return kind;
                    }
                }
                throw new IllegalArgumentException("unknown ordered-index typed key kind: " + code);
            }

            static boolean isKnownCode(char code) {
                for (Kind kind : values()) {
                    if (kind.code == code && kind != LEGACY) {
                        return true;
                    }
                }
                return false;
            }
        }

        private record EncodedKey(Kind kind, String payload) {
            static EncodedKey parse(String key) {
                Objects.requireNonNull(key, "key");
                if (!key.startsWith(ENVELOPE_PREFIX)) {
                    return new EncodedKey(Kind.LEGACY, key);
                }
                if (!hasTypedEnvelope(key)) {
                    throw new IllegalArgumentException("malformed ordered-index typed key envelope: " + key);
                }
                return new EncodedKey(Kind.fromCode(key.charAt(KIND_OFFSET)), key.substring(PAYLOAD_OFFSET));
            }

            static boolean hasTypedEnvelope(String key) {
                return key != null
                        && key.startsWith(ENVELOPE_PREFIX)
                        && key.length() >= PAYLOAD_OFFSET
                        && key.charAt(KIND_OFFSET + 1) == SEPARATOR
                        && Kind.isKnownCode(key.charAt(KIND_OFFSET));
            }
        }
    }


    /** Signals a valid sidecar that cannot safely answer the requested lookup shape. */
    public static final class UnsupportedLookupException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private UnsupportedLookupException(String message) {
            super(message);
        }
    }

    public record Snapshot(long pageCount, List<Entry> entries) {
        public Snapshot {
            if (pageCount < 0L) {
                throw new IllegalArgumentException("ordered index page count must not be negative");
            }
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }
}
