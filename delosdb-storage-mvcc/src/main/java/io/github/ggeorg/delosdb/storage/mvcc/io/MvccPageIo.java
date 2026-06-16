package io.github.ggeorg.delosdb.storage.mvcc.io;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Codec for {@link MvccPage} images. */
public final class MvccPageIo {
    public static final int MAGIC = 0x444D5650; // "DMVP" - DelosDB MVCC page.
    public static final short FORMAT_VERSION = 1;

    private static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private MvccPageIo() {
    }

    public static byte[] encode(MvccPage page) {
        Objects.requireNonNull(page, "page");
        byte[] bytes = page.copyImage();
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(BYTE_ORDER);
        buffer.putInt(0, MAGIC);
        buffer.putShort(4, FORMAT_VERSION);
        buffer.putShort(6, checkedShort(page.pageType(), "pageType"));
        buffer.putLong(8, page.pageId().value());
        buffer.putInt(16, page.slotTableEnd());
        buffer.putInt(20, page.freeEnd());
        buffer.putInt(24, page.slotCount());

        int position = MvccPage.HEADER_SIZE;
        for (MvccPage.Slot slot : page.copySlots()) {
            buffer.putInt(position, slot.offset());
            buffer.putInt(position + 4, slot.length());
            buffer.putInt(position + 8, slot.flags());
            position += MvccPage.SLOT_SIZE;
        }
        return bytes;
    }

    public static MvccPage decode(byte[] bytes) {
        return decode(bytes, null);
    }

    public static MvccPage decode(byte[] bytes, MvccPageId expectedPageId) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != MvccPage.PAGE_SIZE) {
            throw new IllegalArgumentException("page image must be exactly " + MvccPage.PAGE_SIZE + " bytes");
        }

        byte[] image = bytes.clone();
        ByteBuffer buffer = ByteBuffer.wrap(image).order(BYTE_ORDER);
        int magic = buffer.getInt(0);
        if (magic != MAGIC) {
            throw new IllegalArgumentException(
                    "invalid MVCC page magic 0x" + Integer.toHexString(magic) + ", expected 0x"
                            + Integer.toHexString(MAGIC));
        }
        short version = buffer.getShort(4);
        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported MVCC page format version " + version + ", expected " + FORMAT_VERSION);
        }
        int pageType = Short.toUnsignedInt(buffer.getShort(6));
        MvccPageId pageId = new MvccPageId(buffer.getLong(8));
        if (expectedPageId != null && !expectedPageId.equals(pageId)) {
            throw new IllegalArgumentException(
                    "page id mismatch: expected " + expectedPageId.value() + ", found " + pageId.value());
        }

        int freeStart = buffer.getInt(16);
        int freeEnd = buffer.getInt(20);
        int slotCount = buffer.getInt(24);
        if (slotCount < 0) {
            throw new IllegalArgumentException("slot count must be non-negative: " + slotCount);
        }
        int expectedFreeStart = MvccPage.HEADER_SIZE + Math.multiplyExact(slotCount, MvccPage.SLOT_SIZE);
        if (freeStart != expectedFreeStart) {
            throw new IllegalArgumentException(
                    "invalid freeStart " + freeStart + ", expected " + expectedFreeStart);
        }
        if (freeEnd < freeStart || freeEnd > MvccPage.PAGE_SIZE) {
            throw new IllegalArgumentException("invalid freeEnd: " + freeEnd + ", freeStart=" + freeStart);
        }

        List<MvccPage.Slot> slots = new ArrayList<>(slotCount);
        int position = MvccPage.HEADER_SIZE;
        for (int index = 0; index < slotCount; index++) {
            int offset = buffer.getInt(position);
            int length = buffer.getInt(position + 4);
            int flags = buffer.getInt(position + 8);
            if (length <= 0) {
                throw new IllegalArgumentException("slot " + index + " has invalid length: " + length);
            }
            if (offset < freeEnd || offset + length > MvccPage.PAGE_SIZE) {
                throw new IllegalArgumentException(
                        "slot " + index + " points outside record area: offset=" + offset + ", length=" + length);
            }
            slots.add(new MvccPage.Slot(offset, length, flags));
            position += MvccPage.SLOT_SIZE;
        }
        return MvccPage.decoded(pageId, pageType, image, slots, freeEnd);
    }

    private static short checkedShort(int value, String name) {
        if (value < 0 || value > 0xffff) {
            throw new IllegalArgumentException(name + " outside unsigned short range: " + value);
        }
        return (short) value;
    }
}
