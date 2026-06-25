package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.storage.mvcc.DelosMvccStorageProvider;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionId;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatus;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusRecord;
import io.github.ggeorg.delosdb.storage.mvcc.MvccTransactionStatusStore;
import io.github.ggeorg.delosdb.spi.storage.versioned.TxContext;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTable;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTableMetadata;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedTransactionCoordinator;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MODULE5I smoke: restart visibility uses durable MVCC transaction status.
 *
 * <p>This is not WAL and not final Derby store/access integration.  It proves
 * the next durability step after MODULE5H: normal Derby INSERT/ROLLBACK routed
 * by {@code USING delos_mvcc} survives database close/reopen with committed rows
 * visible and rolled-back rows invisible. Heap remains on Derby's inherited
 * path, and no old native proof properties are enabled.</p>
 */
public final class Module5iRestartVisibilitySmoke {
    private static final String DATABASE_PATH = "build/module5i-restart-visibility-db";
    private static final String MVCC_TABLE = "MODULE5I_VISIBILITY_MVCC";
    private static final String HEAP_TABLE = "MODULE5I_VISIBILITY_HEAP";

    private Module5iRestartVisibilitySmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        Path statusFile = statusFileForDatabase(DATABASE_PATH);

        SmokeUtils.loadEmbeddedDriver();
        try {
            createAndMutateDatabase();

            Map<MvccTransactionId, MvccTransactionStatusRecord> beforeReopen = statuses(statusFile);
            newestStatus(beforeReopen, MvccTransactionStatus.COMMITTED)
                    .orElseThrow(() -> new AssertionError("MODULE5I setup did not write durable COMMITTED status"));
            newestStatus(beforeReopen, MvccTransactionStatus.ABORTED)
                    .orElseThrow(() -> new AssertionError("MODULE5I setup did not write durable ABORTED status"));

            SmokeUtils.shutdown(DATABASE_PATH);
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            assertNativeRoutePropertiesAreNotSet();

            reopenAndAssertVisibility();

            Map<MvccTransactionId, MvccTransactionStatusRecord> afterReopen = statuses(statusFile);
            newestStatus(afterReopen, MvccTransactionStatus.COMMITTED)
                    .orElseThrow(() -> new AssertionError("COMMITTED MVCC status did not survive reopen"));
            newestStatus(afterReopen, MvccTransactionStatus.ABORTED)
                    .orElseThrow(() -> new AssertionError("ABORTED MVCC status did not survive reopen"));

            assertProviderRecoveryUsesDurableStatusAuthority(Path.of("build/module5i-status-authority-provider"));
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void createAndMutateDatabase() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + MVCC_TABLE
                    + " (id INT, name VARCHAR(20)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE
                    + " (id INT, name VARCHAR(20))");

            assertNativeRoutePropertiesAreNotSet();

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (1, 'A')");
            connection.commit();

            statement.executeUpdate("INSERT INTO APP." + MVCC_TABLE + " VALUES (2, 'B')");
            connection.rollback();

            connection.setAutoCommit(true);
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (7, 'heap')");

            SmokeUtils.assertEquals(List.of(1), ids(statement, MVCC_TABLE),
                    "rolled-back delos_mvcc row should be invisible before restart");
            SmokeUtils.assertEquals(List.of("A"), names(statement, MVCC_TABLE),
                    "committed delos_mvcc row should be visible before restart");
            SmokeUtils.assertEquals(List.of(7), ids(statement, HEAP_TABLE),
                    "heap row should be visible before restart");
        }
    }

    private static void reopenAndAssertVisibility() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertNativeRoutePropertiesAreNotSet();
            SmokeUtils.assertEquals(List.of(1), ids(statement, MVCC_TABLE),
                    "restart visibility should expose committed delos_mvcc row A");
            SmokeUtils.assertEquals(List.of("A"), names(statement, MVCC_TABLE),
                    "restart visibility should preserve committed delos_mvcc value A");
            SmokeUtils.assertEquals(List.of(7), ids(statement, HEAP_TABLE),
                    "heap table should still work after delos_mvcc restart visibility proof");
        }
    }


    private static void assertProviderRecoveryUsesDurableStatusAuthority(Path storageDirectory) throws Exception {
        SmokeUtils.deleteRecursively(storageDirectory);
        VersionedTableMetadata metadata = new VersionedTableMetadata("app", "module5i_status_authority");

        DelosMvccStorageProvider writerProvider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> writerTable = writerProvider.createTable(metadata);
        VersionedTransactionCoordinator writerTransactions = writerProvider.transactionCoordinator();
        TxContext transaction = writerTransactions.begin();
        writerTable.insert(1L, List.of(1, "status-authority"), transaction);

        MvccTransactionStatusStore.open(DelosMvccStorageProvider.transactionStatusPath(storageDirectory))
                .recordCommitted(new MvccTransactionId(transaction.transactionId()), new io.github.ggeorg.delosdb.storage.mvcc.MvccCommitSequence(1L));

        DelosMvccStorageProvider recoveredProvider = DelosMvccStorageProvider.open(storageDirectory);
        VersionedTable<Long, List<Object>> recoveredTable = recoveredProvider.openTable(metadata);
        TxContext reader = recoveredProvider.transactionCoordinator().begin();
        try {
            SmokeUtils.assertEquals(Optional.of(List.of(1, "status-authority")),
                    recoveredTable.read(1L, reader.currentView()),
                    "provider restart recovery should trust durable COMMITTED status, not only storage-log COMMIT");
        } finally {
            recoveredProvider.transactionCoordinator().abort(reader);
        }
    }

    private static Path statusFileForDatabase(String databasePath) {
        Path storageDirectory = DelosMvccStorageProvider.databaseStorageDirectory(Path.of(databasePath));
        return DelosMvccStorageProvider.transactionStatusPath(storageDirectory);
    }

    private static Map<MvccTransactionId, MvccTransactionStatusRecord> statuses(Path statusFile) {
        return MvccTransactionStatusStore.open(statusFile).recoverStatuses();
    }

    private static Optional<MvccTransactionStatusRecord> newestStatus(
            Map<MvccTransactionId, MvccTransactionStatusRecord> statuses,
            MvccTransactionStatus expectedStatus) {
        return statuses.entrySet().stream()
                .filter(entry -> entry.getValue().status() == expectedStatus)
                .max(Comparator.comparingLong(entry -> entry.getKey().value()))
                .map(Map.Entry::getValue);
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        List<String> names = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id, name FROM APP." + tableName)) {
            while (rows.next()) {
                names.add(rows.getString(2));
            }
        }
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        assertPropertyNotSet(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }

    private static void assertPropertyNotSet(String propertyName) {
        if (Boolean.getBoolean(propertyName)) {
            throw new AssertionError("MODULE5I must not rely on old native proof property: " + propertyName);
        }
    }

    private static void clearNativeRouteProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_PROBE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_RANGE_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_BETWEEN_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_NULL_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_OR_PREDICATES_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_PROJECTION_VARIANTS_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_ORDER_BY_RESIDUAL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_COUNT_AGGREGATE_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
    }
}
