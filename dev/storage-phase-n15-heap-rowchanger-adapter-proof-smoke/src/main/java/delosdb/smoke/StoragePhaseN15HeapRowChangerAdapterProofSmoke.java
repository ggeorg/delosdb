/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
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
import org.apache.derby.iapi.sql.execute.ExecutionFactory;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.services.storetypes.EngineHeapRowChangerMutationAdapter;

/**
 * N1.5 direct proof for the narrow internal heap RowChanger mutation adapter.
 */
public final class StoragePhaseN15HeapRowChangerAdapterProofSmoke {
    private static final String DATABASE_PATH = "storage-phase-n15-heap-rowchanger-adapter-proof-db";
    private static final String TABLE = "N15_HEAP_ADAPTER_MUTATION";

    private StoragePhaseN15HeapRowChangerAdapterProofSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveAdapterSourceShape();
        provePriorGuardAllowsAdapterButStillBlocksSqlRouting();
        proveAdapterBackedHeapInsertUpdateDelete();
        proveNoHeapDeleteUpdateSqlRoutingOrProviderActivation();
        proveGradleWiring();
        System.out.println("storage_phase_n15_heap_rowchanger_adapter_proof: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n15-heap-rowchanger-adapter-proof.md"), List.of(
                "Storage Phase N1.5 — Heap RowChanger mutation adapter direct proof",
                "EngineHeapRowChangerMutationAdapter",
                "insert(ExecRow row) -> RowLocation",
                "update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)",
                "delete(ExecRow row, RowLocation rowLocation)",
                "No SQL routing change",
                "No EngineHeapMutableTableAccess",
                "No generic DelosMutableTableAccess.tryLock(...)",
                "Do **not** start N2 yet",
                "Do **not** start N3 yet",
                "N1.6 — adapter-backed heap mutation classification decision"));
    }

    private static void proveAdapterSourceShape() throws Exception {
        Path adapter = Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapRowChangerMutationAdapter.java");
        assertSourceContains(adapter, List.of(
                "public final class EngineHeapRowChangerMutationAdapter implements AutoCloseable",
                "private final RowChanger rowChanger",
                "public static EngineHeapRowChangerMutationAdapter open(",
                "ExecutionFactory executionFactory",
                "StaticCompiledOpenConglomInfo heapSCOCI",
                "DynamicCompiledOpenConglomInfo heapDCOCI",
                "IndexRowGenerator[] irgs",
                "TransactionController tc",
                "FormatableBitSet baseRowReadList",
                "Activation activation",
                "rowChanger.open(lockMode)",
                "public RowLocation insert(ExecRow row)",
                "rowChanger.insertRow",
                "public void update(ExecRow oldRow, ExecRow newRow, RowLocation rowLocation)",
                "rowChanger.updateRow",
                "public void delete(ExecRow row, RowLocation rowLocation)",
                "rowChanger.deleteRow",
                "rowChanger.finish()",
                "rowChanger.close()",
                "Do not wire this adapter from GenericResultSetFactory"));

        assertSourceNotContains(adapter, List.of(
                "implements DelosMutableTableAccess",
                "tryLock(",
                "reserveMutation(",
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet"));
    }

    private static void provePriorGuardAllowsAdapterButStillBlocksSqlRouting() throws Exception {
        assertSourceContains(Path.of(
                "dev/storage-phase-n14-heap-rowchanger-adapter-decision-smoke/src/main/java/delosdb/smoke/StoragePhaseN14HeapRowChangerAdapterDecisionSmoke.java"), List.of(
                "EngineHeapRowChangerMutationAdapter",
                "if (Files.exists(adapterPath))",
                "must remain internal-only",
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet"));
    }

    private static void proveAdapterBackedHeapInsertUpdateDelete() throws Exception {
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

                mutateHeapRowsThroughAdapter(connection, "APP", TABLE);
                connection.commit();
            } catch (Exception e) {
                rollbackQuietly(connection);
                throw e;
            }
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            assertSingleValue(statement, 51, "adapter-inserted");
            assertSingleValue(statement, 52, "adapter-updated");
            try (ResultSet rows = statement.executeQuery(
                    "SELECT value FROM APP." + TABLE + " WHERE id = 53")) {
                require(!rows.next(), "Expected adapter-deleted heap row to be absent");
            }
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM APP." + TABLE)) {
                require(rows.next(), "Expected count row");
                require(rows.getInt(1) == 2, "Expected inserted and updated rows only");
            }
        }

        SmokeUtils.shutdown(DATABASE_PATH);
    }

    private static void mutateHeapRowsThroughAdapter(
            Connection connection,
            String schemaName,
            String tableName) throws Exception {
        EmbedConnection embedConnection = (EmbedConnection) connection;
        setupEmbedContextStack(embedConnection);
        try {
            RowChangerContext context = rowChangerContext(embedConnection, schemaName, tableName);
            try (EngineHeapRowChangerMutationAdapter adapter = EngineHeapRowChangerMutationAdapter.open(
                    context.executionFactory,
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
                    null,
                    null,
                    null,
                    new String[0],
                    TransactionController.MODE_RECORD)) {
                ExecRow inserted = row(context.executionFactory, context.table, 51, "adapter-inserted");
                RowLocation insertLocation = adapter.insert(inserted);
                require(insertLocation != null, "Expected adapter INSERT to return RowLocation");

                ExecRow oldUpdated = row(context.executionFactory, context.table, 52, "adapter-before");
                RowLocation updateLocation = adapter.insert(oldUpdated);
                require(updateLocation != null, "Expected adapter update seed to return RowLocation");
                ExecRow newUpdated = row(context.executionFactory, context.table, 52, "adapter-updated");
                adapter.update(oldUpdated, newUpdated, updateLocation);

                ExecRow deleted = row(context.executionFactory, context.table, 53, "adapter-delete-me");
                RowLocation deleteLocation = adapter.insert(deleted);
                require(deleteLocation != null, "Expected adapter delete seed to return RowLocation");
                adapter.delete(deleted, deleteLocation);

                adapter.finish();
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

    private static ExecRow row(ExecutionFactory executionFactory, TableDescriptor table, int id, String value) {
        ExecRow row = executionFactory.getValueRow(table.getNumberOfColumns());
        row.setColumn(1, new SQLInteger(id));
        row.setColumn(2, value == null ? new SQLVarchar() : new SQLVarchar(value));
        return row;
    }

    private static void assertSingleValue(Statement statement, int id, String expected) throws Exception {
        try (ResultSet rows = statement.executeQuery(
                "SELECT value FROM APP." + TABLE + " WHERE id = " + id)) {
            require(rows.next(), "Expected row id " + id);
            require(expected.equals(rows.getString(1)),
                    "Expected row id " + id + " to have value " + expected);
            require(!rows.next(), "Expected one row for id " + id);
        }
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

    private static void proveNoHeapDeleteUpdateSqlRoutingOrProviderActivation() throws Exception {
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapDeleteResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DelosHeapUpdateResultSet.java"));
        assertPathMissing(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/services/storetypes/EngineHeapMutableTableAccess.java"));

        assertSourceNotContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"), List.of(
                "DelosHeapDeleteResultSet",
                "DelosHeapUpdateResultSet",
                "EngineHeapMutableTableAccess",
                "heapDeleteLiveRoute",
                "heapUpdateLiveRoute"));

        assertSourceNotContains(Path.of(
                "delosdb-engine/src/main/java/io/github/ggeorg/delosdb/engine/extension/storage/versioned/sql/DelosNativeTableRegistry.java"), List.of(
                "EngineHeapMutableTableAccess",
                "EngineHeapRowChangerMutationAdapter"));

        assertSourceNotContains(Path.of(
                "delosdb-engine-kernel/src/main/java/org/apache/derby/iapi/store/types/DelosMutableTableAccess.java"), List.of(
                "tryLock(",
                "reserveMutation(",
                "RowChangerImpl",
                "ConglomerateController"));
    }

    private static void proveGradleWiring() throws Exception {
        assertSourceContains(Path.of("gradle/storage-native-execution-closeout.gradle"), List.of(
                "storage-phase-n15-heap-rowchanger-adapter-proof-smoke",
                "compileStoragePhaseN15HeapRowChangerAdapterProofSmoke",
                "storagePhaseN15HeapRowChangerAdapterProofSmoke",
                "verifyStoragePhaseN15HeapRowChangerAdapterProof",
                "dependsOn         : 'verifyStoragePhaseN14HeapRowChangerAdapterDecision'",
                "Verified N1.5 heap RowChanger adapter proof without heap mutation SQL routing"));

        assertSourceContains(Path.of("gradle/delosdb-permanent-storage-guards.gradle"), List.of(
                "through N1.5 heap RowChanger adapter proof"));

        assertSourceContains(Path.of("scripts/delete-stale-storage-smoke-dbs.sh"), List.of(
                "storage-phase-n15-heap-rowchanger-adapter-proof-db"));
    }

    private static void clearProofProperties() {
        System.clearProperty("delosdb.storage.phaseM.heapScanShadow");
        System.clearProperty("delosdb.storage.phaseM3.heapSelectLiveRoute");
        System.clearProperty("delosdb.storage.phaseF.nativeInsert");
        System.clearProperty("delosdb.storage.phaseF.nativeDeleteEquality");
        System.clearProperty("delosdb.storage.phaseF.nativeUpdateEquality");
        System.clearProperty("delosdb.storage.phaseF.nativeSelectEquality");
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Preserve the original proof failure.
        }
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = read(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.5 marker: " + marker);
        }
    }

    private static void assertSourceNotContains(Path path, List<String> forbiddenMarkers) throws Exception {
        String source = read(path);
        for (String marker : forbiddenMarkers) {
            require(!source.contains(marker), path + " contains forbidden N1.5 marker: " + marker);
        }
    }

    private static void assertPathMissing(Path path) {
        require(!Files.exists(path), "N1.5 must not introduce premature heap mutation SQL route/provider file: " + path);
    }

    private static String read(Path path) throws Exception {
        require(Files.exists(path), "Missing source path: " + path);
        return Files.readString(path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
