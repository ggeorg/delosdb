package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class MvccOverflowPayloadCodecTest {
    @Test
    void descriptorRoundTripsFirstChunkLocator() {
        MvccOverflowPayloadDescriptor descriptor = new MvccOverflowPayloadDescriptor(
                20_000L,
                3,
                Optional.of(new MvccVersionLocator(new DelosPageId(7L), 2)));

        MvccOverflowPayloadDescriptor decoded = MvccOverflowPayloadCodec.decodeDescriptor(
                MvccOverflowPayloadCodec.encodeDescriptor(descriptor));

        assertEquals(descriptor, decoded);
    }

    @Test
    void emptyDescriptorHasNoLocator() {
        MvccOverflowPayloadDescriptor decoded = MvccOverflowPayloadCodec.decodeDescriptor(
                MvccOverflowPayloadCodec.encodeDescriptor(MvccOverflowPayloadDescriptor.empty()));

        assertEquals(0L, decoded.totalLength());
        assertEquals(0, decoded.chunkCount());
        assertFalse(decoded.firstChunkLocator().isPresent());
    }

    @Test
    void chunkRoundTripsLinkedLocatorAndPayload() {
        byte[] payload = new byte[] {1, 3, 5, 7};
        MvccOverflowPayloadChunk chunk = new MvccOverflowPayloadChunk(
                1,
                3,
                12L,
                payload,
                Optional.of(new MvccVersionLocator(new DelosPageId(9L), 1)));

        MvccOverflowPayloadChunk decoded = MvccOverflowPayloadCodec.decodeChunk(
                MvccOverflowPayloadCodec.encodeChunk(chunk));

        assertEquals(chunk, decoded);
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void finalChunkRoundTripsWithoutNextLocator() {
        MvccOverflowPayloadChunk chunk = new MvccOverflowPayloadChunk(
                2,
                3,
                12L,
                new byte[] {9},
                Optional.empty());

        MvccOverflowPayloadChunk decoded = MvccOverflowPayloadCodec.decodeChunk(
                MvccOverflowPayloadCodec.encodeChunk(chunk));

        assertEquals(chunk, decoded);
    }

    @Test
    void codecRejectsInvalidDescriptorMagicAndReservedFields() {
        byte[] badMagic = MvccOverflowPayloadCodec.encodeDescriptor(MvccOverflowPayloadDescriptor.empty());
        badMagic[0] = 0x12;
        assertThrows(IllegalArgumentException.class, () -> MvccOverflowPayloadCodec.decodeDescriptor(badMagic));

        byte[] badReserved = MvccOverflowPayloadCodec.encodeDescriptor(MvccOverflowPayloadDescriptor.empty());
        ByteBuffer.wrap(badReserved).order(ByteOrder.BIG_ENDIAN).putInt(32, 1);
        assertThrows(IllegalArgumentException.class, () -> MvccOverflowPayloadCodec.decodeDescriptor(badReserved));
    }

    @Test
    void codecRejectsInvalidChunkMagicAndLengthMismatch() {
        MvccOverflowPayloadChunk chunk = new MvccOverflowPayloadChunk(
                0,
                1,
                3L,
                new byte[] {1, 2, 3},
                Optional.empty());

        byte[] badMagic = MvccOverflowPayloadCodec.encodeChunk(chunk);
        badMagic[0] = 0x12;
        assertThrows(IllegalArgumentException.class, () -> MvccOverflowPayloadCodec.decodeChunk(badMagic));

        byte[] badLength = MvccOverflowPayloadCodec.encodeChunk(chunk);
        ByteBuffer.wrap(badLength).order(ByteOrder.BIG_ENDIAN).putInt(24, 99);
        assertThrows(IllegalArgumentException.class, () -> MvccOverflowPayloadCodec.decodeChunk(badLength));
    }

    @Test
    void chunkInvariantsRejectBrokenLinks() {
        assertThrows(IllegalArgumentException.class, () -> new MvccOverflowPayloadChunk(
                0,
                2,
                10L,
                new byte[] {1},
                Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new MvccOverflowPayloadChunk(
                1,
                2,
                10L,
                new byte[] {1},
                Optional.of(new MvccVersionLocator(new DelosPageId(1L), 0))));
    }
}
