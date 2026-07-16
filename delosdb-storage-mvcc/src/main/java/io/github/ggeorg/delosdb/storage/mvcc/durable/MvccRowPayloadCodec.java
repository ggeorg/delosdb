package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Codec for the durable table payload stored inside {@code MvccVersionRecord}. */
public final class MvccRowPayloadCodec {
    public static final int MAGIC = 0x444D5250; // "DMRP" - DelosDB MVCC row payload.
    public static final short FORMAT_VERSION = 1;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;
    private static final int HEADER_SIZE = 16;

    private MvccRowPayloadCodec() {
    }

    public static byte[] encode(MvccRowPayload payload) {
        Objects.requireNonNull(payload, "payload");
        byte[] keyBytes = payload.key().getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = payload.value();
        byte[] encoded = new byte[Math.addExact(HEADER_SIZE, Math.addExact(keyBytes.length, valueBytes.length))];
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(BYTE_ORDER);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) 0);
        buffer.putInt(keyBytes.length);
        buffer.putInt(valueBytes.length);
        buffer.put(keyBytes);
        buffer.put(valueBytes);
        return encoded;
    }

    public static MvccRowPayload decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("MVCC row payload is shorter than header: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid MVCC row-payload magic 0x"
                    + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC row-payload format version "
                    + version + ", expected " + FORMAT_VERSION);
        }
        buffer.getShort(); // reserved
        int keyLength = buffer.getInt();
        int valueLength = buffer.getInt();
        if (keyLength <= 0) {
            throw new IllegalArgumentException("MVCC row-payload key length must be positive: " + keyLength);
        }
        if (valueLength < 0) {
            throw new IllegalArgumentException("MVCC row-payload value length must be non-negative: " + valueLength);
        }
        int expectedLength = Math.addExact(HEADER_SIZE, Math.addExact(keyLength, valueLength));
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("MVCC row-payload length mismatch: expected "
                    + expectedLength + " bytes, found " + bytes.length);
        }
        byte[] keyBytes = new byte[keyLength];
        byte[] valueBytes = new byte[valueLength];
        buffer.get(keyBytes);
        buffer.get(valueBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        return new MvccRowPayload(key, valueBytes);
    }
}
