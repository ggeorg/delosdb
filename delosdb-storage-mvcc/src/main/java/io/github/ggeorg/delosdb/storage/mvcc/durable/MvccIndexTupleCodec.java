package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/** Binary codec for durable provider-owned MVCC index tuples. */
public final class MvccIndexTupleCodec {
    private static final int MAGIC = 0x444d5849; // DMXI: Delos MVCC indeX Index tuple
    private static final int FORMAT_VERSION = 1;
    private static final int FIXED_BYTES = Integer.BYTES // magic
            + Integer.BYTES // version
            + Integer.BYTES // flags
            + Long.BYTES // row id
            + Long.BYTES // version id
            + Long.BYTES // heap page id
            + Integer.BYTES // heap slot id
            + Integer.BYTES // index-name length
            + Integer.BYTES; // index-key length

    private MvccIndexTupleCodec() {
    }

    public static byte[] encode(MvccIndexTuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        byte[] indexNameBytes = tuple.indexName().getBytes(StandardCharsets.UTF_8);
        byte[] indexKey = tuple.indexKey();
        if (indexNameBytes.length == 0) {
            throw new IllegalArgumentException("index name must not be empty");
        }
        int totalBytes = Math.addExact(FIXED_BYTES, Math.addExact(indexNameBytes.length, indexKey.length));
        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        buffer.putInt(MAGIC);
        buffer.putInt(FORMAT_VERSION);
        buffer.putInt(tuple.flags());
        buffer.putLong(tuple.rowId().value());
        buffer.putLong(tuple.versionId().value());
        buffer.putLong(tuple.versionLocator().pageId().value());
        buffer.putInt(tuple.versionLocator().slotId());
        buffer.putInt(indexNameBytes.length);
        buffer.putInt(indexKey.length);
        buffer.put(indexNameBytes);
        buffer.put(indexKey);
        return buffer.array();
    }

    public static MvccIndexTuple decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < FIXED_BYTES) {
            throw new IllegalArgumentException("MVCC index tuple record is truncated: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("bad MVCC index tuple magic: 0x" + Integer.toHexString(magic));
        }
        int version = buffer.getInt();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC index tuple format version: " + version);
        }
        int flags = buffer.getInt();
        long rowId = buffer.getLong();
        long versionId = buffer.getLong();
        long pageId = buffer.getLong();
        int slotId = buffer.getInt();
        int indexNameLength = buffer.getInt();
        int indexKeyLength = buffer.getInt();
        if (indexNameLength <= 0) {
            throw new IllegalArgumentException("index name length must be positive: " + indexNameLength);
        }
        if (indexKeyLength <= 0) {
            throw new IllegalArgumentException("index key length must be positive: " + indexKeyLength);
        }
        int variableBytes = Math.addExact(indexNameLength, indexKeyLength);
        if (buffer.remaining() != variableBytes) {
            throw new IllegalArgumentException("MVCC index tuple length mismatch: remaining="
                    + buffer.remaining() + ", expected=" + variableBytes);
        }
        byte[] indexNameBytes = new byte[indexNameLength];
        buffer.get(indexNameBytes);
        byte[] indexKeyBytes = new byte[indexKeyLength];
        buffer.get(indexKeyBytes);
        String indexName = new String(indexNameBytes, StandardCharsets.UTF_8);
        return new MvccIndexTuple(
                indexName,
                indexKeyBytes,
                new MvccRowId(rowId),
                new MvccVersionId(versionId),
                new MvccVersionLocator(new MvccPageId(pageId), slotId),
                flags);
    }
}
