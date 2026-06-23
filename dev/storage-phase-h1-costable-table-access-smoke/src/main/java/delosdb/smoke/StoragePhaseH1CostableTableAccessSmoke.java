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
import org.apache.derby.iapi.store.types.DelosCostableTableAccess;
import org.apache.derby.iapi.store.types.DelosTableCapability;
import org.apache.derby.iapi.store.types.DelosTableCostEstimate;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;
import org.apache.derby.shared.common.error.StandardException;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Phase H1 proof: native delos_mvcc table access exposes a cost-capability seam
 * backed by provider table statistics.  This does not route costs into Derby's
 * optimizer yet; it only proves the separate DelosCostableTableAccess surface.
 */
public final class StoragePhaseH1CostableTableAccessSmoke {
    private static final String DATABASE_PATH = "storage-phase-h1-costable-table-access-db";
    private static final String TABLE_NAME = "H1_COSTABLE";

    private StoragePhaseH1CostableTableAccessSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveMvccCostableTableAccess();
        } finally {
            clearProofProperties();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_h1_costable_table_access: PASS");
    }

    private static void proveMvccCostableTableAccess() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected first native INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected second native INSERT to affect one row");

            assertCostableAccess(connection);
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "H1 costable table proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
    }

    private static void assertCostableAccess(Connection connection)
            throws SQLException, StandardException {
        TableDescriptor table = tableDescriptor(connection, "APP", TABLE_NAME);
        try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                     DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                             .orElseThrow(() -> new IllegalStateException(
                                     "Expected native delos_mvcc access for APP." + TABLE_NAME))) {
            Object tableAccess = nativeAccess.tableAccess();
            require(tableAccess instanceof DelosCostableTableAccess,
                    "Expected MVCC table access to implement DelosCostableTableAccess");
            DelosCostableTableAccess costable = (DelosCostableTableAccess) tableAccess;
            require(costable.capabilities().supports(DelosTableCapability.COSTABLE),
                    "Expected MVCC capabilities to advertise COSTABLE");

            DelosTableCostEstimate estimate = costable.estimateTableCost(nativeAccess.context());
            require(estimate.logicalRowCount() == 2L,
                    "Expected logicalRowCount 2 but was " + estimate.logicalRowCount());
            require(estimate.visibleRowCount() == 2L,
                    "Expected visibleRowCount 2 but was " + estimate.visibleRowCount());
            require(estimate.physicalVersionCount() == 2L,
                    "Expected physicalVersionCount 2 but was " + estimate.physicalVersionCount());
            require(estimate.deadVersionEstimate() == 0L,
                    "Expected deadVersionEstimate 0 but was " + estimate.deadVersionEstimate());
            require(estimate.estimatedFullScanCost() == 2L,
                    "Expected estimatedFullScanCost 2 but was " + estimate.estimatedFullScanCost());
        }
    }

    private static TableDescriptor tableDescriptor(Connection connection, String schemaName, String tableName)
            throws SQLException, StandardException {
        if (!(connection instanceof EmbedConnection embedConnection)) {
            throw new IllegalArgumentException("H1 costable table proof requires an embedded Derby connection");
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

    private static String normalizeIdentifier(String identifier) {
        return identifier.trim().toUpperCase(Locale.ROOT);
    }

    private static void clearProofProperties() {
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
