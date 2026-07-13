package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Durable physical MVCC row-version record.
 *
 * <p>The payload is intentionally opaque in Phase A2. SQL row encoding belongs
 * to a later page-backed table layer; this record only guarantees a stable
 * version header plus bytes that can be stored in an MVCC page.</p>
 */
public final class MvccVersionRecord {
    private final MvccTupleHeader header;
    private final byte[] payload;

    public MvccVersionRecord(MvccTupleHeader header, byte[] payload) {
        this(header, payload, false);
    }

    private MvccVersionRecord(MvccTupleHeader header, byte[] payload, boolean ownsPayload) {
        this.header = Objects.requireNonNull(header, "header");
        byte[] requiredPayload = Objects.requireNonNull(payload, "payload");
        this.payload = ownsPayload ? requiredPayload : requiredPayload.clone();
    }

    /**
     * Creates a record that takes ownership of a freshly decoded payload array.
     *
     * <p>This is intentionally package-private and reserved for trusted format
     * codecs. Public construction and access continue to copy bytes so callers
     * cannot mutate a durable record through an aliased array.</p>
     */
    static MvccVersionRecord fromOwnedPayload(MvccTupleHeader header, byte[] payload) {
        return new MvccVersionRecord(header, payload, true);
    }

    public MvccTupleHeader header() {
        return header;
    }

    public byte[] payload() {
        return payload.clone();
    }

    /** Writes the immutable record-owned payload without exposing its array. */
    void writePayloadTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target").put(payload);
    }

    int payloadLength() {
        return payload.length;
    }

    public int encodedLength() {
        return Math.addExact(MvccVersionRecordCodec.HEADER_SIZE, payload.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MvccVersionRecord that)) {
            return false;
        }
        return header.equals(that.header) && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        int result = header.hashCode();
        result = 31 * result + Arrays.hashCode(payload);
        return result;
    }

    @Override
    public String toString() {
        return "MvccVersionRecord[header=" + header + ", payloadBytes=" + payload.length + ']';
    }
}
