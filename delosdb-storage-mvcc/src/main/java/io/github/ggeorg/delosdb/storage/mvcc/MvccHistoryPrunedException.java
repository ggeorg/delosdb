package io.github.ggeorg.delosdb.storage.mvcc;

/**
 * Raised when a snapshot would have seen a row version that cleanup already
 * pruned. Returning Optional.empty() in this case would silently turn unsafe
 * history loss into "row not found".
 */
public final class MvccHistoryPrunedException extends RuntimeException {
    public MvccHistoryPrunedException(String message) {
        super(message);
    }
}
