package io.github.ggeorg.delosdb.storage.io.page;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class DelosPagePrimitiveSmoke {
    private DelosPagePrimitiveSmoke() {
    }

    public static void main(String[] args) {
        provesPageIdIsZeroBasedOffset();
        provesPageRoundTripPreservesOpaquePayloads();
        provesPageHeaderLayoutRemainsStable();
        provesExpectedPageIdMismatchIsRejected();
        System.out.println("delosdb-storage-io-s2-page-primitives: PASS");
    }

    private static void provesPageIdIsZeroBasedOffset() {
        DelosPageId pageId = new DelosPageId(7L);
        assertEquals(7L * DelosPage.PAGE_SIZE, pageId.byteOffset(DelosPage.PAGE_SIZE), "byte offset");
        assertEquals(new DelosPageId(8L), pageId.next(), "next page id");
        assertThrows(IllegalArgumentException.class, () -> new DelosPageId(-1L), "negative page id");
    }

    private static void provesPageRoundTripPreservesOpaquePayloads() {
        DelosPage page = DelosPage.empty(new DelosPageId(3L), 9);
        int firstSlot = page.appendRecord(new byte[] {1, 2, 3, 4});
        int secondSlot = page.appendRecord(new byte[] {9, 8, 7});

        byte[] encoded = page.toBytes();
        DelosPage decoded = DelosPageIo.decode(encoded, new DelosPageId(3L));

        assertEquals(new DelosPageId(3L), decoded.pageId(), "decoded page id");
        assertEquals(9, decoded.pageType(), "decoded page type");
        assertEquals(2, decoded.slotCount(), "decoded slot count");
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoded.readRecord(firstSlot), "first payload");
        assertArrayEquals(new byte[] {9, 8, 7}, decoded.readRecord(secondSlot), "second payload");
    }

    private static void provesPageHeaderLayoutRemainsStable() {
        DelosPage page = DelosPage.empty(new DelosPageId(11L), 4);
        page.appendRecord(new byte[] {42});
        byte[] encoded = page.toBytes();
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);

        assertEquals(DelosPageIo.MAGIC, buffer.getInt(0), "magic");
        assertEquals(DelosPageIo.FORMAT_VERSION, buffer.getShort(4), "format version");
        assertEquals(4, Short.toUnsignedInt(buffer.getShort(6)), "page type");
        assertEquals(11L, buffer.getLong(8), "page id");
        assertEquals(DelosPage.HEADER_SIZE + DelosPage.SLOT_SIZE, buffer.getInt(16), "freeStart");
        assertEquals(DelosPage.PAGE_SIZE - 1, buffer.getInt(20), "freeEnd");
        assertEquals(1, buffer.getInt(24), "slot count");
    }

    private static void provesExpectedPageIdMismatchIsRejected() {
        byte[] encoded = DelosPage.empty(new DelosPageId(1L)).toBytes();
        assertThrows(
                IllegalArgumentException.class,
                () -> DelosPageIo.decode(encoded, new DelosPageId(2L)),
                "page id mismatch");
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual, String label) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + Arrays.toString(expected)
                    + " but found " + Arrays.toString(actual));
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but found " + actual);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable, String label) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError(label + ": expected " + expected.getName()
                    + " but caught " + actual.getClass().getName(), actual);
        }
        throw new AssertionError(label + ": expected " + expected.getName() + " but nothing was thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
