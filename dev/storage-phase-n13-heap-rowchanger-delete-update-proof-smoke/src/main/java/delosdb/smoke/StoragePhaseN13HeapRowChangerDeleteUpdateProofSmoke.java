package delosdb.smoke;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.apache.derby.iapi.sql.conn.LanguageConnectionContext;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.SchemaDescriptor;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.ExecutionFactory;
import org.apache.derby.iapi.sql.execute.RowChanger;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;

/**
 * N1.3 direct RowChanger-backed heap DELETE / UPDATE proof.
 *
 * <p>This smoke intentionally does not route SQL DELETE or UPDATE through Delos.
 * It creates an ordinary Derby heap table, drives UPDATE and DELETE through
 * Derby's RowChanger API directly, and then verifies the final contents through
 * ordinary SQL reads.</p>
 */
public final class StoragePhaseN13HeapRowChangerDeleteUpdateProofSmoke {
    private static final String DATABASE_PATH = "storage-phase-n13-heap-rowchanger-delete-update-proof-db";
    private static final String TABLE = "N13_HEAP_DIRECT_DELETE_UPDATE";

    private StoragePhaseN13HeapRowChangerDeleteUpdateProofSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveSourceShape();
        proveDirectRowChangerHeapUpdateAndDelete();
        proveNoHeapMutationRoutingOrProviderActivation();
        System.out.println("storage_phase_n13_heap_rowchanger_delete_update_proof: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n13-heap-rowchanger-delete-update-proof.md"), List.of(
                "Storage Phase N1.3 — Direct RowChanger-backed heap DELETE / UPDATE proof",
                "N1.3 is a direct proof only",
                "No SQL routing change",
                "No heap mutation provider",
                "No generic Delos mutation API",
                "RowChanger.updateRow(...) directly",
                "RowChanger.deleteRow(...) directly",
                "RowLocation",
                "Do **not** start N2 yet",
                "Do **not** start N3 yet"));
    }

    private static void proveSourceShape() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/RowChanger.java"), List.of(
                "public void deleteRow(ExecRow baseRow, RowLocation baseRowLocation)",
                "public void updateRow(ExecRow oldBaseRow",
                "ExecRow newBaseRow",
                "RowLocation baseRowLocation"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"), List.of(
                "public void deleteRow(ExecRow baseRow, RowLocation baseRowLocation)",
                "baseCC.delete(baseRowLocation)",
                "public void updateRow(ExecRow oldBaseRow",
                "baseCC.replace(baseRowLocation",
                "sparseRowArray"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"), List.of(
                "getRowChanger(",
                "rc.open(lockMode)",
                "rc.deleteRow(row,baseRowLocation)"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"), List.of(
                "getRowChanger( heapConglom",
                "rowChanger.open(lockMode)",
                "rowChanger.updateRow"));
    }

    private static void proveDirectRowChangerHeapUpdateAndDelete() throws Exception {
        clearProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                require(statement.executeUpdate(
                        "CREATE TABLE APP." + TABLE + " (id INT, value VARCHAR(32))") == 0,
                        "Expected ordinary heap table creation to stay Derby-owned");
                connection.commit();

                updateHeapRowDirectly(connection, "APP", TABLE,
                        31, "update-before", "update-after");
                deleteHeapRowDirectly(connection, "APP", TABLE,
                        32, "delete-me");
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT value FROM APP." + TABLE + " WHERE id = 31")) {
                require(rows.next(), "Expected direct RowChanger UPDATE row to remain visible");
                require("update-after".equals(rows.getString(1)),
                        "Expected RowChanger-updated heap row to preserve new value");
                require(!rows.next(), "Expected one updated heap row");
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT value FROM APP." + TABLE + " WHERE id = 32")) {
                require(!rows.next(), "Expected direct RowChanger DELETE row to be absent");
            }

            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM APP." + TABLE)) {
                require(rows.next(), "Expected count row");
                require(rows.getInt(1) == 1, "Expected only the updated row to remain after direct delete");
            }
        }

        SmokeUtils.shutdown(DATABASE_PATH);
    }

    private static void updateHeapRowDirectly(
            Connection connection,
            String schemaName,
            String tableName,
            int id,
            String before,
            String after) throws Exception {
        EmbedConnection embedConnection = (EmbedConnection) connection;
        setupEmbedContextStack(embedConnection);
        try {
            RowChangerContext context = rowChangerContext(embedConnection, schemaName, tableName);
            RowChanger rowChanger = newRowChanger(context);
            rowChanger.open(TransactionController.MODE_RECORD);
            try {
                ExecRow oldRow = row(context.executionFactory, context.table, id, before);
                ExecRow newRow = row(context.executionFactory, context.table, id, after);
                RowLocation rowLocation = rowChanger.insertRow(oldRow, true);
                require(rowLocation != null, "Expected RowChanger INSERT seed to return a RowLocation");
                rowChanger.updateRow(oldRow, newRow, rowLocation);
                rowChanger.finish();
            } finally {
                rowChanger.close();
            }
        } finally {
            restoreEmbedContextStack(embedConnection);
        }
    }

    private static void deleteHeapRowDirectly(
            Connection connection,
            String schemaName,
            String tableName,
            int id,
            String value) throws Exception {
        EmbedConnection embedConnection = (EmbedConnection) connection;
        setupEmbedContextStack(embedConnection);
        try {
            RowChangerContext context = rowChangerContext(embedConnection, schemaName, tableName);
            RowChanger rowChanger = newRowChanger(context);
            rowChanger.open(TransactionController.MODE_RECORD);
            try {
                ExecRow row = row(context.executionFactory, context.table, id, value);
                RowLocation rowLocation = rowChanger.insertRow(row, true);
                require(rowLocation != null, "Expected RowChanger INSERT seed to return a RowLocation");
                rowChanger.deleteRow(row, rowLocation);
                rowChanger.finish();
            } finally {
                rowChanger.close();
            }
        } finally {
            restoreEmbedContextStack(embedConnection);
        }
    }

    private static RowChangerContext rowChangerContext(
            EmbedConnection embedConnection,
            String schemaName,
            String tableName) throws Exception {
        LanguageConnectionContext lcc = embedConnection.getLanguageConnection();
        TransactionController tc = lcc.getTransactionExecute();
        DataDictionary dd = lcc.getDataDictionary();
        SchemaDescriptor schema = dd.getSchemaDescriptor(schemaName, tc, true);
        TableDescriptor table = dd.getTableDescriptor(tableName, schema, tc);
        require(table != null, "Expected table descriptor for " + schemaName + "." + tableName);

        long heapConglom = table.getHeapConglomerateId();
        StaticCompiledOpenConglomInfo heapSCOCI = tc.getStaticCompiledConglomInfo(heapConglom);
        DynamicCompiledOpenConglomInfo heapDCOCI = tc.getDynamicCompiledConglomInfo(heapConglom);
        ExecutionFactory executionFactory = lcc.getLanguageConnectionFactory().getExecutionFactory();
        return new RowChangerContext(table, tc, executionFactory, heapConglom, heapSCOCI, heapDCOCI);
    }

    private static RowChanger newRowChanger(RowChangerContext context) throws Exception {
        RowChanger rowChanger = context.executionFactory.getRowChanger(
                context.heapConglom,
                context.heapSCOCI,
                context.heapDCOCI,
                new org.apache.derby.iapi.sql.dictionary.IndexRowGenerator[0],
                new long[0],
                new StaticCompiledOpenConglomInfo[0],
                new DynamicCompiledOpenConglomInfo[0],
                context.table.getNumberOfColumns(),
                context.tc,
                null,
                null,
                null);
        rowChanger.setIndexNames(new String[0]);
        return rowChanger;
    }

    private static ExecRow row(ExecutionFactory executionFactory, TableDescriptor table, int id, String value) {
        ExecRow row = executionFactory.getValueRow(table.getNumberOfColumns());
        row.setColumn(1, new SQLInteger(id));
        row.setColumn(2, value == null ? new SQLVarchar() : new SQLVarchar(value));
        return row;
    }

    private record RowChangerContext(
            TableDescriptor table,
            TransactionController tc,
            ExecutionFactory executionFactory,
            long heapConglom,
            StaticCompiledOpenConglomInfo heapSCOCI,
            DynamicCompiledOpenConglomInfo heapDCOCI) {
    }

    private static void setupEmbedContextStack(EmbedConnection connection) throws Exception {
        invokeEmbedContextMethod(connection, "setupContextStack");
    }

    private static void restoreEmbedContextStack(EmbedConnection connection) throws Exception {
        invokeEmbedContextMethod(connection, "restoreContextStack");
    }

    private static void invokeEmbedContextMethod(EmbedConnection connection, String methodName) throws Exception {
        Method method = EmbedConnection.class.getDeclaredMethod(methodName);
        method.setAccessible(true);
        try {
            method.invoke(connection);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original proof failure.
        }
    }

    private static void proveNoHeapMutationRoutingOrProviderActivation() throws Exception {
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapInsertResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));
        assertFileAbsent(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));

        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"), List.of(
                "DelosHeapInsertResultSet",
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet",
                "EngineHeapMutableTableAccess",
                "heapInsertLiveRoute",
                "heapDeleteLiveRoute",
                "heapUpdateLiveRoute"));

        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapMutableTableAccess",
                "EngineHeapTableAccessLiveCandidate"));

        Path mutableContract = Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java");
        if (Files.exists(mutableContract)) {
            assertSourceDoesNotContain(mutableContract, List.of(
                    "tryLock(",
                    "reserveMutation(",
                    "RowChangerImpl",
                    "ConglomerateController"));
        }
    }

    private static void clearProofProperties() {
        System.clearProperty("delosdb.storage.phaseM.heapScanShadow");
        System.clearProperty("delosdb.storage.phaseM3.heapSelectLiveRoute");
        System.clearProperty("delosdb.storage.phaseF.nativeInsert");
        System.clearProperty("delosdb.storage.phaseF.nativeDeleteEquality");
        System.clearProperty("delosdb.storage.phaseF.nativeUpdateEquality");
        System.clearProperty("delosdb.storage.phaseF.nativeSelectEquality");
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.3 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(!source.contains(marker), path + " must not contain N1.3-forbidden marker: " + marker);
        }
    }

    private static void assertFileAbsent(Path path) {
        require(!Files.exists(path), "N1.3 must not introduce " + path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
