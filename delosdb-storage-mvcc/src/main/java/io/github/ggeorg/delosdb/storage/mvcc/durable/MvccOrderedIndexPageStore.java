package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

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
    private long rebuildCount;

    private MvccOrderedIndexPageStore(Path path, DelosPageVolumeFactory volumeFactory, DelosPageVolume pageVolume) {
        this.path = Objects.requireNonNull(path, "path");
        this.volumeFactory = Objects.requireNonNull(volumeFactory, "volumeFactory");
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
    }

    public static MvccOrderedIndexPageStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        MvccOrderedIndexPageStore store = new MvccOrderedIndexPageStore(
                path, FILE_VOLUME_FACTORY, FILE_VOLUME_FACTORY.open(path));
        store.read();
        return store;
    }

    public synchronized Path path() {
        return path;
    }

    public synchronized boolean exists() {
        return Files.exists(path);
    }

    public synchronized void rewrite(List<Entry> entries) throws IOException {
        List<Entry> sorted = sorted(entries);
        pageVolume.close();
        Files.deleteIfExists(path);
        pageVolume = volumeFactory.open(path);
        for (Entry entry : sorted) {
            appendEncoded(encode(entry));
        }
        pageVolume.force();
        rebuildCount++;
    }

    public synchronized Snapshot read() throws IOException {
        List<Entry> entries = new ArrayList<>();
        long count = pageVolume.pageCount();
        for (long pageNumber = 0L; pageNumber < count; pageNumber++) {
            DelosPage page = pageVolume.readPage(new DelosPageId(pageNumber));
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

    public synchronized long pageCount() throws IOException {
        return pageVolume.pageCount();
    }

    public synchronized long entryCount() throws IOException {
        return read().entries().size();
    }

    public synchronized int distinctKeyCount() throws IOException {
        List<String> distinct = new ArrayList<>();
        for (Entry entry : read().entries()) {
            String key = entry.column() + "|" + entry.key();
            if (!distinct.contains(key)) {
                distinct.add(key);
            }
        }
        return distinct.size();
    }

    public synchronized List<Long> rowIdsFor(int column, String key) throws IOException {
        if (column < 0) {
            throw new IllegalArgumentException("ordered index column must be non-negative: " + column);
        }
        String normalizedKey = Entry.normalizeKeyForLookup(Objects.requireNonNull(key, "key"));
        boolean typedLookup = OrderedIndexKeyCodec.isEncoded(normalizedKey);
        List<Long> rowIds = new ArrayList<>();
        for (Entry entry : read().entries()) {
            if (entry.column() != column) {
                continue;
            }
            if (typedLookup && !OrderedIndexKeyCodec.isEncoded(entry.key())) {
                throw new IllegalStateException("ordered index contains legacy untyped keys for column "
                        + column + "; full committed scan fallback is required");
            }
            if (entry.key().equals(normalizedKey)) {
                rowIds.add(entry.rowId());
            }
        }
        return List.copyOf(rowIds);
    }

    public synchronized List<Long> rowIdsInRangeFor(
            int column,
            String lowerKey,
            boolean lowerInclusive,
            String upperKey,
            boolean upperInclusive) throws IOException {
        if (column < 0) {
            throw new IllegalArgumentException("ordered index column must be non-negative: " + column);
        }
        String normalizedLowerKey = lowerKey == null ? null : Entry.normalizeKeyForLookup(lowerKey);
        String normalizedUpperKey = upperKey == null ? null : Entry.normalizeKeyForLookup(upperKey);
        if (normalizedLowerKey != null && normalizedUpperKey != null
                && compareKeys(normalizedLowerKey, normalizedUpperKey) > 0) {
            return List.of();
        }
        boolean typedLookup = OrderedIndexKeyCodec.isEncoded(normalizedLowerKey)
                || OrderedIndexKeyCodec.isEncoded(normalizedUpperKey);
        List<Long> rowIds = new ArrayList<>();
        for (Entry entry : read().entries()) {
            if (entry.column() != column) {
                continue;
            }
            if (typedLookup && !OrderedIndexKeyCodec.isEncoded(entry.key())) {
                throw new IllegalStateException("ordered index contains legacy untyped keys for column "
                        + column + "; full committed scan fallback is required");
            }
            if (!withinLowerBound(entry.key(), normalizedLowerKey, lowerInclusive)) {
                continue;
            }
            if (!withinUpperBound(entry.key(), normalizedUpperKey, upperInclusive)) {
                continue;
            }
            rowIds.add(entry.rowId());
        }
        return List.copyOf(rowIds);
    }

    public synchronized long rebuildCount() {
        return rebuildCount;
    }

    public synchronized List<String> entrySummaries() throws IOException {
        return read().entries().stream()
                .map(entry -> "col:" + entry.column() + "|key:" + OrderedIndexKeyCodec.display(entry.key()) + "|row:" + entry.rowId())
                .toList();
    }

    @Override
    public synchronized void close() throws IOException {
        pageVolume.close();
    }

    private void appendEncoded(byte[] encoded) throws IOException {
        if (encoded.length + SLOT_OVERHEAD_BYTES > maxPayloadBytes()) {
            throw new IllegalArgumentException("ordered MVCC index entry is too large: " + encoded.length);
        }
        long count = pageVolume.pageCount();
        DelosPage page;
        if (count == 0L) {
            page = pageVolume.allocatePage(ORDERED_INDEX_PAGE_TYPE);
        } else {
            page = pageVolume.readPage(new DelosPageId(count - 1L));
            if (page.pageType() != ORDERED_INDEX_PAGE_TYPE || page.freeBytes() < encoded.length + SLOT_OVERHEAD_BYTES) {
                page = pageVolume.allocatePage(ORDERED_INDEX_PAGE_TYPE);
            }
        }
        page.appendRecord(encoded);
        pageVolume.writePage(page);
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
        private static final char SEPARATOR = '|';

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
                    if (kind.code == code) {
                        return kind;
                    }
                }
                return LEGACY;
            }
        }

        private record EncodedKey(Kind kind, String payload) {
            static EncodedKey parse(String key) {
                Objects.requireNonNull(key, "key");
                if (!hasTypedEnvelope(key)) {
                    return new EncodedKey(Kind.LEGACY, key);
                }
                return new EncodedKey(Kind.fromCode(key.charAt(0)), key.substring(2));
            }

            static boolean hasTypedEnvelope(String key) {
                return key != null && key.length() >= 2 && key.charAt(1) == SEPARATOR;
            }
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
