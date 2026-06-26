package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE6H smoke: normal Derby SQL DELETE reaches MVCC through the inherited
 * DeleteResultSet -> RowChangerImpl -> MvccConglomerateController path.
 *
 * <p>This pass intentionally proves unqualified DELETE on one-row MVCC tables.
 * Store qualifier evaluation and UPDATE/replace semantics remain separate
 * follow-up work.</p>
 */
public final class Module6hInheritedSqlDeleteMvccControllerSmoke {
    private static final String DATABASE_PATH = "build/module6h-inherited-sql-delete-mvcc-controller-db";
    private static final String MVCC_DELETE_TABLE = "MODULE6H_DELETE";
    private static final String MVCC_ROLLBACK_TABLE = "MODULE6H_ROLLBACK";
    private static final String MVCC_ACTIVE_TABLE = "MODULE6H_ACTIVE";
    private static final String HEAP_TABLE = "MODULE6H_HEAP";

    private Module6hInheritedSqlDeleteMvccControllerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertSourceInheritedDeleteRoute();
            assertRuntimeInheritedSqlDelete();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceInheritedDeleteRoute() throws Exception {
        String factory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        requireNotContains(factory,
                "DelosDeleteResultSet.createIfEnabled",
                "MODULE6H/MODULE6I must not use a DelosDeleteResultSet factory bypass for MVCC DELETE");
        requireContains(factory,
                "return new DeleteResultSet(source, activation );",
                "MODULE6H must keep normal DELETE on the inherited DeleteResultSet path");

        String deleteResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/DeleteResultSet.java"));
        requireContains(deleteResultSet,
                "rc.deleteRow(row,baseRowLocation)",
                "MODULE6H must keep DeleteResultSet on the inherited RowChanger DELETE path");

        String rowChanger = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"));
        requireContains(rowChanger,
                "baseCC.delete(baseRowLocation)",
                "MODULE6H must keep RowChangerImpl deleting through ConglomerateController.delete");

        String scanController = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java"));
        requireContains(scanController,
                "copyCurrentRowLocation(destRow)",
                "MODULE6H requires inherited scans to materialize MVCC RowLocation columns for DELETE");

        String controller = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateController.java"));
        requireContains(controller,
                "deleteCountForTesting",
                "MODULE6H smoke must prove runtime SQL DELETE reached MvccConglomerateController");
        requireContains(controller,
                "state.table().delete(location.rowId(), transaction, snapshot, state.transactions())",
                "MODULE6H DELETE must mutate through the MVCC table using the inherited RowLocation");
    }

    private static void assertRuntimeInheritedSqlDelete() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6H_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("DELETE FROM APP." + HEAP_TABLE + " WHERE id = 1");
            SmokeUtils.assertEquals(List.of(), ids(statement, HEAP_TABLE),
                    "heap DELETE and btree must remain green while switching MVCC DELETE to inherited RowChanger");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_DELETE_TABLE + "(id INT) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, MVCC_DELETE_TABLE) & 0x0fL,
                    "MODULE6H requires delos_mvcc table to have an MVCC physical conglomerate");
            statement.executeUpdate("INSERT INTO APP." + MVCC_DELETE_TABLE + " VALUES (101)");
            SmokeUtils.assertEquals(List.of(101), ids(statement, MVCC_DELETE_TABLE),
                    "MODULE6G inherited SQL INSERT must seed the MODULE6H DELETE proof");

            MvccConglomerateController.resetDeleteCountForTesting();
            statement.executeUpdate("DELETE FROM APP." + MVCC_DELETE_TABLE);
            require(MvccConglomerateController.deleteCountForTesting() > 0,
                    "normal SQL DELETE must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of(), ids(statement, MVCC_DELETE_TABLE),
                    "normal SQL SELECT must not see the committed row deleted through inherited MVCC store/access");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_ROLLBACK_TABLE + "(id INT) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + MVCC_ROLLBACK_TABLE + " VALUES (202)");
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("DELETE FROM APP." + MVCC_ROLLBACK_TABLE);
                connection.rollback();
                connection.setAutoCommit(true);
                SmokeUtils.assertEquals(List.of(202), ids(statement, MVCC_ROLLBACK_TABLE),
                        "rolled-back SQL DELETE must leave the MVCC row visible through inherited scan");
            } finally {
                connection.setAutoCommit(true);
            }

            try (Connection activeWriter = SmokeUtils.connect(DATABASE_PATH, false);
                 Statement activeStatement = activeWriter.createStatement();
                 Connection reader = SmokeUtils.connect(DATABASE_PATH, false);
                 Statement readerStatement = reader.createStatement()) {
                activeStatement.executeUpdate("CREATE TABLE APP." + MVCC_ACTIVE_TABLE + "(id INT) USING delos_mvcc");
                activeStatement.executeUpdate("INSERT INTO APP." + MVCC_ACTIVE_TABLE + " VALUES (303)");
                activeWriter.setAutoCommit(false);
                activeStatement.executeUpdate("DELETE FROM APP." + MVCC_ACTIVE_TABLE);
                require(ids(readerStatement, MVCC_ACTIVE_TABLE).contains(303),
                        "active SQL DELETE must be invisible to another Derby transaction");
                activeWriter.commit();
                require(!ids(readerStatement, MVCC_ACTIVE_TABLE).contains(303),
                        "committed SQL DELETE must become visible to another Derby transaction");
            }

            require(MvccStoreAccessTransactionRegistry.pendingCountForTesting(
                    connection.unwrap(EmbedConnection.class)
                            .getLanguageConnection()
                            .getTransactionExecute()) == 0,
                    "MODULE6H must not leak pending MVCC store/access writers after commit/rollback");
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        List<Integer> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            while (rows.next()) {
                ids.add(rows.getInt(1));
            }
        }
        ids.sort(Integer::compareTo);
        return List.copyOf(ids);
    }

    private static long baseConglomerateNumber(Statement statement, String tableName) throws Exception {
        String sql = "SELECT c.CONGLOMERATENUMBER "
                + "FROM SYS.SYSCONGLOMERATES c, SYS.SYSTABLES t "
                + "WHERE c.TABLEID = t.TABLEID "
                + "AND c.ISINDEX = FALSE "
                + "AND t.TABLENAME = '" + tableName + "'";
        try (ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) {
                throw new AssertionError("Missing base conglomerate for " + tableName);
            }
            long value = rows.getLong(1);
            if (rows.next()) {
                throw new AssertionError("More than one base conglomerate for " + tableName);
            }
            return value;
        }
    }

    private static void assertNativeRoutePropertiesAreNotSet() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            if (Boolean.getBoolean(propertyName)) {
                throw new AssertionError("MODULE6H must not rely on old native proof property: " + propertyName);
            }
        }
    }

    private static void clearNativeRouteProperties() {
        for (String propertyName : NativeRouteProperties.NAMES) {
            System.clearProperty(propertyName);
        }
    }

    private static void requireContains(String source, String expected, String label) {
        if (source == null || !source.contains(expected)) {
            throw new AssertionError(label + " expected source to contain: " + expected);
        }
    }

    private static void requireNotContains(String source, String unexpected, String label) {
        if (source != null && source.contains(unexpected)) {
            throw new AssertionError(label + " unexpected source content: " + unexpected);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static final class NativeRouteProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.phaseF3.tableScanBranchProbe",
                "delosdb.storage.phaseF5.nativeMvccInsert",
                "delosdb.storage.phaseG3.nativeSelectAll",
                "delosdb.storage.phaseF4.nativeMvccSelectEquality",
                "delosdb.storage.phaseG1.nativeRangePredicates",
                "delosdb.storage.phaseG2.nativeBetweenPredicates",
                "delosdb.storage.phaseL31.nativeNullPredicates",
                "delosdb.storage.phaseL33.nativeOrPredicateResidual",
                "delosdb.storage.phaseL34.nativeProjectionVariants",
                "delosdb.storage.phaseL35.nativeOrderByResidual",
                "delosdb.storage.phaseG4.nativeCountAggregate",
                "delosdb.storage.phaseF6.nativeMvccDeleteEquality",
                "delosdb.storage.phaseF7.nativeMvccUpdateEquality"
        };
    }
}
