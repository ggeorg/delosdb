package io.github.ggeorg.delosdb.spi.storage.versioned;

/**
 * Stable snapshot view used by versioned storage scans and conflict checks.
 */
public interface TxView {
    /**
     * Returns whether a physical row version is visible to this view.
     *
     * @param createdByTransactionId transaction that created the row version
     * @param deletedByTransactionId transaction that deleted the row version, or
     *                               {@code 0} when the version is not deleted
     */
    boolean isVisible(long createdByTransactionId, long deletedByTransactionId);

    /**
     * Smallest transaction boundary that may still be visible to an active
     * snapshot. Cleanup must not remove versions needed before this boundary.
     */
    long oldestVisibleTransaction();
}
