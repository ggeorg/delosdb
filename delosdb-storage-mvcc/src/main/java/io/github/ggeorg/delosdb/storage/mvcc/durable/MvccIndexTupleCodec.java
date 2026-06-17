package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

/** Codec for one durable index-candidate record stored inside an index page slot. */
final class MvccIndexTupleCodec {
    private static final int MAGIC = 0x44495831; // DIX1
    private static final short VERSION = 2;

    private static final short KEY_NULL = 0;
    private static final short KEY_STRING = 1;
    private static final short KEY_INTEGER = 2;
    private static final short KEY_LONG = 3;
    private static final short KEY_BYTES = 4;

    private MvccIndexTupleCodec() {
    }

    /**
     * Encodes a tuple that already owns its normalized index name and durable key bytes.
     */
    static byte[] encode(MvccIndexTuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        EncodedKey key = encodedKeyFromTuple(tuple);
        return encode(tuple, key);
    }

    /**
     * Compatibility overload used by older A6 helper code: build the durable key
     * from the supplied logical index key, but keep the row/version candidate from
     * the tuple.
     */
    static byte[] encode(Object indexKey, MvccIndexTuple tuple) {
        Objects.requireNonNull(tuple, "tuple");
        EncodedKey key = encodeKey(indexKey);
        MvccIndexTuple keyedTuple = MvccIndexTuple.active(
                tuple.indexName(), key.bytes(), tuple.rowId(), tuple.versionId(), tuple.versionLocator());
        return encode(keyedTuple, key);
    }

    static DecodedIndexTuple decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        if (buffer.remaining() < minimumRecordLength()) {
            throw new IllegalArgumentException("durable MVCC index tuple is too short: " + encoded.length);
        }
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("bad durable MVCC index tuple magic: 0x"
                    + Integer.toHexString(magic));
        }
        short version = buffer.getShort();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported durable MVCC index tuple version: " + version);
        }

        String indexName = readUtf8(buffer, "index name");
        short keyType = buffer.getShort();
        byte[] keyBytes = readBytes(buffer, "index key", Long.BYTES + Long.BYTES + Long.BYTES
                + Integer.BYTES + Integer.BYTES);
        Object indexKey = decodeKey(keyType, keyBytes);

        MvccIndexTuple tuple = new MvccIndexTuple(
                indexName,
                keyBytes,
                new MvccRowId(buffer.getLong()),
                new MvccVersionId(buffer.getLong()),
                new MvccVersionLocator(new MvccPageId(buffer.getLong()), buffer.getInt()),
                buffer.getInt());
        if (buffer.hasRemaining()) {
            throw new IllegalArgumentException("durable MVCC index tuple has trailing bytes: " + buffer.remaining());
        }
        return new DecodedIndexTuple(indexKey, tuple);
    }

    private static byte[] encode(MvccIndexTuple tuple, EncodedKey key) {
        byte[] indexNameBytes = tuple.indexName().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES
                + Short.BYTES
                + Integer.BYTES
                + indexNameBytes.length
                + Short.BYTES
                + Integer.BYTES
                + key.bytes().length
                + Long.BYTES
                + Long.BYTES
                + Long.BYTES
                + Integer.BYTES
                + Integer.BYTES);
        buffer.putInt(MAGIC);
        buffer.putShort(VERSION);
        buffer.putInt(indexNameBytes.length);
        buffer.put(indexNameBytes);
        buffer.putShort(key.type());
        buffer.putInt(key.bytes().length);
        buffer.put(key.bytes());
        buffer.putLong(tuple.rowId().value());
        buffer.putLong(tuple.versionId().value());
        buffer.putLong(tuple.versionLocator().pageId().value());
        buffer.putInt(tuple.versionLocator().slotId());
        buffer.putInt(tuple.flags());
        return buffer.array();
    }

    private static int minimumRecordLength() {
        return Integer.BYTES // magic
                + Short.BYTES // version
                + Integer.BYTES // index name length
                + Short.BYTES // key type
                + Integer.BYTES // key length
                + Long.BYTES // row id
                + Long.BYTES // version id
                + Long.BYTES // locator page id
                + Integer.BYTES // locator slot id
                + Integer.BYTES; // flags
    }

    private static String readUtf8(ByteBuffer buffer, String fieldName) {
        byte[] bytes = readBytes(buffer, fieldName, Short.BYTES + Integer.BYTES + Long.BYTES
                + Long.BYTES + Long.BYTES + Integer.BYTES + Integer.BYTES);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(ByteBuffer buffer, String fieldName, int requiredTailBytes) {
        if (buffer.remaining() < Integer.BYTES + requiredTailBytes) {
            throw new IllegalArgumentException("durable MVCC index tuple is missing " + fieldName + " length");
        }
        int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining() - requiredTailBytes) {
            throw new IllegalArgumentException("invalid durable MVCC " + fieldName + " length: " + length);
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    private static EncodedKey encodedKeyFromTuple(MvccIndexTuple tuple) {
        byte[] keyBytes = tuple.indexKey();
        return new EncodedKey(KEY_BYTES, keyBytes);
    }

    private static EncodedKey encodeKey(Object indexKey) {
        if (indexKey == null) {
            return new EncodedKey(KEY_NULL, new byte[0]);
        }
        if (indexKey instanceof String value) {
            return new EncodedKey(KEY_STRING, value.getBytes(StandardCharsets.UTF_8));
        }
        if (indexKey instanceof Integer value) {
            return new EncodedKey(KEY_INTEGER, ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
        }
        if (indexKey instanceof Long value) {
            return new EncodedKey(KEY_LONG, ByteBuffer.allocate(Long.BYTES).putLong(value).array());
        }
        if (indexKey instanceof byte[] value) {
            return new EncodedKey(KEY_BYTES, value.clone());
        }
        throw new IllegalArgumentException("unsupported durable MVCC index key type: "
                + indexKey.getClass().getName());
    }

    private static Object decodeKey(short keyType, byte[] keyBytes) {
        return switch (keyType) {
            case KEY_NULL -> {
                if (keyBytes.length != 0) {
                    throw new IllegalArgumentException("null durable MVCC index key must have zero bytes");
                }
                yield null;
            }
            case KEY_STRING -> new String(keyBytes, StandardCharsets.UTF_8);
            case KEY_INTEGER -> {
                if (keyBytes.length != Integer.BYTES) {
                    throw new IllegalArgumentException("integer durable MVCC index key has invalid length: "
                            + keyBytes.length);
                }
                yield ByteBuffer.wrap(keyBytes).getInt();
            }
            case KEY_LONG -> {
                if (keyBytes.length != Long.BYTES) {
                    throw new IllegalArgumentException("long durable MVCC index key has invalid length: "
                            + keyBytes.length);
                }
                yield ByteBuffer.wrap(keyBytes).getLong();
            }
            case KEY_BYTES -> keyBytes.clone();
            default -> throw new IllegalArgumentException("unsupported durable MVCC index key type: " + keyType);
        };
    }

    record DecodedIndexTuple(Object indexKey, MvccIndexTuple tuple) {
        DecodedIndexTuple {
            tuple = Objects.requireNonNull(tuple, "tuple");
        }
    }

    private record EncodedKey(short type, byte[] bytes) {
        private EncodedKey {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
        }
    }
}
