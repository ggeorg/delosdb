package io.github.ggeorg.delosdb.storage.mvcc.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;

final class MvccPageRecordCodecTest {
    @Test
    void versionRecordIsWrappedWithMvccPageRecordHeader() {
        MvccVersionRecord record = sampleRecord();

        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(record);

        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        assertEquals(MvccPageRecordCodec.MAGIC, buffer.getInt(0));
        assertEquals(MvccPageRecordCodec.FORMAT_VERSION, buffer.getShort(4));
        assertEquals(MvccPageRecordCodec.HEADER_SIZE, Short.toUnsignedInt(buffer.getShort(6)));
        assertEquals(MvccPageRecordCodec.RECORD_TYPE_VERSION, buffer.getInt(8));
        assertEquals(MvccPageRecordCodec.FLAGS_NONE, buffer.getInt(12));
        assertEquals(record.encodedLength(), buffer.getInt(16));
        assertEquals(MvccPageRecordCodec.HEADER_SIZE + record.encodedLength(), encoded.length);
        assertEquals(MvccPageRecordCodec.encodedLength(record), encoded.length);
        assertTrue(MvccPageRecordCodec.isPageRecord(encoded));
        assertEquals(record, MvccPageRecordCodec.decodeVersionRecord(encoded));
    }

    @Test
    void legacyVersionRecordPayloadsStillDecode() {
        MvccVersionRecord record = sampleRecord();
        byte[] legacy = MvccVersionRecordCodec.encode(record);

        assertFalse(MvccPageRecordCodec.isPageRecord(legacy));
        assertEquals(record, MvccPageRecordCodec.decodeVersionRecord(legacy));
    }


    @Test
    void metadataDistinguishesWrappedVersionRecordsFromLegacyRecords() {
        MvccVersionRecord record = sampleRecord();
        byte[] wrapped = MvccPageRecordCodec.encodeVersionRecord(record);
        byte[] legacy = MvccVersionRecordCodec.encode(record);

        MvccPageRecordCodec.PageRecordMetadata wrappedMetadata = MvccPageRecordCodec.metadata(wrapped);
        assertFalse(wrappedMetadata.legacyFormat());
        assertTrue(wrappedMetadata.versionRecord());
        assertEquals(MvccPageRecordCodec.RECORD_TYPE_VERSION, wrappedMetadata.recordType());
        assertEquals(MvccPageRecordCodec.FLAGS_NONE, wrappedMetadata.flags());
        assertEquals(record.encodedLength(), wrappedMetadata.bodyLength());

        MvccPageRecordCodec.PageRecordMetadata legacyMetadata = MvccPageRecordCodec.metadata(legacy);
        assertTrue(legacyMetadata.legacyFormat());
        assertTrue(legacyMetadata.versionRecord());
        assertEquals(legacy.length, legacyMetadata.bodyLength());
    }

    @Test
    void decodeExposesRecordMetadataBesideVersionRecord() {
        MvccVersionRecord record = sampleRecord();

        MvccPageRecordCodec.PageRecord decoded = MvccPageRecordCodec.decode(
                MvccPageRecordCodec.encodeVersionRecord(record));

        assertEquals(record, decoded.versionRecord());
        assertFalse(decoded.metadata().legacyFormat());
        assertTrue(decoded.metadata().versionRecord());
    }

    @Test
    void pageRecordRejectsChecksumDamageBeforeVersionDecode() {
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(sampleRecord());
        encoded[encoded.length - 1] ^= 0x01;

        assertThrows(IllegalArgumentException.class, () -> MvccPageRecordCodec.decodeVersionRecord(encoded));
    }

    @Test
    void pageRecordRejectsUnsupportedRecordType() {
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(sampleRecord());
        ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN).putInt(8, 99);

        assertThrows(IllegalArgumentException.class, () -> MvccPageRecordCodec.decodeVersionRecord(encoded));
    }

    @Test
    void pageRecordBodyMatchesExistingVersionRecordCodec() {
        MvccVersionRecord record = sampleRecord();
        byte[] encoded = MvccPageRecordCodec.encodeVersionRecord(record);
        byte[] body = new byte[encoded.length - MvccPageRecordCodec.HEADER_SIZE];
        System.arraycopy(encoded, MvccPageRecordCodec.HEADER_SIZE, body, 0, body.length);

        assertArrayEquals(MvccVersionRecordCodec.encode(record), body);
    }

    private static MvccVersionRecord sampleRecord() {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(7L),
                        new MvccVersionId(11L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(3L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(9L),
                        0),
                "payload".getBytes(StandardCharsets.UTF_8));
    }
}
