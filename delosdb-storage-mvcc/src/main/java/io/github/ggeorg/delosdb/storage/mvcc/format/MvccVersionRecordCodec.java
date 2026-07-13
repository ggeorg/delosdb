package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

/** Binary codec for durable MVCC row-version records. */
public final class MvccVersionRecordCodec {
    public static final int MAGIC = 0x444D5652; // "DMVR" - DelosDB MVCC version record.
    public static final short FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 64;

    private MvccVersionRecordCodec() {
    }

    public static byte[] encode(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] bytes = new byte[record.encodedLength()];
        encodeInto(record, MvccBinaryFormat.wrap(bytes));
        return bytes;
    }

    /** Writes one complete version record at the buffer's current position. */
    static void encodeInto(MvccVersionRecord record, ByteBuffer buffer) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(buffer, "buffer");
        int payloadLength = record.payloadLength();
        int required = Math.addExact(HEADER_SIZE, payloadLength);
        if (buffer.remaining() < required) {
            throw new IllegalArgumentException("insufficient target space for MVCC version-record: required "
                    + required + " bytes, found " + buffer.remaining());
        }
        MvccTupleHeader header = record.header();

        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(header.flags());
        buffer.putInt(payloadLength);
        buffer.putLong(header.rowId().value());
        buffer.putLong(header.versionId().value());
        buffer.putLong(header.previousVersionId().value());
        buffer.putLong(header.createdByTx().value());
        buffer.putLong(header.deletedByTx().value());
        buffer.putLong(header.commitSequence().value());
        record.writePayloadTo(buffer);
    }

    public static MvccVersionRecord decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return decode(bytes, 0, bytes.length);
    }

    /** Decodes one version record from a validated byte-array slice. */
    static MvccVersionRecord decode(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        requireSlice(bytes, offset, length);
        if (length < HEADER_SIZE) {
            throw new IllegalArgumentException("MVCC version-record is shorter than header: " + length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length).slice().order(MvccBinaryFormat.BYTE_ORDER);
        int actualMagic = buffer.getInt();
        if (actualMagic != MAGIC) {
            throw new IllegalArgumentException("invalid MVCC version-record magic 0x"
                    + Integer.toHexString(actualMagic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC version-record format version "
                    + version + ", expected " + FORMAT_VERSION);
        }
        int headerSize = Short.toUnsignedInt(buffer.getShort());
        if (headerSize != HEADER_SIZE) {
            throw new IllegalArgumentException("unsupported MVCC version-record header size "
                    + headerSize + ", expected " + HEADER_SIZE);
        }

        int flags = buffer.getInt();
        MvccVersionRecordFlags.validate(flags);
        int payloadLength = buffer.getInt();
        if (payloadLength < 0) {
            throw new IllegalArgumentException("negative MVCC version-record payload length: " + payloadLength);
        }
        int expectedLength = Math.addExact(HEADER_SIZE, payloadLength);
        if (length != expectedLength) {
            throw new IllegalArgumentException("MVCC version-record length mismatch: expected "
                    + expectedLength + " bytes, found " + length);
        }

        MvccTupleHeader header = new MvccTupleHeader(
                new MvccRowId(buffer.getLong()),
                new MvccVersionId(buffer.getLong()),
                new MvccVersionId(buffer.getLong()),
                new MvccTransactionId(buffer.getLong()),
                new MvccTransactionId(buffer.getLong()),
                new MvccCommitSequence(buffer.getLong()),
                flags);
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        return MvccVersionRecord.fromOwnedPayload(header, payload);
    }

    private static void requireSlice(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IndexOutOfBoundsException("invalid MVCC version-record slice: offset="
                    + offset + ", length=" + length + ", arrayLength=" + bytes.length);
        }
    }
}
