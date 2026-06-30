package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Small version-record payload used when the real row payload is stored in
 * MVCC overflow pages.
 */
public final class MvccOverflowPayloadReferenceCodec {
    public static final int MAGIC = 0x444D5658; // "DMVX" - DelosDB MVCC overflow reference.
    public static final short FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 20;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private MvccOverflowPayloadReferenceCodec() {
    }

    public static boolean isOverflowReference(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < Integer.BYTES) {
            return false;
        }
        return ByteBuffer.wrap(bytes, 0, Integer.BYTES).order(BYTE_ORDER).getInt() == MAGIC;
    }

    public static byte[] encode(Reference reference) {
        Objects.requireNonNull(reference, "reference");
        byte[] keyBytes = reference.key().getBytes(StandardCharsets.UTF_8);
        byte[] descriptorBytes = MvccOverflowPayloadCodec.encodeDescriptor(reference.descriptor());
        byte[] encoded = new byte[Math.addExact(
                HEADER_SIZE,
                Math.addExact(keyBytes.length, descriptorBytes.length))];
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(BYTE_ORDER);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(keyBytes.length);
        buffer.putInt(descriptorBytes.length);
        buffer.putInt(0); // reserved flags.
        buffer.put(keyBytes);
        buffer.put(descriptorBytes);
        return encoded;
    }

    public static Reference decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("MVCC overflow reference is shorter than header: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid MVCC overflow reference magic 0x"
                    + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC overflow reference format version "
                    + version + ", expected " + FORMAT_VERSION);
        }
        int headerSize = Short.toUnsignedInt(buffer.getShort());
        if (headerSize != HEADER_SIZE) {
            throw new IllegalArgumentException("unsupported MVCC overflow reference header size "
                    + headerSize + ", expected " + HEADER_SIZE);
        }
        int keyLength = buffer.getInt();
        int descriptorLength = buffer.getInt();
        int flags = buffer.getInt();
        if (keyLength <= 0) {
            throw new IllegalArgumentException("MVCC overflow reference key length must be positive: " + keyLength);
        }
        if (descriptorLength != MvccOverflowPayloadCodec.DESCRIPTOR_SIZE) {
            throw new IllegalArgumentException("MVCC overflow reference descriptor length mismatch: expected "
                    + MvccOverflowPayloadCodec.DESCRIPTOR_SIZE + ", found " + descriptorLength);
        }
        if (flags != 0) {
            throw new IllegalArgumentException("unsupported non-zero MVCC overflow reference flags: " + flags);
        }
        int expectedLength = Math.addExact(HEADER_SIZE, Math.addExact(keyLength, descriptorLength));
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("MVCC overflow reference length mismatch: expected "
                    + expectedLength + " bytes, found " + bytes.length);
        }
        byte[] keyBytes = new byte[keyLength];
        byte[] descriptorBytes = new byte[descriptorLength];
        buffer.get(keyBytes);
        buffer.get(descriptorBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        return new Reference(key, MvccOverflowPayloadCodec.decodeDescriptor(descriptorBytes));
    }

    public record Reference(String key, MvccOverflowPayloadDescriptor descriptor) {
        public Reference {
            key = MvccRowPayload.requireKey(key);
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            if (descriptor.chunkCount() == 0) {
                throw new IllegalArgumentException("overflow reference must point to a non-empty overflow chain");
            }
        }
    }
}
