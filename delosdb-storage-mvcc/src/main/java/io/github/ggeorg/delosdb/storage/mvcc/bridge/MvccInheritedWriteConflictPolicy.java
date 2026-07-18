package io.github.ggeorg.delosdb.storage.mvcc.bridge;

import java.util.List;
import java.util.Set;

import io.github.ggeorg.delosdb.storage.mvcc.MvccWriteConflictException;

/** Shared active-writer conflict policy for mutation and commit publication. */
final class MvccInheritedWriteConflictPolicy {
    private MvccInheritedWriteConflictPolicy() {
    }

    static void requireNoOtherActiveProviderWriter(
            List<MvccInheritedHandles.Transaction> activeTransactions,
            MvccInheritedHandles.Transaction handle,
            long rowId,
            String operation,
            Set<MvccInheritedHandles.Transaction> ignoredWriters) {
        for (MvccInheritedHandles.Transaction activeTransaction : activeTransactions) {
            if (activeTransaction != handle
                    && !ignoredWriters.contains(activeTransaction)
                    && activeTransaction.hasWriteIntentForRow(rowId)) {
                throw new MvccWriteConflictException("provider write conflict: row "
                        + rowId + " has another active writer during " + operation);
            }
        }
    }
}
