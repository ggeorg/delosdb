package io.github.ggeorg.delosdb.storage.mvcc.format;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/** Shared helpers for small MVCC durable binary formats. */
public final class MvccBinaryFormat {
    public static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;

    private MvccBinaryFormat() {
    }

    public static ByteBuffer wrap(byte[] bytes) {
        return ByteBuffer.wrap(Objects.requireNonNull(bytes, "bytes")).order(BYTE_ORDER);
    }

    public static boolean hasMagic(byte[] bytes, int magic) {
        Objects.requireNonNull(bytes, "bytes");
        return bytes.length >= Integer.BYTES && ByteBuffer.wrap(bytes, 0, Integer.BYTES)
                .order(BYTE_ORDER)
                .getInt() == magic;
    }

    /**
     * Validates the standard MVCC binary header shape:
     * {@code int magic, short version, unsigned short headerSize}.
     *
     * <p>The returned buffer is positioned immediately after the header-size
     * field, ready for the caller to read format-specific fields.</p>
     */
    public static ByteBuffer requireHeader(
            byte[] bytes,
            int minimumHeaderSize,
            int magic,
            short formatVersion,
            int expectedHeaderSize,
            String formatName) {
        requireMinimumLength(bytes, minimumHeaderSize, formatName + " is shorter than header");
        ByteBuffer buffer = wrap(bytes);
        int actualMagic = buffer.getInt();
        if (actualMagic != magic) {
            throw new IllegalArgumentException("invalid " + formatName + " magic 0x"
                    + Integer.toHexString(actualMagic) + ", expected 0x" + Integer.toHexString(magic));
        }
        short version = buffer.getShort();
        if (version != formatVersion) {
            throw new IllegalArgumentException("unsupported " + formatName + " format version "
                    + version + ", expected " + formatVersion);
        }
        int headerSize = Short.toUnsignedInt(buffer.getShort());
        if (headerSize != expectedHeaderSize) {
            throw new IllegalArgumentException("unsupported " + formatName + " header size "
                    + headerSize + ", expected " + expectedHeaderSize);
        }
        return buffer;
    }

    public static void requireMinimumLength(byte[] bytes, int minimumLength, String message) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < minimumLength) {
            throw new IllegalArgumentException(message + ": " + bytes.length);
        }
    }

    public static void requireExactLength(byte[] bytes, int expectedLength, String formatName) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException(formatName + " length mismatch: expected "
                    + expectedLength + " bytes, found " + bytes.length);
        }
    }
}
