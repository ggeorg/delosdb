package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPage;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageFile;
import io.github.ggeorg.delosdb.storage.mvcc.io.MvccPageId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A2 durable MVCC row-version record proof.
 */
public final class MvccVersionRecordCodecTest {
    @TempDir
    private Path directory;

    @Test
    public void testEncodeDecodeInsertVersion() {
        byte[] payload = "id=1,name=alpha".getBytes(StandardCharsets.UTF_8);
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(1L),
                        new MvccVersionId(10L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(2L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(3L),
                        0),
                payload);

        MvccVersionRecord decoded = MvccVersionRecordCodec.decode(MvccVersionRecordCodec.encode(record));

        assertEquals(record, decoded);
        assertFalse(decoded.header().hasPreviousVersion());
        assertFalse(decoded.header().isTombstone());
        assertArrayEquals(payload, decoded.payload());
        assertEquals(MvccVersionRecordCodec.HEADER_SIZE + payload.length, decoded.encodedLength());
    }

    @Test
    public void testEncodeDecodeUpdateVersionChain() {
        byte[] payload = "id=1,name=beta".getBytes(StandardCharsets.UTF_8);
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(1L),
                        new MvccVersionId(11L),
                        new MvccVersionId(10L),
                        new MvccTransactionId(4L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(5L),
                        0),
                payload);

        MvccVersionRecord decoded = MvccVersionRecordCodec.decode(MvccVersionRecordCodec.encode(record));

        assertEquals(new MvccVersionId(10L), decoded.header().previousVersionId());
        assertTrue(decoded.header().hasPreviousVersion());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    public void testEncodeDecodeDeleteMarker() {
        MvccVersionRecord tombstone = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(1L),
                        new MvccVersionId(12L),
                        new MvccVersionId(11L),
                        new MvccTransactionId(6L),
                        new MvccTransactionId(6L),
                        new MvccCommitSequence(7L),
                        MvccVersionRecordFlags.TOMBSTONE),
                new byte[0]);

        MvccVersionRecord decoded = MvccVersionRecordCodec.decode(MvccVersionRecordCodec.encode(tombstone));

        assertTrue(decoded.header().isTombstone());
        assertTrue(decoded.header().hasPreviousVersion());
        assertEquals(0, decoded.payload().length);
    }

    @Test
    public void testEncodedVersionRecordCanBeStoredInPageFileAndDecodedAfterReopen() throws Exception {
        Path file = directory.resolve("versions.mvccp");
        MvccVersionRecord record = new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(9L),
                        new MvccVersionId(20L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(8L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(9L),
                        0),
                "page-backed-alpha".getBytes(StandardCharsets.UTF_8));

        try (MvccPageFile pageFile = MvccPageFile.open(file)) {
            MvccPage page = pageFile.allocatePage();
            page.appendRecord(MvccVersionRecordCodec.encode(record));
            pageFile.writePage(page);
            pageFile.force();
        }

        try (MvccPageFile reopened = MvccPageFile.open(file)) {
            byte[] encoded = reopened.readPage(new MvccPageId(0)).readRecord(0);
            assertEquals(record, MvccVersionRecordCodec.decode(encoded));
        }
    }

    @Test
    public void testRejectsBadMagic() {
        byte[] encoded = MvccVersionRecordCodec.encode(sampleRecord());
        encoded[0] = 0x12;
        assertThrows(IllegalArgumentException.class, () -> MvccVersionRecordCodec.decode(encoded));
    }

    @Test
    public void testRejectsUnsupportedVersion() {
        byte[] encoded = MvccVersionRecordCodec.encode(sampleRecord());
        encoded[5] = 99;
        assertThrows(IllegalArgumentException.class, () -> MvccVersionRecordCodec.decode(encoded));
    }

    @Test
    public void testRejectsTruncatedPayload() {
        byte[] encoded = MvccVersionRecordCodec.encode(sampleRecord());
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);
        assertThrows(IllegalArgumentException.class, () -> MvccVersionRecordCodec.decode(truncated));
    }

    @Test
    public void testRejectsUnknownFlags() {
        assertThrows(IllegalArgumentException.class, () -> new MvccTupleHeader(
                new MvccRowId(1L),
                new MvccVersionId(1L),
                MvccVersionId.NONE,
                new MvccTransactionId(1L),
                MvccTransactionId.NONE,
                MvccCommitSequence.NONE,
                0x40));
    }

    @Test
    public void testRejectsInvalidIdsAndTombstoneWithoutDeletingTransaction() {
        assertThrows(IllegalArgumentException.class, () -> new MvccTupleHeader(
                MvccRowId.NONE,
                new MvccVersionId(1L),
                MvccVersionId.NONE,
                new MvccTransactionId(1L),
                MvccTransactionId.NONE,
                MvccCommitSequence.NONE,
                0));

        assertThrows(IllegalArgumentException.class, () -> new MvccTupleHeader(
                new MvccRowId(1L),
                new MvccVersionId(1L),
                MvccVersionId.NONE,
                new MvccTransactionId(1L),
                MvccTransactionId.NONE,
                MvccCommitSequence.NONE,
                MvccVersionRecordFlags.TOMBSTONE));
    }

    private static MvccVersionRecord sampleRecord() {
        return new MvccVersionRecord(
                new MvccTupleHeader(
                        new MvccRowId(1L),
                        new MvccVersionId(2L),
                        MvccVersionId.NONE,
                        new MvccTransactionId(3L),
                        MvccTransactionId.NONE,
                        new MvccCommitSequence(4L),
                        0),
                "sample".getBytes(StandardCharsets.UTF_8));
    }
}
