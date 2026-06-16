package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/**
 * Durable provider-owned MVCC index tuple.
 *
 * <p>This is deliberately a candidate pointer, not a visibility decision. The
 * tuple stores an index key and points back to a durable heap row/version
 * candidate. A lookup must still visit the version chain and evaluate snapshot
 * visibility before returning a row. That mirrors the PostgreSQL-style rule we
 * want in DelosDB: index entries narrow the search; heap/version visibility is
 * authoritative.</p>
 */
public record MvccIndexTuple(
        String indexName,
        byte[] indexKey,
        MvccRowId rowId,
        MvccVersionId versionId,
        MvccVersionLocator versionLocator,
        int flags) {
    public static final int DELETE_CANDIDATE = 1;

    public MvccIndexTuple {
        indexName = normalizeIndexName(indexName);
        indexKey = requireIndexKey(indexKey).clone();
        rowId = Objects.requireNonNull(rowId, "rowId");
        versionId = Objects.requireNonNull(versionId, "versionId");
        versionLocator = Objects.requireNonNull(versionLocator, "versionLocator");
        if (rowId.isNone()) {
            throw new IllegalArgumentException("index tuple row id must not be NONE");
        }
        if (versionId.isNone()) {
            throw new IllegalArgumentException("index tuple version id must not be NONE");
        }
        if ((flags & ~DELETE_CANDIDATE) != 0) {
            throw new IllegalArgumentException("unknown MVCC index tuple flags: " + flags);
        }
    }

    public static MvccIndexTuple forStringKey(
            String indexName,
            String indexKey,
            MvccRowId rowId,
            MvccVersionId versionId,
            MvccVersionLocator versionLocator) {
        return new MvccIndexTuple(
                indexName,
                Objects.requireNonNull(indexKey, "indexKey").getBytes(StandardCharsets.UTF_8),
                rowId,
                versionId,
                versionLocator,
                0);
    }

    public boolean deleteCandidate() {
        return (flags & DELETE_CANDIDATE) != 0;
    }

    public String indexKeyAsUtf8() {
        return new String(indexKey, StandardCharsets.UTF_8);
    }

    public boolean hasIndexKey(byte[] expectedKey) {
        return Arrays.equals(indexKey, requireIndexKey(expectedKey));
    }

    @Override
    public byte[] indexKey() {
        return indexKey.clone();
    }


    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccIndexTuple tuple)) {
            return false;
        }
        return flags == tuple.flags
                && indexName.equals(tuple.indexName)
                && Arrays.equals(indexKey, tuple.indexKey)
                && rowId.equals(tuple.rowId)
                && versionId.equals(tuple.versionId)
                && versionLocator.equals(tuple.versionLocator);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(indexName, rowId, versionId, versionLocator, flags);
        result = 31 * result + Arrays.hashCode(indexKey);
        return result;
    }

    private static String normalizeIndexName(String indexName) {
        String normalized = Objects.requireNonNull(indexName, "indexName").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("index name must not be blank");
        }
        return normalized.toUpperCase(java.util.Locale.ROOT);
    }

    private static byte[] requireIndexKey(byte[] indexKey) {
        Objects.requireNonNull(indexKey, "indexKey");
        if (indexKey.length == 0) {
            throw new IllegalArgumentException("index key must not be empty");
        }
        return indexKey;
    }
}
