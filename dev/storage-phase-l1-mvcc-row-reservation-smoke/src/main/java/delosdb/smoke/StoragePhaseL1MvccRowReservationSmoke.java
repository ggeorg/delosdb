package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;
import io.github.ggeorg.delosdb.spi.storage.versioned.VersionedWriteConflictException;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.services.context.ContextService;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.store.types.DelosMvccMutationReservation;
import org.apache.derby.iapi.store.types.DelosProjection;
import org.apache.derby.iapi.store.types.DelosRow;
import org.apache.derby.iapi.store.types.DelosRowIdentity;
import org.apache.derby.iapi.store.types.DelosScan;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.services.storetypes.EngineMvccTableAccess;
import org.apache.derby.impl.sql.execute.DelosTableScanProviderLookup;
import org.apache.derby.shared.common.error.StandardException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * L1 proof: delos_mvcc has an MVCC-specific row reservation for native
 * mutation concurrency without adding a generic heap-capable lock API.
 */
public final class StoragePhaseL1MvccRowReservationSmoke {
    private static final String DATABASE_PATH = "storage-phase-l1-mvcc-row-reservation-db";
    private static final String TABLE_NAME = "L1_ROW_RESERVATION";
    private static final String TRANSACTION_CONFLICT_SQL_STATE = "40001";

    private StoragePhaseL1MvccRowReservationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try {
            proveSourceShape();
            proveMvccSpecificRowReservation();
        } finally {
            clearProofProperties();
            DelosTableScanProviderLookup.resetFactoryLookupForTesting();
            SmokeUtils.shutdown(DATABASE_PATH);
        }
        System.out.println("storage_phase_l1_mvcc_row_reservation: PASS");
    }

    private static void proveSourceShape() throws Exception {
        String mutableAccess = read("delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        require(!mutableAccess.contains("reserveMutation("),
                "L1 must not add reserveMutation to generic DelosMutableTableAccess");
        require(!mutableAccess.contains("tryLock("),
                "L1 must not add a generic tryLock to DelosMutableTableAccess");

        String mvccReservable = read("delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMvccReservableTableAccess.java");
        require(mvccReservable.contains("interface DelosMvccReservableTableAccess extends DelosMutableTableAccess"),
                "L1 MVCC reservation must be an explicit MVCC-only capability");
        require(mvccReservable.contains("reserveMutation(")
                        && mvccReservable.contains("completeMutationReservations("),
                "L1 MVCC reservation capability must expose reserve and completion operations");

        String mvccAccess = read("delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineMvccTableAccess.java");
        require(mvccAccess.contains("implements DelosFilterableTableAccess")
                        && mvccAccess.contains("DelosMvccReservableTableAccess"),
                "EngineMvccTableAccess must be the MVCC reservation implementation");
        require(mvccAccess.contains("VersionedWriteConflictException")
                        && mvccAccess.contains("reserved for mutation by active transaction"),
                "MVCC reservation conflicts must use the existing versioned write-conflict signal");

        String heapProof = read("delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapTableAccessProof.java");
        require(!heapProof.contains("DelosMvccReservableTableAccess"),
                "EngineHeapTableAccessProof must remain proof-only and must not implement MVCC row reservation");

        String preparation = read("delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutationPreparation.java");
        require(!preparation.contains("lockAcquired"),
                "DelosMutationPreparation must not regain a lockAcquired field");
        require(!preparation.contains("reservationAcquired"),
                "DelosMutationPreparation must not claim generic reservation ownership");
    }

    private static void proveMvccSpecificRowReservation() throws Exception {
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
            DelosRowIdentity reservedUpdateIdentity = rowIdentityForId(table, 1);
            DelosRowIdentity reservedDeleteIdentity = rowIdentityForId(table, 2);

            proveDirectReservationConflict(table, reservedUpdateIdentity);
            proveReservationBlocksNativeUpdate(table, reservedUpdateIdentity);
            proveReservationBlocksNativeDelete(table, reservedDeleteIdentity);
        }
    }

    private static void proveDirectReservationConflict(
            TableDescriptor table,
            DelosRowIdentity rowIdentity) throws Exception {
        DelosNativeTableRegistry.NativeExecutionTableAccess firstWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access for APP." + TABLE_NAME));
        try {
            DelosMvccMutationReservation firstReservation = firstWriter.tableAccess().reserveMutation(
                    firstWriter.context(),
                    rowIdentity);
            require(firstReservation.reserved(),
                    "Expected first MVCC transaction to reserve the row for mutation");

            DelosNativeTableRegistry.NativeExecutionTableAccess secondWriter =
                    DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Expected second native delos_mvcc access for APP." + TABLE_NAME));
            try {
                VersionedWriteConflictException conflict = expectWriteConflict(() ->
                        secondWriter.tableAccess().reserveMutation(secondWriter.context(), rowIdentity));
                require(conflict.getMessage() != null
                                && conflict.getMessage().contains("reserved for mutation by active transaction"),
                        "Expected row-reservation conflict to explain the active reservation: "
                                + conflict.getMessage());
            } finally {
                secondWriter.abort();
            }
        } finally {
            firstWriter.abort();
        }

        DelosNativeTableRegistry.NativeExecutionTableAccess afterAbortWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access after abort for APP." + TABLE_NAME));
        try {
            require(afterAbortWriter.tableAccess().reserveMutation(
                            afterAbortWriter.context(),
                            rowIdentity).reserved(),
                    "Expected row reservation to be released after abort");
        } finally {
            afterAbortWriter.abort();
        }
    }

    private static void proveReservationBlocksNativeUpdate(
            TableDescriptor table,
            DelosRowIdentity rowIdentity) throws Exception {
        DelosNativeTableRegistry.NativeExecutionTableAccess firstWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access for APP." + TABLE_NAME));
        try {
            require(firstWriter.tableAccess().reserveMutation(firstWriter.context(), rowIdentity).reserved(),
                    "Expected first writer to reserve id=1 before UPDATE conflict proof");
            try (Connection conflictConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
                SQLException conflict = expectSqlException(() -> executePreparedUpdate(
                        conflictConnection,
                        "UPDATE APP." + TABLE_NAME + " SET value = ? WHERE id = ?",
                        "blocked-update",
                        1));
                assertTransactionConflict(conflict, "UPDATE");
            }
        } finally {
            firstWriter.abort();
        }

        try (Connection successConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(executePreparedUpdate(
                    successConnection,
                    "UPDATE APP." + TABLE_NAME + " SET value = ? WHERE id = ?",
                    "after-reservation-update",
                    1) == 1,
                    "Expected UPDATE to succeed after reservation abort");
        }
    }

    private static void proveReservationBlocksNativeDelete(
            TableDescriptor table,
            DelosRowIdentity rowIdentity) throws Exception {
        DelosNativeTableRegistry.NativeExecutionTableAccess firstWriter =
                DelosNativeTableRegistry.openNativeExecutionTableAccess(table)
                        .orElseThrow(() -> new IllegalStateException(
                                "Expected native delos_mvcc access for APP." + TABLE_NAME));
        try {
            require(firstWriter.tableAccess().reserveMutation(firstWriter.context(), rowIdentity).reserved(),
                    "Expected first writer to reserve id=2 before DELETE conflict proof");
            try (Connection conflictConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
                SQLException conflict = expectSqlException(() -> executePreparedUpdate(
                        conflictConnection,
                        "DELETE FROM APP." + TABLE_NAME + " WHERE id = ?",
                        2));
                assertTransactionConflict(conflict, "DELETE");
            }
        } finally {
            firstWriter.abort();
        }

        try (Connection successConnection = SmokeUtils.connect(DATABASE_PATH, false)) {
            require(executePreparedUpdate(
                    successConnection,
                    "DELETE FROM APP." + TABLE_NAME + " WHERE id = ?",
                    2) == 1,
                    "Expected DELETE to succeed after reservation abort");
        }
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
            throw new IllegalArgumentException("L1 row-reservation proof requires an embedded Derby connection");
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

    private static int executePreparedUpdate(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                Object value = values[i];
                int parameterIndex = i + 1;
                if (value instanceof Integer integer) {
                    statement.setInt(parameterIndex, integer.intValue());
                } else if (value instanceof String string) {
                    statement.setString(parameterIndex, string);
                } else {
                    statement.setObject(parameterIndex, value);
                }
            }
            return statement.executeUpdate();
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

    private static VersionedWriteConflictException expectWriteConflict(ReservationOperation operation) {
        try {
            operation.run();
        } catch (VersionedWriteConflictException expected) {
            return expected;
        }
        throw new IllegalStateException("Expected MVCC row-reservation write conflict but operation succeeded");
    }

    private static void assertTransactionConflict(SQLException conflict, String operation) {
        require(TRANSACTION_CONFLICT_SQL_STATE.equals(conflict.getSQLState()),
                "Expected " + operation + " conflict SQLState " + TRANSACTION_CONFLICT_SQL_STATE
                        + " but was " + conflict.getSQLState() + ": " + conflict.getMessage());
        require(conflict.getMessage() != null && conflict.getMessage().contains("delos_mvcc " + operation),
                "Expected " + operation + " conflict message to identify the native delos_mvcc mutation boundary: "
                        + conflict.getMessage());
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

    private static String read(String repoRelativePath) throws Exception {
        return Files.readString(Path.of(repoRelativePath));
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

    @FunctionalInterface
    private interface ReservationOperation {
        void run();
    }
}
