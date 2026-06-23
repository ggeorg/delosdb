package delosdb.smoke;

import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Phase F2.2 proof: the future ResultSetFactory branch can resolve persisted
 * table-provider metadata from the generated table-scan argument shape
 * (LanguageConnectionContext + tableName) without invoking the transitional SQL
 * bridge and without changing generated activation bytecode.
 */
public final class StoragePhaseF2ResultSetLookupSmoke {
    private static final String DATABASE_PATH = "storage-phase-f2-resultset-lookup-db";
    private static final String MVCC_TABLE_NAME = "F2_RESULTSET_LOOKUP_MVCC";
    private static final String HEAP_TABLE_NAME = "F2_RESULTSET_LOOKUP_HEAP";
    private static final String PROVIDER_NAME = "delos_mvcc";

    private StoragePhaseF2ResultSetLookupSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();

        try {
            createTables();
            SmokeUtils.shutdown(DATABASE_PATH);
            verifyLookupAfterRestart();
        } finally {
            SmokeUtils.shutdown(DATABASE_PATH);
        }

        System.out.println("storage_phase_f2_resultset_lookup: PASS");
    }

    private static void createTables() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true)) {
            executePrepared(connection,
                    "CREATE TABLE " + MVCC_TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc");
            executePrepared(connection,
                    "CREATE TABLE " + HEAP_TABLE_NAME + " (id INT, value VARCHAR(32))");
        }
    }

    private static void verifyLookupAfterRestart() throws Exception {
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            assertProvider(connection, "APP." + MVCC_TABLE_NAME, PROVIDER_NAME, false);
            assertProvider(connection, MVCC_TABLE_NAME, PROVIDER_NAME, false);
            assertProvider(connection, "APP." + HEAP_TABLE_NAME, "heap", true);
            assertMissing(connection, "APP.F2_RESULTSET_LOOKUP_MISSING");
        }
    }

    private static void executePrepared(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static void assertProvider(Connection connection, String tableName, String expectedProvider, boolean defaultProvider)
            throws Exception {
        Optional<DelosTableScanProviderLookup.Result> resolved = resolve(connection, tableName);
        require(resolved.isPresent(), "Expected table-scan provider metadata for " + tableName);
        DelosTableScanProviderLookup.Result result = resolved.get();
        require(expectedProvider.equals(result.storageProviderName()),
                "Expected provider " + expectedProvider + " for " + tableName
                        + " but was " + result.storageProviderName());
        require(defaultProvider == result.isDefaultStorageProvider(),
                "Unexpected default-provider flag for " + tableName);
        require(result.isProvider(expectedProvider),
                "Expected isProvider(" + expectedProvider + ") for " + tableName);
    }

    private static void assertMissing(Connection connection, String tableName) throws Exception {
        require(resolve(connection, tableName).isEmpty(),
                "Expected no table-scan provider metadata for missing table " + tableName);
    }

    private static Optional<DelosTableScanProviderLookup.Result> resolve(Connection connection, String tableName)
            throws Exception {
        if (!(connection instanceof EmbedConnection embedConnection)) {
            throw new IllegalArgumentException("F2.2 lookup proof requires an embedded Derby connection");
        }
        LanguageConnectionContext lcc = embedConnection.getLanguageConnection();
        ContextManager contextManager = lcc.getContextManager();
        ContextService contextService = ContextService.getFactory();
        boolean contextSet = false;
        try {
            contextService.setCurrentContextManager(contextManager);
            contextSet = true;
            return DelosTableScanProviderLookup.find(lcc, tableName);
        } finally {
            if (contextSet) {
                contextService.resetCurrentContextManager(contextManager);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
