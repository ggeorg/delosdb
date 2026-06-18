package io.github.ggeorg.delosdb.storage.mvcc;

/** Visibility rules for the experimental MVCC kernel. */
public final class MvccVisibility {
    private MvccVisibility() {
    }

    public static boolean isVisible(
            MvccVersion<?> version,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        if (!isCreatingTransactionVisible(version, snapshot, catalog)) {
            return false;
        }
        return version.deletedBy()
                .map(deletingTx -> !isDeletingTransactionVisible(version, deletingTx, snapshot, catalog))
                .orElse(true);
    }

    public static boolean isSafeToPrune(
            MvccVersion<?> version,
            MvccCommitSequence oldestVisibleThrough,
            MvccTransactionCatalog catalog) {
        if (version.wasCreatedByAbortedTransaction(catalog)) {
            return true;
        }
        return version.isDeletedBeforeOrAt(oldestVisibleThrough, catalog);
    }

    static boolean isCreationVisible(
            MvccTransactionId createdBy,
            MvccCommandSequence createdAtCommand,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        if (createdBy.equals(snapshot.owner())) {
            return snapshot.isOwnerCommandVisible(createdAtCommand);
        }
        return snapshot.isTransactionVisible(createdBy, catalog);
    }

    static boolean isDeletionVisible(
            MvccTransactionId deletedBy,
            MvccCommandSequence deletedAtCommand,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        if (deletedBy.equals(snapshot.owner())) {
            return snapshot.isOwnerCommandVisible(deletedAtCommand);
        }
        return snapshot.isTransactionVisible(deletedBy, catalog);
    }

    private static boolean isCreatingTransactionVisible(
            MvccVersion<?> version,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        return isCreationVisible(version.createdBy(), version.createdAtCommand(), snapshot, catalog);
    }

    private static boolean isDeletingTransactionVisible(
            MvccVersion<?> version,
            MvccTransactionId deletingTx,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        MvccCommandSequence deletedAtCommand = version.deletedAtCommand()
                .orElse(MvccCommandSequence.FIRST);
        return isDeletionVisible(deletingTx, deletedAtCommand, snapshot, catalog);
    }
}
