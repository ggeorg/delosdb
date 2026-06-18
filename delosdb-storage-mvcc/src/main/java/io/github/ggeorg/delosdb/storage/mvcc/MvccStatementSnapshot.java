package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Objects;

/**
 * Statement-scoped MVCC view plus the command sequence assigned to that statement.
 *
 * <p>A statement snapshot is captured before the statement writes. The same
 * command sequence is then stamped on versions created or deleted by the
 * statement, so the statement can keep reading the view that existed at its
 * start while the next statement can see the write.</p>
 */
public record MvccStatementSnapshot(
        MvccTransaction transaction,
        MvccCommandSequence commandSequence,
        MvccSnapshot snapshot
) {
    public MvccStatementSnapshot {
        transaction = Objects.requireNonNull(transaction, "transaction");
        commandSequence = Objects.requireNonNull(commandSequence, "commandSequence");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.owner().equals(transaction.id())) {
            throw new IllegalArgumentException("statement snapshot owner "
                    + snapshot.owner() + " does not match transaction " + transaction.id());
        }
    }
}
