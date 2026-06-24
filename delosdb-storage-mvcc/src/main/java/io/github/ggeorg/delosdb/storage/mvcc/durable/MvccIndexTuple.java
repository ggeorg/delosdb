package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

/**
 * Durable index candidate pointing back to versioned heap storage.
 *
 * <p>The tuple intentionally carries no visibility decision. A durable MVCC
 * index returns candidates only; the page-backed table/version directory remains
 * the authority for snapshot visibility and for deciding whether the currently
 * visible row value still matches the indexed key.</p>
 */
public record MvccIndexTuple(
        String indexName,
        byte[] indexKey,
        MvccRowId rowId,
        MvccVersionId versionId,
        MvccVersionLocator versionLocator,
        int flags) {
    public static final int ACTIVE = 1;
    public static final int ACTIVE_FLAG = ACTIVE;

    private static final int KNOWN_FLAGS = ACTIVE;
    private static final String UNKNOWN_INDEX_NAME = "";
    private static final byte[] UNKNOWN_INDEX_KEY = new byte[0];
    private static final MvccVersionLocator UNKNOWN_VERSION_LOCATOR =
            new MvccVersionLocator(new DelosPageId(0L), 0);

    public MvccIndexTuple {
        indexName = normalizeIndexName(indexName);
        indexKey = Objects.requireNonNull(indexKey, "indexKey").clone();
        rowId = Objects.requireNonNull(rowId, "rowId");
        versionId = Objects.requireNonNull(versionId, "versionId");
        versionLocator = Objects.requireNonNull(versionLocator, "versionLocator");
        if (versionId.equals(MvccVersionId.NONE)) {
            throw new IllegalArgumentException("index tuple must point to a concrete version id");
        }
        if ((flags & ~KNOWN_FLAGS) != 0) {
            throw new IllegalArgumentException("unsupported durable MVCC index tuple flags: " + flags);
        }
    }

    public MvccIndexTuple(MvccRowId rowId, MvccVersionId versionId, int flags) {
        this(UNKNOWN_INDEX_NAME, UNKNOWN_INDEX_KEY, rowId, versionId, UNKNOWN_VERSION_LOCATOR, flags);
    }

    public MvccIndexTuple(MvccRowId rowId, MvccVersionId versionId) {
        this(rowId, versionId, ACTIVE);
    }

    public static MvccIndexTuple active(MvccRowId rowId, MvccVersionId versionId) {
        return new MvccIndexTuple(rowId, versionId, ACTIVE);
    }

    public static MvccIndexTuple active(
            MvccRowId rowId,
            MvccVersionId versionId,
            MvccVersionLocator versionLocator) {
        return new MvccIndexTuple(UNKNOWN_INDEX_NAME, UNKNOWN_INDEX_KEY, rowId, versionId, versionLocator, ACTIVE);
    }

    public static MvccIndexTuple active(
            String indexName,
            byte[] indexKey,
            MvccRowId rowId,
            MvccVersionId versionId,
            MvccVersionLocator versionLocator) {
        return new MvccIndexTuple(indexName, indexKey, rowId, versionId, versionLocator, ACTIVE);
    }

    public static MvccIndexTuple forStringKey(
            String indexName,
            String indexKey,
            MvccRowId rowId,
            MvccVersionId versionId,
            MvccVersionLocator versionLocator) {
        return active(indexName, stringBytes(indexKey), rowId, versionId, versionLocator);
    }

    public static MvccIndexTuple of(MvccRowId rowId, MvccVersionId versionId) {
        return active(rowId, versionId);
    }

    public static MvccIndexTuple of(MvccRowId rowId, MvccVersionId versionId, int flags) {
        return new MvccIndexTuple(rowId, versionId, flags);
    }

    public byte[] indexKey() {
        return indexKey.clone();
    }

    public String indexKeyAsUtf8() {
        return new String(indexKey, StandardCharsets.UTF_8);
    }

    public boolean isActive() {
        return (flags & ACTIVE) != 0;
    }

    private static String normalizeIndexName(String value) {
        return Objects.requireNonNull(value, "indexName").toUpperCase(Locale.ROOT);
    }

    private static byte[] stringBytes(String value) {
        return Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccIndexTuple that)) {
            return false;
        }
        return flags == that.flags
                && indexName.equals(that.indexName)
                && Arrays.equals(indexKey, that.indexKey)
                && rowId.equals(that.rowId)
                && versionId.equals(that.versionId)
                && versionLocator.equals(that.versionLocator);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(indexName, rowId, versionId, versionLocator, flags);
        result = 31 * result + Arrays.hashCode(indexKey);
        return result;
    }

    @Override
    public String toString() {
        return "MvccIndexTuple["
                + "indexName=" + indexName
                + ", indexKeyLength=" + indexKey.length
                + ", rowId=" + rowId
                + ", versionId=" + versionId
                + ", versionLocator=" + versionLocator
                + ", flags=" + flags
                + ']';
    }
}
