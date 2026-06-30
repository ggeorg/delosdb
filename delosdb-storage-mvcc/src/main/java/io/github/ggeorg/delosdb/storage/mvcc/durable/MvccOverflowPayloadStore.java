package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactory;

/**
 * Page-volume backed store for MVCC overflow payload chunks.
 *
 * <p>This is intentionally not wired into SQL row writes yet. It is the durable
 * primitive needed before long rows/LOBs can move from a clean failure boundary
 * to real support.</p>
 */
public final class MvccOverflowPayloadStore implements AutoCloseable {
    public static final int OVERFLOW_PAGE_TYPE = 2;

    private static final int SLOT_OVERHEAD_BYTES = 12;

    private final DelosPageVolume pageVolume;

    private MvccOverflowPayloadStore(DelosPageVolume pageVolume) {
        this.pageVolume = Objects.requireNonNull(pageVolume, "pageVolume");
    }

    public static MvccOverflowPayloadStore open(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(path, DelosPageVolumeFactories.fileChannel());
    }

    public static MvccOverflowPayloadStore open(
            Path path,
            DelosPageVolumeFactory volumeFactory) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(volumeFactory, "volumeFactory");
        return new MvccOverflowPayloadStore(volumeFactory.open(path));
    }

    static MvccOverflowPayloadStore open(DelosPageVolume pageVolume) {
        return new MvccOverflowPayloadStore(pageVolume);
    }

    public synchronized MvccOverflowPayloadDescriptor write(byte[] payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            return MvccOverflowPayloadDescriptor.empty();
        }

        int chunkPayloadBytes = maxChunkPayloadBytes();
        int chunkCount = Math.toIntExact(((long) payload.length + chunkPayloadBytes - 1L) / chunkPayloadBytes);
        MvccVersionLocator next = null;
        for (int chunkIndex = chunkCount - 1; chunkIndex >= 0; chunkIndex--) {
            int start = chunkIndex * chunkPayloadBytes;
            int end = Math.min(payload.length, start + chunkPayloadBytes);
            byte[] chunkPayload = Arrays.copyOfRange(payload, start, end);
            MvccOverflowPayloadChunk chunk = new MvccOverflowPayloadChunk(
                    chunkIndex,
                    chunkCount,
                    payload.length,
                    chunkPayload,
                    Optional.ofNullable(next));
            next = appendChunk(MvccOverflowPayloadCodec.encodeChunk(chunk));
        }
        return new MvccOverflowPayloadDescriptor(payload.length, chunkCount, Optional.of(next));
    }

    public synchronized byte[] read(MvccOverflowPayloadDescriptor descriptor) throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.chunkCount() == 0) {
            return new byte[0];
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.toIntExact(descriptor.totalLength()));
        Optional<MvccVersionLocator> current = descriptor.firstChunkLocator();
        for (int expectedIndex = 0; expectedIndex < descriptor.chunkCount(); expectedIndex++) {
            if (current.isEmpty()) {
                throw new IllegalStateException(
                        "overflow chain ended before chunk " + expectedIndex + " of " + descriptor.chunkCount());
            }
            MvccVersionLocator locator = current.get();
            MvccOverflowPayloadChunk chunk = readChunk(locator);
            validateChunk(descriptor, expectedIndex, chunk);
            out.writeBytes(chunk.payload());
            current = chunk.nextChunkLocator();
        }
        if (current.isPresent()) {
            throw new IllegalStateException("overflow chain contains extra chunk after expected count "
                    + descriptor.chunkCount() + ": " + current.get());
        }
        byte[] payload = out.toByteArray();
        if (payload.length != descriptor.totalLength()) {
            throw new IllegalStateException("overflow chain total length mismatch: expected "
                    + descriptor.totalLength() + " bytes, found " + payload.length);
        }
        return payload;
    }

    public synchronized long pageCount() throws IOException {
        return pageVolume.pageCount();
    }

    @Override
    public synchronized void close() throws IOException {
        pageVolume.close();
    }

    private MvccOverflowPayloadChunk readChunk(MvccVersionLocator locator) throws IOException {
        DelosPage page = pageVolume.readPage(locator.pageId());
        if (page.pageType() != OVERFLOW_PAGE_TYPE) {
            throw new IllegalStateException("expected overflow page type " + OVERFLOW_PAGE_TYPE
                    + " at page " + locator.pageId().value() + ", found " + page.pageType());
        }
        return MvccOverflowPayloadCodec.decodeChunk(page.readRecord(locator.slotId()));
    }

    private MvccVersionLocator appendChunk(byte[] encodedChunk) throws IOException {
        DelosPage page = writableOverflowPage(encodedChunk.length);
        int slotId = page.appendRecord(encodedChunk);
        pageVolume.writePage(page);
        pageVolume.force();
        return new MvccVersionLocator(page.pageId(), slotId);
    }

    private DelosPage writableOverflowPage(int encodedChunkLength) throws IOException {
        long count = pageVolume.pageCount();
        if (count == 0) {
            return pageVolume.allocatePage(OVERFLOW_PAGE_TYPE);
        }
        DelosPage last = pageVolume.readPage(new DelosPageId(count - 1L));
        if (last.pageType() == OVERFLOW_PAGE_TYPE
                && last.freeBytes() >= encodedChunkLength + SLOT_OVERHEAD_BYTES) {
            return last;
        }
        return pageVolume.allocatePage(OVERFLOW_PAGE_TYPE);
    }

    private static int maxChunkPayloadBytes() {
        int maxRecordBytes = DelosPage.empty(new DelosPageId(0L), OVERFLOW_PAGE_TYPE).freeBytes()
                - SLOT_OVERHEAD_BYTES;
        return maxRecordBytes - MvccOverflowPayloadCodec.CHUNK_HEADER_SIZE;
    }

    private static void validateChunk(
            MvccOverflowPayloadDescriptor descriptor,
            int expectedIndex,
            MvccOverflowPayloadChunk chunk) {
        if (chunk.chunkIndex() != expectedIndex) {
            throw new IllegalStateException("overflow chain chunk index mismatch: expected "
                    + expectedIndex + ", found " + chunk.chunkIndex());
        }
        if (chunk.chunkCount() != descriptor.chunkCount()) {
            throw new IllegalStateException("overflow chain chunk count mismatch: expected "
                    + descriptor.chunkCount() + ", found " + chunk.chunkCount());
        }
        if (chunk.totalLength() != descriptor.totalLength()) {
            throw new IllegalStateException("overflow chain total length mismatch: expected "
                    + descriptor.totalLength() + ", found " + chunk.totalLength());
        }
    }
}
