package io.github.ggeorg.delosdb.storage.mvcc.durable;

import java.util.Objects;

import io.github.ggeorg.delosdb.storage.mvcc.format.MvccRowId;
import io.github.ggeorg.delosdb.storage.mvcc.format.MvccVersionId;

/**
 * Durable index candidate pointing back to versioned heap storage.
 *
 * <p>The tuple intentionally does not carry visibility information. A lookup in
 * a durable MVCC index returns candidates only; the page-backed table/version
 * directory remains the authority for snapshot visibility and for deciding
 * whether the currently visible row value still matches the indexed key.</p>
 */
public record MvccIndexTuple(MvccRowId rowId, MvccVersionId versionId, int flags) {
    public static final int ACTIVE = 1;
    public static final int ACTIVE_FLAG = ACTIVE;

    public MvccIndexTuple {
        rowId = Objects.requireNonNull(rowId, "rowId");
        versionId = Objects.requireNonNull(versionId, "versionId");
        if (versionId.equals(MvccVersionId.NONE)) {
            throw new IllegalArgumentException("index tuple must point to a concrete version id");
        }
    }

    public MvccIndexTuple(MvccRowId rowId, MvccVersionId versionId) {
        this(rowId, versionId, ACTIVE);
    }

    public static MvccIndexTuple active(MvccRowId rowId, MvccVersionId versionId) {
        return new MvccIndexTuple(rowId, versionId, ACTIVE);
    }

    public static MvccIndexTuple of(MvccRowId rowId, MvccVersionId versionId) {
        return active(rowId, versionId);
    }

    public static MvccIndexTuple of(MvccRowId rowId, MvccVersionId versionId, int flags) {
        return new MvccIndexTuple(rowId, versionId, flags);
    }

    public boolean isActive() {
        return (flags & ACTIVE) != 0;
    }
}
