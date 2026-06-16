package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

/** Binary codec for durable MVCC row-version records. */
public final class MvccVersionRecordCodec {
    public static final int MAGIC = 0x444D5652; // "DMVR" - DelosDB MVCC version record.
    public static final short FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 64;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private MvccVersionRecordCodec() {
    }

    public static byte[] encode(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        byte[] payload = record.payload();
        byte[] bytes = new byte[Math.addExact(HEADER_SIZE, payload.length)];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        MvccTupleHeader header = record.header();

        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(header.flags());
        buffer.putInt(payload.length);
        buffer.putLong(header.rowId().value());
        buffer.putLong(header.versionId().value());
        buffer.putLong(header.previousVersionId().value());
        buffer.putLong(header.createdByTx().value());
        buffer.putLong(header.deletedByTx().value());
        buffer.putLong(header.commitSequence().value());
        buffer.put(payload);
        return bytes;
    }

    public static MvccVersionRecord decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("MVCC version record is shorter than header: " + bytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid MVCC version-record magic 0x"
                    + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
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
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("MVCC version-record length mismatch: expected "
                    + expectedLength + " bytes, found " + bytes.length);
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
        return new MvccVersionRecord(header, payload);
    }
}
