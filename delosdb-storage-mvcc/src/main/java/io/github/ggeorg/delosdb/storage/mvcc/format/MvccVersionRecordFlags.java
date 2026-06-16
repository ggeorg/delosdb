package io.github.ggeorg.delosdb.storage.mvcc.format;

/** Durable row-version flag bits. */
public final class MvccVersionRecordFlags {
    /** This version is a delete marker rather than a value-carrying tuple. */
    public static final int TOMBSTONE = 1;

    /** Bits known to this format version. */
    public static final int KNOWN_MASK = TOMBSTONE;

    private MvccVersionRecordFlags() {
    }

    public static void validate(int flags) {
        if ((flags & ~KNOWN_MASK) != 0) {
            throw new IllegalArgumentException("unknown MVCC version-record flag bits: 0x"
                    + Integer.toHexString(flags));
        }
    }
}
