package io.github.ggeorg.delosdb.storage.io.page;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Codec for {@link DelosPage} images. */
public final class DelosPageIo {
    public static final int MAGIC = 0x444D5650; // "DMVP" existing DelosDB page magic.
    public static final short FORMAT_VERSION = 2;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private DelosPageIo() {
    }

    public static byte[] encode(DelosPage page) {
        Objects.requireNonNull(page, "page");
        byte[] bytes = page.copyImage();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        buffer.putInt(0, MAGIC);
        buffer.putShort(4, FORMAT_VERSION);
        buffer.putShort(6, checkedShort(page.pageType(), "pageType"));
        buffer.putLong(8, page.pageId().value());
        buffer.putLong(16, page.pageLsn());
        buffer.putInt(24, page.slotTableEnd());
        buffer.putInt(28, page.freeEnd());
        buffer.putInt(32, page.slotCount());

        int position = DelosPage.HEADER_SIZE;
        for (DelosPage.Slot slot : page.copySlots()) {
            buffer.putInt(position, slot.offset());
            buffer.putInt(position + 4, slot.length());
            buffer.putInt(position + 8, slot.flags());
            position += DelosPage.SLOT_SIZE;
        }
        return bytes;
    }

    public static DelosPage decode(byte[] bytes) {
        return decode(bytes, null);
    }

    public static DelosPage decode(byte[] bytes, DelosPageId expectedPageId) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != DelosPage.PAGE_SIZE) {
            throw new IllegalArgumentException("page image must be exactly " + DelosPage.PAGE_SIZE + " bytes");
        }

        byte[] image = bytes.clone();
        ByteBuffer buffer = ByteBuffer.wrap(image).order(BYTE_ORDER);
        int magic = buffer.getInt(0);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "invalid page magic 0x" + Integer.toHexString(magic) + ", expected 0x"
                            + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort(4);
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported page format version " + version + ", expected " + FORMAT_VERSION);
        }
        int pageType = Short.toUnsignedInt(buffer.getShort(6));
        DelosPageId pageId = new DelosPageId(buffer.getLong(8));
        if (expectedPageId != null && !expectedPageId.equals(pageId)) {
            throw new IllegalArgumentException(
                    "page id mismatch: expected " + expectedPageId.value() + ", found " + pageId.value());
        }

        long pageLsn = buffer.getLong(16);
        if (pageLsn < 0L) {
            throw new IllegalArgumentException("pageLSN must be non-negative: " + pageLsn);
        }
        int freeStart = buffer.getInt(24);
        int freeEnd = buffer.getInt(28);
        int slotCount = buffer.getInt(32);
        if (slotCount < 0) {
            throw new IllegalArgumentException("slot count must be non-negative: " + slotCount);
        }
        int expectedFreeStart = DelosPage.HEADER_SIZE + Math.multiplyExact(slotCount, DelosPage.SLOT_SIZE);
        if (freeStart != expectedFreeStart) {
            throw new IllegalArgumentException(
                    "invalid freeStart " + freeStart + ", expected " + expectedFreeStart);
        }
        if (freeEnd < freeStart || freeEnd > DelosPage.PAGE_SIZE) {
            throw new IllegalArgumentException("invalid freeEnd: " + freeEnd + ", freeStart=" + freeStart);
        }

        List<DelosPage.Slot> slots = new ArrayList<>(slotCount);
        int position = DelosPage.HEADER_SIZE;
        for (int index = 0; index < slotCount; index++) {
            int offset = buffer.getInt(position);
            int length = buffer.getInt(position + 4);
            int flags = buffer.getInt(position + 8);
            if (length <= 0) {
                throw new IllegalArgumentException("slot " + index + " has invalid length: " + length);
            }
            if (offset < freeEnd || offset + length > DelosPage.PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "slot " + index + " points outside record area: offset=" + offset + ", length=" + length);
            }
            slots.add(new DelosPage.Slot(offset, length, flags));
            position += DelosPage.SLOT_SIZE;
        }
        return DelosPage.decoded(pageId, pageType, pageLsn, image, slots, freeEnd);
    }

    private static short checkedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " outside unsigned short range: " + value);
        }
        return (short) value;
    }
}
