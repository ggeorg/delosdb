package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
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
    private static final int CHECKSUM_OFFSET = 20;

    private MvccPageRecordCodec() {
    }

    public static byte[] encodeVersionRecord(MvccVersionRecord record) {
        Objects.requireNonNull(record, "record");
        int bodyLength = record.encodedLength();
        byte[] bytes = new byte[Math.addExact(HEADER_SIZE, bodyLength)];
        ByteBuffer buffer = MvccBinaryFormat.wrap(bytes);
        buffer.putInt(MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) HEADER_SIZE);
        buffer.putInt(RECORD_TYPE_VERSION);
        buffer.putInt(FLAGS_NONE);
        buffer.putInt(bodyLength);
        buffer.putInt(0); // Filled after the version record is written in place.
        MvccVersionRecordCodec.encodeInto(record, buffer);
        buffer.putInt(CHECKSUM_OFFSET, checksum(bytes, HEADER_SIZE, bodyLength));
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
        return new PageRecord(
                header.metadata(),
                MvccVersionRecordCodec.decode(bytes, header.bodyOffset(), header.metadata().bodyLength()));
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
        return MvccBinaryFormat.hasMagic(bytes, MAGIC);
    }

    private static DecodedHeader decodeHeader(byte[] bytes) {
        ByteBuffer buffer = MvccBinaryFormat.requireHeader(
                bytes,
                HEADER_SIZE,
                MAGIC,
                FORMAT_VERSION,
                HEADER_SIZE,
                "MVCC page-record");
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
        MvccBinaryFormat.requireExactLength(bytes, expectedLength, "MVCC page-record");
        int computedChecksum = checksum(bytes, HEADER_SIZE, bodyLength);
        if (bodyChecksum != computedChecksum) {
            throw new IllegalArgumentException("invalid MVCC page-record body checksum: expected 0x"
                    + Integer.toHexString(bodyChecksum) + ", computed 0x"
                    + Integer.toHexString(computedChecksum));
        }
        return new DecodedHeader(
                new PageRecordMetadata(false, recordType, flags, bodyLength, bodyChecksum),
                HEADER_SIZE);
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    private record DecodedHeader(PageRecordMetadata metadata, int bodyOffset) {
        private DecodedHeader {
            metadata = Objects.requireNonNull(metadata, "metadata");
            if (bodyOffset < 0) {
                throw new IllegalArgumentException("bodyOffset must not be negative: " + bodyOffset);
            }
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
