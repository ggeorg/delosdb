package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.VersionedStorageSqlBridge;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;
import org.apache.derby.shared.common.error.StandardException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Phase I2 proof: native UPDATE/DELETE surfaces MVCC write/write conflicts as
 * Derby transaction conflicts without introducing a row-lock claim.
 */
public final class StoragePhaseI2MutationConflictSmoke {
    private static final String DATABASE_PATH = "storage-phase-i2-mutation-conflict-db";
    private static final String TABLE_NAME = "I2_MUTATION_CONFLICT";
    private static final String TRANSACTION_CONFLICT_SQL_STATE = "40001";

    private StoragePhaseI2MutationConflictSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveNativeMutationConflictMapping();
        } finally {
            clearProofProperties();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_i2_mutation_conflict: PASS");
    }

    private static void proveNativeMutationConflictMapping() throws Exception {
        clearProofProperties();
        enableNativeMutationProofs();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected native INSERT id=1 to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected native INSERT id=2 to affect one row");
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            TableDescriptor table = tableDescriptor(connection, "APP", TABLE_NAME);
            DelosRowIdentity updateIdentity = rowIdentityForId(table, 1);
            DelosRowIdentity deleteIdentity = rowIdentityForId(table, 2);

            proveUpdateConflictMapping(table, updateIdentity);
            proveDeleteConflictMapping(table, deleteIdentity);
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "I2 mutation conflict proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void proveUpdateConflictMapping(
            TableDescriptor table,
            DelosRowIdentity rowIdentity) throws Exception {
        DelosNativeTableRegistry.NativeExecutionTableAccess firstWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access for APP." + TABLE_NAME));
        try {
            require(firstWriter.update(rowIdentity, List.of(1, "held-update")) == 1L,
                    "Expected first native writer to update id=1 and keep its MVCC transaction active");
            try (Connection conflictConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
                SQLException conflict = expectSqlException(() -> SmokeUtils.executePreparedUpdate(
                        conflictConnection,
                        "UPDATE APP." + TABLE_NAME + " SET value = ? WHERE id = ?",
                        "second-update",
                        1));
                assertTransactionConflict(conflict, "UPDATE");
            }
        } finally {
            firstWriter.abort();
        }

        try (Connection successConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(SmokeUtils.executePreparedUpdate(
                    successConnection,
                    "UPDATE APP." + TABLE_NAME + " SET value = ? WHERE id = ?",
                    "after-update-conflict",
                    1) == 1,
                    "Expected UPDATE to succeed after the active writer aborts");
        }
    }

    private static void proveDeleteConflictMapping(
            TableDescriptor table,
            DelosRowIdentity rowIdentity) throws Exception {
        DelosNativeTableRegistry.NativeExecutionTableAccess firstWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access for APP." + TABLE_NAME));
        try {
            require(firstWriter.delete(rowIdentity) == 1L,
                    "Expected first native writer to delete id=2 and keep its MVCC transaction active");
            try (Connection conflictConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
                SQLException conflict = expectSqlException(() -> SmokeUtils.executePreparedUpdate(
                        conflictConnection,
                        "DELETE FROM APP." + TABLE_NAME + " WHERE id = ?",
                        2));
                assertTransactionConflict(conflict, "DELETE");
            }
        } finally {
            firstWriter.abort();
        }

        try (Connection successConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(SmokeUtils.executePreparedUpdate(
                    successConnection,
                    "DELETE FROM APP." + TABLE_NAME + " WHERE id = ?",
                    2) == 1,
                    "Expected DELETE to succeed after the active writer aborts");
        }
    }

    private static SQLException expectSqlException(SqlOperation operation) throws Exception {
        try {
            operation.run();
        } catch (SQLException expected) {
            return expected;
        }
        throw new IllegalStateException("Expected SQL mutation conflict but operation succeeded");
    }

    private static void assertTransactionConflict(SQLException conflict, String operation) {
        require(TRANSACTION_CONFLICT_SQL_STATE.equals(conflict.getSQLState()),
                "Expected " + operation + " conflict SQLState " + TRANSACTION_CONFLICT_SQL_STATE
                        + " but was " + conflict.getSQLState() + ": " + conflict.getMessage());
        require(conflict.getMessage() != null && conflict.getMessage().contains("delos_mvcc " + operation),
                "Expected " + operation + " conflict message to identify the native delos_mvcc mutation boundary: "
                        + conflict.getMessage());
    }

    private static DelosRowIdentity rowIdentityForId(TableDescriptor table, int id)
            throws SQLException {
        try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                     DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                             .orElseThrow(() -> new IllegalStateException(
                                     "Expected native delos_mvcc access for APP." + TABLE_NAME));
             DelosScan scan = nativeAccess.tableAccess().scan(
                     nativeAccess.context(),
                     List.of(),
                     DelosProjection.all())) {
            while (scan.next()) {
                DelosRow row = scan.row();
                Object actualId = EngineMvccTableAccess.nativeValue(row.values().get(0));
                if (Integer.valueOf(id).equals(actualId)) {
                    return row.rowIdentity().orElseThrow(() ->
                            new IllegalStateException("Native scan row has no DelosRowIdentity"));
                }
            }
        }
        throw new IllegalStateException("Could not find native row identity for id=" + id);
    }

    private static TableDescriptor tableDescriptor(Connection connection, String schemaName, String tableName)
            throws SQLException, StandardException {
        if (!(connection instanceof EmbedConnection embedConnection)) {
            throw new IllegalArgumentException("I2 mutation conflict proof requires an embedded Derby connection");
        }
        LanguageConnectionContext lcc = embedConnection.getLanguageConnection();
        ContextManager contextManager = lcc.getContextManager();
        ContextService contextService = ContextService.getFactory();
        boolean contextSet = false;
        try {
            contextService.setCurrentContextManager(contextManager);
            contextSet = true;
            DataDictionary dataDictionary = lcc.getDataDictionary();
            TransactionController transactionController = lcc.getTransactionExecute();
            SchemaDescriptor schema = dataDictionary.getSchemaDescriptor(
                    normalizeIdentifier(schemaName), transactionController, true);
            TableDescriptor table = dataDictionary.getTableDescriptor(
                    normalizeIdentifier(tableName), schema, transactionController);
            if (table == null) {
                throw new IllegalArgumentException("Table not found: " + schema.getSchemaName() + "." + tableName);
            }
            return table;
        } finally {
            if (contextSet) {
                contextService.resetCurrentContextManager(contextManager);
            }
        }
    }

    private static void enableNativeMutationProofs() {
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY, "true");
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_UPDATE_EQUALITY_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_DELETE_EQUALITY_PROPERTY);
    }

    private static String normalizeIdentifier(String identifier) {
        return identifier.trim().toUpperCase(Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws Exception;
    }
}
