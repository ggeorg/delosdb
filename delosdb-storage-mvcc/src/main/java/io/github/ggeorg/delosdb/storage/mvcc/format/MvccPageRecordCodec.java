package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Slot-record header codec for MVCC page records.
 *
 * <p>The lower Delos page layer already owns page headers and the slot
 * directory. This header is the MVCC-owned record boundary inside each active
 * slot. It deliberately wraps the existing {@link MvccVersionRecordCodec}
 * payload instead of changing tuple visibility semantics in the same step.</p>
 */
public final class MvccPageRecordCodec {
    public static final int MAGIC = 0x444D5052; // "DMPR" - DelosDB MVCC page record.
    public static final short FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 24;
    public static final int RECORD_TYPE_VERSION = 1;
    public static final int FLAGS_NONE = 0;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private MvccPageRecordCodec() {
    }

    public static byte[] encodeVersionRecord(MvccVersionRecord record) {
        byte[] body = MvccVersionRecordCodec.encode(Objects.requireNonNull(record, "record"));
        byte[] bytes = new byte[Math.addExact(HEADER_SIZE, body.length)];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(RECORD_TYPE_VERSION);
        buffer.putInt(FLAGS_NONE);
        buffer.putInt(body.length);
        buffer.putInt(checksum(body));
        buffer.put(body);
        return bytes;
    }

    public static PageRecord decode(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (!isPageRecord(bytes)) {
            MvccVersionRecord legacy = MvccVersionRecordCodec.decode(bytes);
            return new PageRecord(PageRecordMetadata.legacyVersionRecord(bytes.length), legacy);
        }

        DecodedHeader header = decodeHeader(bytes);
        if (header.metadata().recordType() != RECORD_TYPE_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC page-record type "
                    + header.metadata().recordType());
        }
        return new PageRecord(header.metadata(), MvccVersionRecordCodec.decode(header.body()));
    }

    public static MvccVersionRecord decodeVersionRecord(byte[] bytes) {
        return decode(bytes).versionRecord();
    }

    public static PageRecordMetadata metadata(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (!isPageRecord(bytes)) {
            return PageRecordMetadata.legacyVersionRecord(bytes.length);
        }
        return decodeHeader(bytes).metadata();
    }

    public static int encodedLength(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        return Math.addExact(HEADER_SIZE, record.encodedLength());
    }

    public static boolean isPageRecord(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < Integer.BYTES) {
            return false;
        }
        return ByteBuffer.wrap(bytes).order(BYTE_ORDER).getInt(0) == MAGIC;
    }

    private static DecodedHeader decodeHeader(byte[] bytes) {
        if (bytes.length < HEADER_SIZE) {
            throw new IllegalArgumentException("MVCC page record is shorter than header: " + bytes.length);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("invalid MVCC page-record magic 0x"
                    + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort();
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported MVCC page-record format version "
                    + version + ", expected " + FORMAT_VERSION);
        }
        int headerSize = Short.toUnsignedInt(buffer.getShort());
        if (headerSize != HEADER_SIZE) {
            throw new IllegalArgumentException("unsupported MVCC page-record header size "
                    + headerSize + ", expected " + HEADER_SIZE);
        }
        int recordType = buffer.getInt();
        int flags = buffer.getInt();
        if (flags != FLAGS_NONE) {
            throw new IllegalArgumentException("unsupported MVCC page-record flags 0x"
                    + Integer.toHexString(flags));
        }
        int bodyLength = buffer.getInt();
        if (bodyLength < 0) {
            throw new IllegalArgumentException("negative MVCC page-record body length: " + bodyLength);
        }
        int bodyChecksum = buffer.getInt();
        int expectedLength = Math.addExact(HEADER_SIZE, bodyLength);
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("MVCC page-record length mismatch: expected "
                    + expectedLength + " bytes, found " + bytes.length);
        }
        byte[] body = new byte[bodyLength];
        buffer.get(body);
        int computedChecksum = checksum(body);
        if (bodyChecksum != computedChecksum) {
            throw new IllegalArgumentException("invalid MVCC page-record body checksum: expected 0x"
                    + Integer.toHexString(bodyChecksum) + ", computed 0x"
                    + Integer.toHexString(computedChecksum));
        }
        return new DecodedHeader(
                new PageRecordMetadata(false, recordType, flags, bodyLength, bodyChecksum),
                body);
    }

    private static int checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return (int) crc.getValue();
    }

    private record DecodedHeader(PageRecordMetadata metadata, byte[] body) {
        private DecodedHeader {
            metadata = Objects.requireNonNull(metadata, "metadata");
            body = Objects.requireNonNull(body, "body");
        }
    }

    public record PageRecord(PageRecordMetadata metadata, MvccVersionRecord versionRecord) {
        public PageRecord {
            metadata = Objects.requireNonNull(metadata, "metadata");
            versionRecord = Objects.requireNonNull(versionRecord, "versionRecord");
        }
    }

    public record PageRecordMetadata(
            boolean legacyFormat,
            int recordType,
            int flags,
            int bodyLength,
            int bodyChecksum) {
        public PageRecordMetadata {
            if (bodyLength < 0) {
                throw new IllegalArgumentException("bodyLength must not be negative: " + bodyLength);
            }
        }

        static PageRecordMetadata legacyVersionRecord(int bodyLength) {
            return new PageRecordMetadata(true, RECORD_TYPE_VERSION, FLAGS_NONE, bodyLength, 0);
        }

        public boolean versionRecord() {
            return recordType == RECORD_TYPE_VERSION;
        }
    }
}
