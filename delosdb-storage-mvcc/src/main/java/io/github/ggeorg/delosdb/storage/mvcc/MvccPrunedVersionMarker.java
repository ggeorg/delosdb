package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/** Metadata retained after cleanup removes a committed deleted version. */
record MvccPrunedVersionMarker(
        MvccTransactionId createdBy,
        MvccCommandSequence createdAtCommand,
        MvccTransactionId deletedBy,
        MvccCommandSequence deletedAtCommand) {
    MvccPrunedVersionMarker {
        if (createdBy == null || createdBy.isNone()) {
            throw new IllegalArgumentException("createdBy must be a real transaction id");
        }
        Objects.requireNonNull(createdAtCommand, "createdAtCommand");
        if (deletedBy == null || deletedBy.isNone()) {
            throw new IllegalArgumentException("deletedBy must be a real transaction id");
        }
        Objects.requireNonNull(deletedAtCommand, "deletedAtCommand");
    }

    boolean wouldHaveBeenVisible(MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(catalog, "catalog");
        return MvccVisibility.isCreationVisible(createdBy, createdAtCommand, snapshot, catalog)
                && !MvccVisibility.isDeletionVisible(deletedBy, deletedAtCommand, snapshot, catalog);
    }

    String describe() {
        return "createdBy=" + createdBy
                + ", createdAtCommand=" + createdAtCommand
                + ", deletedBy=" + deletedBy
                + ", deletedAtCommand=" + deletedAtCommand;
    }
}
