package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.VersionedStorageProviderRegistry;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.execution.VersionedStorageExecutionBridge;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxView;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedRow;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableStats;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccTxContext;
import io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransaction;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionManager;

import java.util.List;
import java.util.Optional;

/**
 * Proves the first engine-side execution bridge can drive an experimental
 * VersionedStorageProvider without wiring Derby SQL execution yet.
 */
public final class VersionedStorageExecutionBridgeSmoke {
    private VersionedStorageExecutionBridgeSmoke() {
    }

    public static void main(String[] args) {
        DelosMvccStorageProvider provider = new DelosMvccStorageProvider();
        VersionedStorageProviderRegistry registry = VersionedStorageProviderRegistry.empty();
        registry.registerEnabled(provider, "experimental");

        VersionedStorageExecutionBridge bridge = new VersionedStorageExecutionBridge(registry.resolver());
        VersionedTableMetadata metadata = new VersionedTableMetadata("APP", "MVCC_EXECUTION_BRIDGE");
        VersionedTable<Integer, String> table = bridge.createTable(
                DelosMvccStorageProvider.PROVIDER_NAME,
                metadata);

        MvccTransactionManager transactions = new MvccTransactionManager();
        MvccTransaction insertTransaction = transactions.begin();
        TxContext insertContext = context(transactions, insertTransaction);
        bridge.insert(table, 1, "one", insertContext);
        requireEquals(Optional.of("one"), bridge.read(table, 1, insertContext.currentView()),
                "writer reads its own inserted row");
        transactions.commit(insertTransaction);

        MvccTransaction oldReader = transactions.begin();
        TxView oldView = context(transactions, oldReader).currentView();
        requireRows(bridge.scanAll(table, oldView), List.of(new VersionedRow<>(1, "one")),
                "old reader sees committed insert");

        MvccTransaction updateTransaction = transactions.begin();
        TxContext updateContext = context(transactions, updateTransaction);
        bridge.update(table, 1, "two", updateContext);
        transactions.commit(updateTransaction);

        requireRows(bridge.scanAll(table, oldView), List.of(new VersionedRow<>(1, "one")),
                "old snapshot remains stable after committed update");

        MvccTransaction newReader = transactions.begin();
        TxView newView = context(transactions, newReader).currentView();
        requireEquals(Optional.of("two"), bridge.read(table, 1, newView),
                "new reader sees committed update");
        requireRows(bridge.scanAll(table, newView), List.of(new VersionedRow<>(1, "two")),
                "new scan sees committed update");

        MvccTransaction deleteTransaction = transactions.begin();
        TxContext deleteContext = context(transactions, deleteTransaction);
        bridge.delete(table, 1, deleteContext);
        transactions.abort(deleteTransaction);

        MvccTransaction afterAbortReader = transactions.begin();
        TxView afterAbortView = context(transactions, afterAbortReader).currentView();
        requireEquals(Optional.of("two"), bridge.read(table, 1, afterAbortView),
                "aborted delete does not hide committed row");

        VersionedTableStats stats = bridge.stats(table, afterAbortView);
        if (stats.visibleRowCount() != 1L || stats.logicalRowCount() != 1L || stats.physicalVersionCount() < 2L) {
            throw new IllegalStateException("Unexpected MVCC stats after bridge operations: " + stats);
        }

        requireProviderFailure(bridge);

        System.out.println("versioned_storage_execution provider=" + DelosMvccStorageProvider.PROVIDER_NAME
                + " rows=" + stats.visibleRowCount()
                + " physicalVersions=" + stats.physicalVersionCount());
        System.out.println("DelosDB VersionedStorage execution bridge smoke test passed.");
    }

    private static DelosMvccTxContext context(MvccTransactionManager transactions, MvccTransaction transaction) {
        return new DelosMvccTxContext(
                transaction,
                transactions.snapshot(transaction),
                transactions,
                transactions.oldestActiveVisibleThrough());
    }

    private static void requireProviderFailure(VersionedStorageExecutionBridge bridge) {
        try {
            bridge.createTable("missing_provider", new VersionedTableMetadata("APP", "SHOULD_NOT_EXIST"));
            throw new AssertionError("Missing provider unexpectedly resolved");
        } catch (RuntimeException expected) {
            String message = expected.getMessage();
            if (message == null || !message.contains("missing_provider")) {
                throw new AssertionError("Unexpected missing provider diagnostic: " + message, expected);
            }
        }
    }

    private static <T> void requireEquals(T expected, T actual, String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static <K, V> void requireRows(
            List<VersionedRow<K, V>> actual,
            List<VersionedRow<K, V>> expected,
            String message) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(message + ": expected=" + expected + " actual=" + actual);
        }
    }
}
