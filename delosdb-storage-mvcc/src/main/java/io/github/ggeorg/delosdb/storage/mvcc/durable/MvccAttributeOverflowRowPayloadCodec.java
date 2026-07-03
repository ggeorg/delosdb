package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccBinaryFormat;

/**
 * Row-payload codec for an MVCC row whose value attribute is stored in overflow
 * chunks while the row key and overflow descriptor remain inline on the row page.
 */
public final class MvccAttributeOverflowRowPayloadCodec {
    public static final int MAGIC = 0x444D5241; // "DMRA" - DelosDB MVCC row attribute overflow.
    public static final short FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 28;
    public static final int DESCRIPTOR_SIZE = MvccOverflowPayloadCodec.DESCRIPTOR_SIZE;

    private MvccAttributeOverflowRowPayloadCodec() {
    }

    public static byte[] encode(String key, long valueLength, MvccOverflowPayloadDescriptor descriptor) {
        key = MvccRowPayload.requireKey(key);
        Objects.requireNonNull(descriptor, "descriptor");
        if (valueLength < 0L) {
            throw new IllegalArgumentException("valueLength must not be negative: " + valueLength);
        }
        if (descriptor.totalLength() != valueLength) {
            throw new IllegalArgumentException("attribute overflow descriptor length " + descriptor.totalLength()
                    + " does not match value length " + valueLength);
        }
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] descriptorBytes = MvccOverflowPayloadCodec.encodeDescriptor(descriptor);
        byte[] encoded = new byte[Math.addExact(HEADER_SIZE, Math.addExact(keyBytes.length, descriptorBytes.length))];
        ByteBuffer buffer = MvccBinaryFormat.wrap(encoded);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(keyBytes.length);
        buffer.putLong(valueLength);
        buffer.putInt(descriptorBytes.length);
        buffer.putInt(0); // reserved flags.
        buffer.put(keyBytes);
        buffer.put(descriptorBytes);
        return encoded;
    }

    public static Reference decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        ByteBuffer buffer = MvccBinaryFormat.requireHeader(
                bytes,
                HEADER_SIZE,
                MAGIC,
                FORMAT_VERSION,
                HEADER_SIZE,
                "MVCC attribute-overflow row payload");
        int keyLength = buffer.getInt();
        long valueLength = buffer.getLong();
        int descriptorLength = buffer.getInt();
        int flags = buffer.getInt();
        if (keyLength <= 0) {
            throw new IllegalArgumentException("MVCC attribute-overflow key length must be positive: " + keyLength);
        }
        if (valueLength < 0L) {
            throw new IllegalArgumentException("MVCC attribute-overflow value length must not be negative: " + valueLength);
        }
        if (descriptorLength != DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException("MVCC attribute-overflow descriptor length mismatch: expected "
                    + DESCRIPTOR_SIZE + ", found " + descriptorLength);
        }
        if (flags != 0) {
            throw new IllegalArgumentException("unsupported MVCC attribute-overflow flags 0x"
                    + Integer.toHexString(flags));
        }
        int expectedLength = Math.addExact(HEADER_SIZE, Math.addExact(keyLength, descriptorLength));
        MvccBinaryFormat.requireExactLength(bytes, expectedLength, "MVCC attribute-overflow row payload");
        byte[] keyBytes = new byte[keyLength];
        buffer.get(keyBytes);
        byte[] descriptorBytes = new byte[descriptorLength];
        buffer.get(descriptorBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        MvccOverflowPayloadDescriptor descriptor = MvccOverflowPayloadCodec.decodeDescriptor(descriptorBytes);
        if (descriptor.totalLength() != valueLength) {
            throw new IllegalArgumentException("MVCC attribute-overflow descriptor length "
                    + descriptor.totalLength() + " does not match value length " + valueLength);
        }
        return new Reference(key, valueLength, descriptor);
    }

    public static boolean isAttributeOverflowPayload(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return MvccBinaryFormat.hasMagic(bytes, MAGIC);
    }

    public static int encodedLengthForKey(String key) {
        byte[] keyBytes = MvccRowPayload.requireKey(key).getBytes(StandardCharsets.UTF_8);
        return Math.addExact(HEADER_SIZE, Math.addExact(keyBytes.length, DESCRIPTOR_SIZE));
    }

    public record Reference(String key, long valueLength, MvccOverflowPayloadDescriptor descriptor) {
        public Reference {
            key = MvccRowPayload.requireKey(key);
            if (valueLength < 0L) {
                throw new IllegalArgumentException("valueLength must not be negative: " + valueLength);
            }
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }
    }
}
