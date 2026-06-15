package io.github.ggeorg.delosdb.storage.mvcc;

/** Visibility rules for the experimental MVCC kernel. */
public final class MvccVisibility {
    private MvccVisibility() {
    }

    public static boolean isVisible(
            MvccVersion<?> version,
            MvccSnapshot snapshot,
            MvccTransactionCatalog catalog) {
        if (!snapshot.isTransactionVisible(version.createdBy(), catalog)) {
            return false;
        }
        return version.deletedBy()
                .map(deletingTx -> !snapshot.isTransactionVisible(deletingTx, catalog))
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
}
