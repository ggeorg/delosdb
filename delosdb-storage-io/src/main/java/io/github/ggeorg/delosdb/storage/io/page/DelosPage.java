package io.github.ggeorg.delosdb.storage.io.page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Raw fixed-size DelosDB storage page.
 *
 * <p>The page owns only storage-level facts: page id, page type, fixed-size
 * image, slot table, and opaque payload bytes. It deliberately carries no
 * transaction, visibility, recovery-policy, SQL-row, heap, or provider meaning.</p>
 */
public final class DelosPage {
    public static final int PAGE_SIZE = 8192;
    public static final int DATA_PAGE_TYPE = 1;

    static final int HEADER_SIZE = 28;
    static final int SLOT_SIZE = 12;
    static final int ACTIVE_SLOT = 1;

    private final DelosPageId pageId;
    private final int pageType;
    private final byte[] image;
    private final List<Slot> slots;
    private int freeEnd;

    private DelosPage(DelosPageId pageId, int pageType, byte[] image, List<Slot> slots, int freeEnd) {
        this.pageId = Objects.requireNonNull(pageId, "pageId");
        if (pageType <= 0) {
            throw new IllegalArgumentException("page type must be positive: " + pageType);
        }
        this.pageType = pageType;
        this.image = Objects.requireNonNull(image, "image");
        if (image.length != PAGE_SIZE) {
            throw new IllegalArgumentException("page image must be exactly " + PAGE_SIZE + " bytes");
        }
        this.slots = new ArrayList<>(Objects.requireNonNull(slots, "slots"));
        this.freeEnd = freeEnd;
        validateLayout();
    }

    public static DelosPage empty(DelosPageId pageId) {
        return empty(pageId, DATA_PAGE_TYPE);
    }

    public static DelosPage empty(DelosPageId pageId, int pageType) {
        return new DelosPage(pageId, pageType, new byte[PAGE_SIZE], List.of(), PAGE_SIZE);
    }

    static DelosPage decoded(DelosPageId pageId, int pageType, byte[] image, List<Slot> slots, int freeEnd) {
        return new DelosPage(pageId, pageType, image, slots, freeEnd);
    }

    public DelosPageId pageId() {
        return pageId;
    }

    public int pageType() {
        return pageType;
    }

    public int slotCount() {
        return slots.size();
    }

    public int freeBytes() {
        return freeEnd - slotTableEnd();
    }

    public int appendRecord(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new IllegalArgumentException("record payload must not be empty");
        }
        int requiredBytes = Math.addExact(SLOT_SIZE, payload.length);
        if (requiredBytes > freeBytes()) {
            throw new IllegalStateException(
                    "record of " + payload.length + " bytes does not fit in page " + pageId.value()
                            + "; free bytes=" + freeBytes());
        }

        int offset = freeEnd - payload.length;
        System.arraycopy(payload, 0, image, offset, payload.length);
        freeEnd = offset;
        slots.add(new Slot(offset, payload.length, ACTIVE_SLOT));
        validateLayout();
        return slots.size() - 1;
    }

    public byte[] readRecord(int slotId) {
        Slot slot = slot(slotId);
        if ((slot.flags() & ACTIVE_SLOT) == 0) {
            throw new IllegalStateException("slot " + slotId + " is not active");
        }
        return Arrays.copyOfRange(image, slot.offset(), slot.offset() + slot.length());
    }

    public byte[] toBytes() {
        return DelosPageIo.encode(this);
    }

    byte[] copyImage() {
        return image.clone();
    }

    List<Slot> copySlots() {
        return List.copyOf(slots);
    }

    int freeEnd() {
        return freeEnd;
    }

    int slotTableEnd() {
        return HEADER_SIZE + (slots.size() * SLOT_SIZE);
    }

    Slot slot(int slotId) {
        if (slotId < 0 || slotId >= slots.size()) {
            throw new IndexOutOfBoundsException("slot id out of range: " + slotId);
        }
        return slots.get(slotId);
    }

    private void validateLayout() {
        if (freeEnd < HEADER_SIZE || freeEnd > PAGE_SIZE) {
            throw new IllegalArgumentException("invalid page freeEnd: " + freeEnd);
        }
        if (slotTableEnd() > freeEnd) {
            throw new IllegalArgumentException(
                    "slot table overlaps record area: slotTableEnd=" + slotTableEnd() + ", freeEnd=" + freeEnd);
        }
        for (Slot slot : slots) {
            if (slot.offset() < freeEnd || slot.offset() + slot.length() > PAGE_SIZE) {
                throw new IllegalArgumentException("slot outside record area: " + slot);
            }
            if (slot.length() <= 0) {
                throw new IllegalArgumentException("slot length must be positive: " + slot.length());
            }
        }
    }

    record Slot(int offset, int length, int flags) {
    }
}
