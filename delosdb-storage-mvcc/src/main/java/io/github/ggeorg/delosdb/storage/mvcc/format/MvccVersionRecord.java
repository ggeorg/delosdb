package io.github.ggeorg.delosdb.storage.mvcc.format;

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
        this.header = Objects.requireNonNull(header, "header");
        this.payload = Objects.requireNonNull(payload, "payload").clone();
    }

    public MvccTupleHeader header() {
        return header;
    }

    public byte[] payload() {
        return payload.clone();
    }

    public int encodedLength() {
        return MvccVersionRecordCodec.HEADER_SIZE + payload.length;
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
