package io.github.ggeorg.delosdb.storage.mvcc;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory logical table used to prove MVCC semantics before any Derby heap,
 * B-tree, or WAL integration. Keys are logical row ids, values are immutable row
 * payloads chosen by tests or future prototypes.
 */
public final class MvccTable<K, V> {
    private final Map<K, MvccVersionChain<V>> rows = new LinkedHashMap<>();

    public synchronized void insert(K key, V value, MvccTransaction transaction) {
        if (rows.containsKey(key)) {
            throw new MvccWriteConflictException("logical row already exists: " + key);
        }
        rows.put(key, new MvccVersionChain<>(value, transaction));
    }

    public synchronized Optional<V> read(K key, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        MvccVersionChain<V> chain = rows.get(key);
        if (chain == null) {
            return Optional.empty();
        }
        return chain.visibleValue(snapshot, catalog);
    }

    public synchronized void update(K key, V value, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        chainForExistingKey(key).update(value, transaction, snapshot, catalog);
    }

    public synchronized void delete(K key, MvccTransaction transaction, MvccSnapshot snapshot, MvccTransactionCatalog catalog) {
        chainForExistingKey(key).delete(transaction, snapshot, catalog);
    }

    public synchronized int physicalVersionCount(K key) {
        MvccVersionChain<V> chain = rows.get(key);
        return chain == null ? 0 : chain.versionCount();
    }

    public synchronized MvccCleanupResult cleanup(MvccTransactionManager transactionManager) {
        MvccCommitSequence oldestVisibleThrough = transactionManager.oldestActiveVisibleThrough();
        MvccCleanupResult result = new MvccCleanupResult(0);
        Iterator<Map.Entry<K, MvccVersionChain<V>>> iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<K, MvccVersionChain<V>> entry = iterator.next();
            result = result.plus(entry.getValue().cleanup(oldestVisibleThrough, transactionManager));
            if (entry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
        return result;
    }

    private MvccVersionChain<V> chainForExistingKey(K key) {
        MvccVersionChain<V> chain = rows.get(key);
        if (chain == null) {
            throw new MvccWriteConflictException("logical row does not exist: " + key);
        }
        return chain;
    }
}
