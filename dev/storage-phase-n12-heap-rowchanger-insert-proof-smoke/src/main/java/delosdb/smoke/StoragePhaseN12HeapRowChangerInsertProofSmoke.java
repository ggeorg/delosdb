package delosdb.smoke;

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
import org.apache.derby.iapi.sql.execute.RowChanger;
import org.apache.derby.iapi.store.access.DynamicCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.StaticCompiledOpenConglomInfo;
import org.apache.derby.iapi.store.access.TransactionController;
import org.apache.derby.iapi.types.RowLocation;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.iapi.types.SQLVarchar;
import org.apache.derby.impl.jdbc.EmbedConnection;

/**
 * N1.2 direct RowChanger-backed heap INSERT proof.
 *
 * <p>This smoke intentionally does not route SQL INSERT through Delos. It creates
 * an ordinary Derby heap table, inserts a row through Derby's RowChanger API
 * directly, then reads the row back through normal SQL. This proves the minimum
 * RowChanger-backed INSERT context before any heap mutation provider or SQL route
 * exists.</p>
 */
public final class StoragePhaseN12HeapRowChangerInsertProofSmoke {
    private static final String DATABASE_PATH = "storage-phase-n12-heap-rowchanger-insert-proof-db";
    private static final String TABLE = "N12_HEAP_DIRECT_INSERT";

    private StoragePhaseN12HeapRowChangerInsertProofSmoke() {
    }

    public static void main(String[] args) throws Exception {
        proveDecisionDocument();
        proveSourceShape();
        proveDirectRowChangerHeapInsert();
        proveNoHeapMutationRoutingOrProviderActivation();
        System.out.println("storage_phase_n12_heap_rowchanger_insert_proof: PASS");
    }

    private static void proveDecisionDocument() throws Exception {
        assertSourceContains(Path.of("docs/storage-phase-n12-heap-rowchanger-insert-proof.md"), List.of(
                "Storage Phase N1.2 — Direct RowChanger-backed heap INSERT proof",
                "N1.2 is a direct proof only",
                "No SQL routing change",
                "No heap mutation provider",
                "No generic Delos mutation API",
                "ordinary Derby heap table",
                "ExecutionFactory.getRowChanger(...) directly",
                "RowChanger.insertRow(...) directly",
                "RowLocation returned by RowChanger",
                "Do **not** start N2 yet"));
    }

    private static void proveSourceShape() throws Exception {
        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/ExecutionFactory.java"), List.of(
                "getRowChanger(long heapConglom",
                "StaticCompiledOpenConglomInfo heapSCOCI",
                "DynamicCompiledOpenConglomInfo heapDCOCI",
                "IndexRowGenerator[] irgs",
                "TransactionController tc",
                "Activation activation"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/iapi/sql/execute/RowChanger.java"), List.of(
                "public RowLocation insertRow(ExecRow baseRow, boolean getRL)",
                "public void deleteRow(ExecRow baseRow, RowLocation baseRowLocation)",
                "public void updateRow(ExecRow oldBaseRow"));

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"), List.of(
                "public RowLocation insertRow(ExecRow baseRow, boolean getRL)",
                "baseCC.insertAndFetchLocation(baseRow.getRowArray(), baseRowLocation)",
                "baseCC.insert(baseRow.getRowArray())",
                "isc.insert(baseRow, baseRowLocation)"));
    }

    private static void proveDirectRowChangerHeapInsert() throws Exception {
        clearProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            require(statement.executeUpdate(
                    "CREATE TABLE APP." + TABLE + " (id INT, value VARCHAR(32))") == 0,
                    "Expected ordinary heap table creation to stay Derby-owned");
            connection.commit();

            RowLocation rowLocation = insertHeapRowDirectly(connection, "APP", TABLE, 12, "rowchanger-direct");
            require(rowLocation != null, "Expected direct RowChanger INSERT to return a RowLocation proof token");
            connection.commit();
        }

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT value FROM APP." + TABLE + " WHERE id = 12")) {
                require(rows.next(), "Expected SQL read to see row inserted directly through RowChanger");
                require("rowchanger-direct".equals(rows.getString(1)),
                        "Expected RowChanger-inserted heap row to preserve values");
                require(!rows.next(), "Expected one RowChanger-inserted heap row");
            }
        }

        SmokeUtils.shutdown(DATABASE_PATH);
    }

    private static RowLocation insertHeapRowDirectly(
            Connection connection,
            String schemaName,
            String tableName,
            int id,
            String value) throws Exception {
        EmbedConnection embedConnection = (EmbedConnection) connection;
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

        ExecRow row = executionFactory.getValueRow(table.getNumberOfColumns());
        row.setColumn(1, new SQLInteger(id));
        row.setColumn(2, value == null ? new SQLVarchar() : new SQLVarchar(value));

        RowChanger rowChanger = executionFactory.getRowChanger(
                heapConglom,
                heapSCOCI,
                heapDCOCI,
                new org.apache.derby.iapi.sql.dictionary.IndexRowGenerator[0],
                new long[0],
                new StaticCompiledOpenConglomInfo[0],
                new DynamicCompiledOpenConglomInfo[0],
                0,
                tc,
                null,
                null,
                null);
        rowChanger.setIndexNames(new String[0]);
        rowChanger.open(TransactionController.MODE_RECORD);
        try {
            RowLocation rowLocation = rowChanger.insertRow(row, true);
            rowChanger.finish();
            return rowLocation;
        } finally {
            rowChanger.close();
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

        assertSourceContains(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"), List.of(
                "lcc.getLanguageConnectionFactory().getExecutionFactory()",
                ".getRowChanger(",
                "rowChanger.open(lockMode)",
                "rowChanger.insertRow(row, false)",
                "rowChanger.insertRow(row, true)"));

        assertSourceDoesNotContain(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"), List.of(
                "DelosHeapInsertResultSet",
                "EngineHeapMutableTableAccess",
                "heapInsertLiveRoute"));

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
        System.clearProperty("delosdb.storage.phaseF.nativeSelectEquality");
    }

    private static void assertSourceContains(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(source.contains(marker), path + " is missing required N1.2 marker: " + marker);
        }
    }

    private static void assertSourceDoesNotContain(Path path, List<String> markers) throws Exception {
        String source = Files.readString(path);
        for (String marker : markers) {
            require(!source.contains(marker), path + " must not contain N1.2-forbidden marker: " + marker);
        }
    }

    private static void assertFileAbsent(Path path) {
        require(!Files.exists(path), "N1.2 must not introduce " + path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
