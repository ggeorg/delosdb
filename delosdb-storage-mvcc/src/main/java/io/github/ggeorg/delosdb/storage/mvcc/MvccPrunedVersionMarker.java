package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/** Metadata retained after cleanup removes a committed deleted version. */
record MvccPrunedVersionMarker(MvccTransactionId createdBy, MvccTransactionId deletedBy) {
    MvccPrunedVersionMarker {
        if (createdBy == null || createdBy.isNone()) {
            throw new IllegalArgumentException("createdBy must be a real transaction id");
        }
        if (deletedBy == null || deletedBy.isNone()) {
            throw new IllegalArgumentException("deletedBy must be a real transaction id");
        }
    }

    boolean wouldHaveBeenVisible(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(catalog, "catalog");
        return snapshot.isTransactionVisible(createdBy, catalog)
                && !snapshot.isTransactionVisible(deletedBy, catalog);
    }

    String describe() {
        return "createdBy=" + createdBy + ", deletedBy=" + deletedBy;
    }
}
