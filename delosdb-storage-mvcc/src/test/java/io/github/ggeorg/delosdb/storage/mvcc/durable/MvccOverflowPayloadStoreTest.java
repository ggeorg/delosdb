package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.ggeorg.delosdb.storage.io.page.DelosPage;
import io.github.ggeorg.delosdb.storage.io.page.DelosPageId;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolume;
import io.github.ggeorg.delosdb.storage.io.volume.DelosPageVolumeFactories;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MvccOverflowPayloadStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void largePayloadRoundTripsAcrossOverflowPagesAfterReopen() throws Exception {
        Path file = tempDir.resolve("overflow.mvccp");
        byte[] payload = deterministicPayload(24_000);
        MvccOverflowPayloadDescriptor descriptor;

        try (MvccOverflowPayloadStore store = MvccOverflowPayloadStore.open(file)) {
            descriptor = store.write(payload);
            assertTrue(descriptor.chunkCount() >= 3, "payload should require multiple overflow chunks");
            assertTrue(store.pageCount() >= 3, "payload should allocate multiple overflow pages");
        }

        byte[] encodedDescriptor = MvccOverflowPayloadCodec.encodeDescriptor(descriptor);
        MvccOverflowPayloadDescriptor decodedDescriptor = MvccOverflowPayloadCodec.decodeDescriptor(encodedDescriptor);

        try (MvccOverflowPayloadStore reopened = MvccOverflowPayloadStore.open(file)) {
            assertArrayEquals(payload, reopened.read(decodedDescriptor));
        }
    }

    @Test
    void emptyPayloadDoesNotAllocateOverflowPages() throws Exception {
        Path file = tempDir.resolve("empty-overflow.mvccp");

        try (MvccOverflowPayloadStore store = MvccOverflowPayloadStore.open(file)) {
            MvccOverflowPayloadDescriptor descriptor = store.write(new byte[0]);
            assertEquals(MvccOverflowPayloadDescriptor.empty(), descriptor);
            assertEquals(0L, store.pageCount());
            assertArrayEquals(new byte[0], store.read(descriptor));
        }
    }

    @Test
    void readerRejectsDescriptorWhoseFirstPageIsNotOverflowPage() throws Exception {
        Path file = tempDir.resolve("wrong-page-type.mvccp");
        try (DelosPageVolume volume = DelosPageVolumeFactories.fileChannel().open(file)) {
            DelosPage dataPage = volume.allocatePage(DelosPage.DATA_PAGE_TYPE);
            dataPage.appendRecord(new byte[] {1});
            volume.writePage(dataPage);
            volume.force();
        }

        MvccOverflowPayloadDescriptor descriptor = new MvccOverflowPayloadDescriptor(
                1L,
                1,
                Optional.of(new MvccVersionLocator(new DelosPageId(0L), 0)));
        try (MvccOverflowPayloadStore store = MvccOverflowPayloadStore.open(file)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.read(descriptor));
            assertTrue(failure.getMessage().contains("expected overflow page type"));
        }
    }

    @Test
    void readerRejectsDescriptorWithWrongChunkCount() throws Exception {
        Path file = tempDir.resolve("wrong-count.mvccp");
        MvccOverflowPayloadDescriptor actual;
        try (MvccOverflowPayloadStore store = MvccOverflowPayloadStore.open(file)) {
            actual = store.write(deterministicPayload(9_000));
        }
        MvccOverflowPayloadDescriptor wrongCount = new MvccOverflowPayloadDescriptor(
                actual.totalLength(),
                actual.chunkCount() + 1,
                actual.firstChunkLocator());

        try (MvccOverflowPayloadStore store = MvccOverflowPayloadStore.open(file)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> store.read(wrongCount));
            assertTrue(failure.getMessage().contains("chunk count mismatch")
                    || failure.getMessage().contains("ended before chunk"));
        }
    }

    private static byte[] deterministicPayload(int length) {
        byte[] payload = new byte[length];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) ((index * 31 + 17) & 0xff);
        }
        return payload;
    }
}
