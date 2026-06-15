package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Optional;

/**
 * Read-only transaction metadata used by the MVCC visibility engine. Keeping
 * this as a narrow interface prevents row-version code from depending on the
 * concrete transaction manager implementation.
 */
public interface MvccTransactionCatalog {
    MvccTransactionStatus statusOf(MvccTransactionId transactionId);

    Optional<MvccCommitSequence> commitSequenceOf(MvccTransactionId transactionId);
}
