package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccBinaryFormat;

/** Binary codec for MVCC overflow-payload descriptors and chunks. */
public final class MvccOverflowPayloadCodec {
    public static final int DESCRIPTOR_MAGIC = 0x444D564F; // "DMVO" - DelosDB MVCC overflow descriptor.
    public static final int CHUNK_MAGIC = 0x444D5643; // "DMVC" - DelosDB MVCC overflow chunk.
    public static final short FORMAT_VERSION = 1;
    public static final int DESCRIPTOR_SIZE = 44;
    public static final int CHUNK_HEADER_SIZE = 52;
    private static final long NO_PAGE_ID = -1L;
    private static final int NO_SLOT_ID = -1;

    private MvccOverflowPayloadCodec() {
    }

    public static byte[] encodeDescriptor(MvccOverflowPayloadDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        byte[] bytes = new byte[DESCRIPTOR_SIZE];
        ByteBuffer buffer = MvccBinaryFormat.wrap(bytes);
        buffer.putInt(DESCRIPTOR_MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) DESCRIPTOR_SIZE);
        buffer.putLong(descriptor.totalLength());
        buffer.putInt(descriptor.chunkCount());
        Optional<MvccVersionLocator> first = descriptor.firstChunkLocator();
        buffer.putLong(first.map(locator -> locator.pageId().value()).orElse(NO_PAGE_ID));
        buffer.putInt(first.map(MvccVersionLocator::slotId).orElse(NO_SLOT_ID));
        buffer.putInt(0); // reserved for future descriptor flags.
        buffer.putLong(0L); // reserved for future descriptor checksum/chain id.
        return bytes;
    }

    public static MvccOverflowPayloadDescriptor decodeDescriptor(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        MvccBinaryFormat.requireExactLength(bytes, DESCRIPTOR_SIZE, "MVCC overflow descriptor");
        ByteBuffer buffer = MvccBinaryFormat.requireHeader(
                bytes,
                DESCRIPTOR_SIZE,
                DESCRIPTOR_MAGIC,
                FORMAT_VERSION,
                DESCRIPTOR_SIZE,
                "MVCC overflow descriptor");
        long totalLength = buffer.getLong();
        int chunkCount = buffer.getInt();
        long firstPageId = buffer.getLong();
        int firstSlotId = buffer.getInt();
        int flags = buffer.getInt();
        long reserved = buffer.getLong();
        if (flags != 0 || reserved != 0L) {
            throw new IllegalArgumentException("unsupported non-zero MVCC overflow descriptor reserved fields");
        }
        return new MvccOverflowPayloadDescriptor(
                totalLength,
                chunkCount,
                decodeLocator(firstPageId, firstSlotId, "firstChunkLocator"));
    }

    public static byte[] encodeChunk(MvccOverflowPayloadChunk chunk) {
        Objects.requireNonNull(chunk, "chunk");
        byte[] payload = chunk.payload();
        byte[] bytes = new byte[Math.addExact(CHUNK_HEADER_SIZE, payload.length)];
        ByteBuffer buffer = MvccBinaryFormat.wrap(bytes);
        buffer.putInt(CHUNK_MAGIC);
        buffer.putShort(FORMAT_VERSION);
        buffer.putShort((short) CHUNK_HEADER_SIZE);
        buffer.putInt(chunk.chunkIndex());
        buffer.putInt(chunk.chunkCount());
        buffer.putLong(chunk.totalLength());
        buffer.putInt(payload.length);
        Optional<MvccVersionLocator> next = chunk.nextChunkLocator();
        buffer.putLong(next.map(locator -> locator.pageId().value()).orElse(NO_PAGE_ID));
        buffer.putInt(next.map(MvccVersionLocator::slotId).orElse(NO_SLOT_ID));
        buffer.putInt(0); // reserved for future per-chunk flags.
        buffer.putLong(0L); // reserved for future per-chunk checksum/chain id.
        buffer.put(payload);
        return bytes;
    }

    public static MvccOverflowPayloadChunk decodeChunk(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        ByteBuffer buffer = MvccBinaryFormat.requireHeader(
                bytes,
                CHUNK_HEADER_SIZE,
                CHUNK_MAGIC,
                FORMAT_VERSION,
                CHUNK_HEADER_SIZE,
                "MVCC overflow chunk");
        int chunkIndex = buffer.getInt();
        int chunkCount = buffer.getInt();
        long totalLength = buffer.getLong();
        int payloadLength = buffer.getInt();
        long nextPageId = buffer.getLong();
        int nextSlotId = buffer.getInt();
        int flags = buffer.getInt();
        long reserved = buffer.getLong();
        if (flags != 0 || reserved != 0L) {
            throw new IllegalArgumentException("unsupported non-zero MVCC overflow chunk reserved fields");
        }
        if (payloadLength <= 0) {
            throw new IllegalArgumentException("MVCC overflow chunk payload length must be positive: " + payloadLength);
        }
        int expectedLength = Math.addExact(CHUNK_HEADER_SIZE, payloadLength);
        MvccBinaryFormat.requireExactLength(bytes, expectedLength, "MVCC overflow chunk");
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);
        return new MvccOverflowPayloadChunk(
                chunkIndex,
                chunkCount,
                totalLength,
                payload,
                decodeLocator(nextPageId, nextSlotId, "nextChunkLocator"));
    }

    private static Optional<MvccVersionLocator> decodeLocator(long pageId, int slotId, String name) {
        if (pageId == NO_PAGE_ID && slotId == NO_SLOT_ID) {
            return Optional.empty();
        }
        if (pageId < 0L || slotId < 0) {
            throw new IllegalArgumentException(
                    "invalid " + name + ": pageId=" + pageId + ", slotId=" + slotId);
        }
        return Optional.of(new MvccVersionLocator(new DelosPageId(pageId), slotId));
    }
}
