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
import org.apache.derby.iapi.store.types.DelosMutationPreparation;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;
import org.apache.derby.shared.common.error.StandardException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Phase I1 proof: native MVCC mutation exposes an optimistic
 * validate/prepare primitive bound to DelosRowIdentity.  The primitive claims
 * no lock acquisition and rejects a row identity after that row is deleted.
 */
public final class StoragePhaseI1MutationPreparationSmoke {
    private static final String DATABASE_PATH = "storage-phase-i1-mutation-preparation-db";
    private static final String TABLE_NAME = "I1_MUTATION_PREP";

    private StoragePhaseI1MutationPreparationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveMutationPreparationPrimitive();
        } finally {
            clearProofProperties();
            VersionedStorageSqlBridge.resetRouteClassifierForTesting();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_i1_mutation_preparation: PASS");
    }

    private static void proveMutationPreparationPrimitive() throws Exception {
        clearProofProperties();
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_INSERT_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY, "true");
        System.setProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY, "true");

        DelosRowIdentity rowIdentity;
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE_NAME + " (id INT, value VARCHAR(32)) USING delos_mvcc") == 0,
                    "Expected native CREATE TABLE USING delos_mvcc to succeed");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 1, "one") == 1,
                    "Expected native INSERT to affect one row");
            require(SmokeUtils.executePreparedUpdate(connection,
                    "INSERT INTO APP." + TABLE_NAME + " VALUES (?, ?)", 2, "two") == 1,
                    "Expected native INSERT to affect one row");

            TableDescriptor table = tableDescriptor(connection, "APP", TABLE_NAME);
            rowIdentity = rowIdentityForId(table, 1);
            assertPreparedWithoutLock(table, rowIdentity);
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            TableDescriptor table = tableDescriptor(connection, "APP", TABLE_NAME);
            try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                         DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                                 .orElseThrow(() -> new IllegalStateException(
                                         "Expected native delos_mvcc access for APP." + TABLE_NAME))) {
                require(nativeAccess.update(rowIdentity, List.of(1, "one-prime")) == 1L,
                        "Expected native update through prepared row identity to affect one row");
            }

            assertSqlValue(connection, 1, "one-prime");
            assertPreparedWithoutLock(table, rowIdentity);

            try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                         DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                                 .orElseThrow(() -> new IllegalStateException(
                                         "Expected native delos_mvcc access for APP." + TABLE_NAME))) {
                require(nativeAccess.delete(rowIdentity) == 1L,
                        "Expected native delete through prepared row identity to affect one row");
            }
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false)) {
            TableDescriptor table = tableDescriptor(connection, "APP", TABLE_NAME);
            try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                         DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                                 .orElseThrow(() -> new IllegalStateException(
                                         "Expected native delos_mvcc access for APP." + TABLE_NAME))) {
                DelosMutationPreparation stale = nativeAccess.tableAccess().prepareMutation(
                        nativeAccess.context(), rowIdentity);
                require(!stale.mutable(), "Deleted row identity must not validate as mutable");
                require(!stale.prepared(), "Deleted row identity must not prepare for mutation");
                require(!stale.lockAcquired(), "Option A must not claim row-lock acquisition");
            }

            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT * FROM APP." + TABLE_NAME + " WHERE id = ?")) {
                select.setInt(1, 1);
                try (ResultSet rows = select.executeQuery()) {
                    require(!rows.next(), "Expected deleted row id=1 to be invisible");
                }
            }
        }

        require(VersionedStorageSqlBridge.lastRouteClassifierForTesting().isEmpty(),
                "I1 mutation preparation proof must not invoke VersionedStorageSqlBridge.tryExecute(...): "
                        + VersionedStorageSqlBridge.lastRouteClassifierForTesting());
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
                Object actualId = row.values().get(0);
                if (actualId instanceof org.apache.derby.iapi.store.types.StoreDataValue value) {
                    actualId = org.apache.derby.impl.services.storetypes.EngineMvccTableAccess.nativeValue(value);
                }
                if (Integer.valueOf(id).equals(actualId)) {
                    return row.rowIdentity().orElseThrow(() ->
                            new IllegalStateException("Native scan row has no DelosRowIdentity"));
                }
            }
        }
        throw new IllegalStateException("Could not find native row identity for id=" + id);
    }

    private static void assertPreparedWithoutLock(TableDescriptor table, DelosRowIdentity rowIdentity)
            throws SQLException {
        try (DelosNativeTableRegistry.NativeExecutionTableAccess nativeAccess =
                     DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                             .orElseThrow(() -> new IllegalStateException(
                                     "Expected native delos_mvcc access for APP." + TABLE_NAME))) {
            DelosMutationPreparation validation = nativeAccess.tableAccess().validateMutable(
                    nativeAccess.context(), rowIdentity);
            require(validation.mutable(), "Visible row identity should validate as mutable");
            require(!validation.prepared(), "validateMutable should validate without preparing");
            require(!validation.lockAcquired(), "validateMutable must not claim a row lock");

            DelosMutationPreparation prepared = nativeAccess.tableAccess().prepareMutation(
                    nativeAccess.context(), rowIdentity);
            require(prepared.mutable(), "Visible row identity should prepare as mutable");
            require(prepared.prepared(), "prepareMutation should mark a visible row identity as prepared");
            require(!prepared.lockAcquired(), "prepareMutation Option A must not claim a row lock");
        }
    }

    private static void assertSqlValue(Connection connection, int id, String expectedValue)
            throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT value FROM APP." + TABLE_NAME + " WHERE id = ?")) {
            select.setInt(1, id);
            try (ResultSet rows = select.executeQuery()) {
                require(rows.next(), "Expected row id=" + id + " to be visible");
                require(expectedValue.equals(rows.getString(1)),
                        "Expected row id=" + id + " value " + expectedValue + " but was " + rows.getString(1));
                require(!rows.next(), "Expected exactly one row for id=" + id);
            }
        }
    }

    private static TableDescriptor tableDescriptor(Connection connection, String schemaName, String tableName)
            throws SQLException, StandardException {
        if (!(connection instanceof EmbedConnection embedConnection)) {
            throw new IllegalArgumentException("I1 mutation preparation proof requires an embedded Derby connection");
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
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_ALL_PROPERTY);
        System.clearProperty(DelosTableScanProviderLookup.FACTORY_NATIVE_SELECT_EQUALITY_PROPERTY);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
