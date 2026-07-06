package io.github.ggeorg.delosdb.storage.mvcc.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MvccSidecarCodecTest {
    @TempDir
    Path tempDir;

    @Test
    void missingSidecarReadsAsEmptyPayload() throws Exception {
        assertTrue(MvccSidecarCodec.readPayloadIfExists(
                tempDir.resolve("missing.sidecar"),
                Integer.BYTES,
                "test sidecar").isEmpty());
    }

    @Test
    void writesChecksumTrailerAndReadsPayload() throws Exception {
        Path path = tempDir.resolve("checked.sidecar");
        int payloadLength = Integer.BYTES + Long.BYTES;
        ByteBuffer payload = MvccSidecarCodec.allocatePayload(payloadLength);
        payload.putInt(0x01020304);
        payload.putLong(42L);

        MvccSidecarCodec.rewritePayload(path, payload, payloadLength);

        byte[] bytes = Files.readAllBytes(path);
        assertEquals(payloadLength + MvccSidecarCodec.CHECKSUM_BYTES, bytes.length);
        int storedChecksum = ByteBuffer.wrap(bytes, payloadLength, MvccSidecarCodec.CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        assertEquals(MvccSidecarFiles.checksum(bytes, 0, payloadLength), storedChecksum);

        ByteBuffer recovered = MvccSidecarCodec.readPayloadIfExists(
                path,
                payloadLength,
                "test sidecar").orElseThrow();
        assertEquals(0x01020304, recovered.getInt());
        assertEquals(42L, recovered.getLong());
    }


    @Test
    void checksumRewriteDoesNotLeaveRewriteSibling() throws Exception {
        Path path = tempDir.resolve("checked-cleanup.sidecar");
        int payloadLength = Integer.BYTES;
        ByteBuffer payload = MvccSidecarCodec.allocatePayload(payloadLength);
        payload.putInt(99);

        MvccSidecarCodec.rewritePayload(path, payload, payloadLength);

        assertTrue(Files.exists(path));
        assertTrue(Files.notExists(tempDir.resolve("checked-cleanup.sidecar.rewrite")));
    }

    @Test
    void checksumMismatchFailsLoudly() throws Exception {
        Path path = tempDir.resolve("corrupt.sidecar");
        int payloadLength = Integer.BYTES;
        ByteBuffer payload = MvccSidecarCodec.allocatePayload(payloadLength);
        payload.putInt(7);
        MvccSidecarCodec.rewritePayload(path, payload, payloadLength);

        byte[] bytes = Files.readAllBytes(path);
        bytes[0] ^= 0x01;
        Files.write(path, bytes);

        assertThrows(IllegalStateException.class, () -> MvccSidecarCodec.readPayloadIfExists(
                path,
                payloadLength,
                "test sidecar"));
    }

    @Test
    void rewriteRequiresFullyPopulatedPayload() {
        Path path = tempDir.resolve("underfilled.sidecar");
        int payloadLength = Long.BYTES;
        ByteBuffer payload = MvccSidecarCodec.allocatePayload(payloadLength);
        payload.putInt(1);

        assertThrows(IllegalStateException.class,
                () -> MvccSidecarCodec.rewritePayload(path, payload, payloadLength));
    }
}
