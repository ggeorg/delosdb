package io.github.ggeorg.delosdb.storage.mvcc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

final class MvccBinaryFormatTest {
    @Test
    void requireHeaderValidatesStandardMvccBinaryHeader() {
        byte[] bytes = new byte[12];
        ByteBuffer buffer = MvccBinaryFormat.wrap(bytes);
        buffer.putInt(0x444D5448);
        buffer.putShort((short) 1);
        buffer.putShort((short) 12);
        buffer.putInt(42);

        ByteBuffer decoded = MvccBinaryFormat.requireHeader(
                bytes,
                12,
                0x444D5448,
                (short) 1,
                12,
                "MVCC test-header");

        assertEquals(42, decoded.getInt());
        assertTrue(MvccBinaryFormat.hasMagic(bytes, 0x444D5448));
        assertFalse(MvccBinaryFormat.hasMagic(new byte[] {1, 2, 3}, 0x444D5448));
    }

    @Test
    void requireHeaderRejectsBadHeaderShape() {
        byte[] bytes = new byte[8];
        ByteBuffer buffer = MvccBinaryFormat.wrap(bytes);
        buffer.putInt(0x444D5448);
        buffer.putShort((short) 2);
        buffer.putShort((short) 8);

        assertThrows(IllegalArgumentException.class, () -> MvccBinaryFormat.requireHeader(
                bytes,
                8,
                0x444D5448,
                (short) 1,
                8,
                "MVCC test-header"));
        assertThrows(IllegalArgumentException.class, () -> MvccBinaryFormat.requireExactLength(
                bytes,
                9,
                "MVCC test-header"));
    }
}
