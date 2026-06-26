package delosdb.smoke;

import io.github.ggeorg.delosdb.engine.extension.storage.versioned.sql.DelosNativeTableRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.apache.derby.iapi.store.access.conglomerate.ConglomerateFactory;
import org.apache.derby.impl.jdbc.EmbedConnection;
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccStoreAccessTransactionRegistry;

/**
 * MODULE6H smoke: normal Derby SQL UPDATE reaches MVCC through the inherited
 * UpdateResultSet -> RowChangerImpl -> MvccConglomerateController path.
 *
 * <p>This pass intentionally proves unqualified UPDATE on one-row MVCC tables.
 * Store qualifier evaluation and index-backed mutation remain separate work.</p>
 */
public final class Module6hInheritedSqlUpdateMvccControllerSmoke {
    private static final String DATABASE_PATH = "build/module6h-inherited-sql-update-mvcc-controller-db";
    private static final String MVCC_UPDATE_TABLE = "MODULE6H_UPDATE";
    private static final String MVCC_ROLLBACK_TABLE = "MODULE6H_UPD_RB";
    private static final String MVCC_ACTIVE_TABLE = "MODULE6H_UPD_ACTIVE";
    private static final String HEAP_TABLE = "MODULE6H_UPD_HEAP";

    private Module6hInheritedSqlUpdateMvccControllerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeRouteProperties();

        try {
            assertSourceInheritedUpdateRoute();
            assertRuntimeInheritedSqlUpdate();
            assertNativeRoutePropertiesAreNotSet();
        } finally {
            clearNativeRouteProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertSourceInheritedUpdateRoute() throws Exception {
        String factory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        requireNotContains(factory,
                "DelosUpdateResultSet.createIfEnabled",
                "MODULE6H/MODULE6I must not use a DelosUpdateResultSet factory bypass for MVCC UPDATE");
        requireContains(factory,
                "return new UpdateResultSet(UpdateResultSetParameters.normal(",
                "MODULE6H UPDATE must keep normal UPDATE on the inherited UpdateResultSet path");

        String updateResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/UpdateResultSet.java"));
        requireContains(updateResultSet,
                "rowChanger.updateRow(row,newBaseRow,baseRowLocation)",
                "MODULE6H UPDATE must keep UpdateResultSet on the inherited RowChanger UPDATE path");

        String rowChanger = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"));
        requireContains(rowChanger,
                "baseCC.replace(baseRowLocation",
                "MODULE6H UPDATE must keep RowChangerImpl updating through ConglomerateController.replace");

        String scanController = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccScanController.java"));
        requireContains(scanController,
                "copyCurrentRowLocation(destRow)",
                "MODULE6H UPDATE requires inherited scans to materialize MVCC RowLocation columns for UPDATE");

        String controller = Files.readString(Path.of(
                "delosdb-storage-derby/src/main/java/org/apache/derby/impl/store/access/mvcc/MvccConglomerateController.java"));
        requireContains(controller,
                "updateCountForTesting",
                "MODULE6H UPDATE smoke must prove runtime SQL UPDATE reached MvccConglomerateController");
        requireContains(controller,
                "state.table().update(",
                "MODULE6H UPDATE must mutate through the MVCC table using the inherited RowLocation");
        requireContains(controller,
                "replacementRow(visible.get(), row, validColumns)",
                "MODULE6H UPDATE must merge sparse RowChanger updates with the current MVCC row");
    }

    private static void assertRuntimeInheritedSqlUpdate() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6H_UPD_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            statement.executeUpdate("UPDATE APP." + HEAP_TABLE + " SET name = 'heap2' WHERE id = 1");
            SmokeUtils.assertEquals(List.of("heap2"), names(statement, HEAP_TABLE),
                    "heap UPDATE and btree must remain green while switching MVCC UPDATE to inherited RowChanger");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_UPDATE_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, MVCC_UPDATE_TABLE) & 0x0fL,
                    "MODULE6H UPDATE requires delos_mvcc table to have an MVCC physical conglomerate");
            statement.executeUpdate("INSERT INTO APP." + MVCC_UPDATE_TABLE + " VALUES (101, 'before')");
            SmokeUtils.assertEquals(List.of("before"), names(statement, MVCC_UPDATE_TABLE),
                    "MODULE6G inherited SQL INSERT must seed the MODULE6H UPDATE proof");

            MvccConglomerateController.resetUpdateCountForTesting();
            statement.executeUpdate("UPDATE APP." + MVCC_UPDATE_TABLE + " SET name = 'after'");
            require(MvccConglomerateController.updateCountForTesting() > 0,
                    "normal SQL UPDATE must reach MvccConglomerateController through inherited RowChanger");
            SmokeUtils.assertEquals(List.of("after"), names(statement, MVCC_UPDATE_TABLE),
                    "normal SQL SELECT must see the committed row updated through inherited MVCC store/access");
            SmokeUtils.assertEquals(List.of(101), ids(statement, MVCC_UPDATE_TABLE),
                    "sparse inherited UPDATE must preserve non-updated MVCC columns");

            statement.executeUpdate("CREATE TABLE APP." + MVCC_ROLLBACK_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("INSERT INTO APP." + MVCC_ROLLBACK_TABLE + " VALUES (202, 'before')");
            connection.setAutoCommit(false);
            try {
                statement.executeUpdate("UPDATE APP." + MVCC_ROLLBACK_TABLE + " SET name = 'rolledback'");
                connection.rollback();
                connection.setAutoCommit(true);
                SmokeUtils.assertEquals(List.of("before"), names(statement, MVCC_ROLLBACK_TABLE),
                        "rolled-back SQL UPDATE must leave the MVCC row visible with its previous values");
            } finally {
                connection.setAutoCommit(true);
            }

            try (Connection activeWriter = SmokeUtils.connect(DATABASE_PATH, false);
                 Statement activeStatement = activeWriter.createStatement();
                 Connection reader = SmokeUtils.connect(DATABASE_PATH, false);
                 Statement readerStatement = reader.createStatement()) {
                activeStatement.executeUpdate("CREATE TABLE APP." + MVCC_ACTIVE_TABLE + "(id INT, name VARCHAR(32)) USING delos_mvcc");
                activeStatement.executeUpdate("INSERT INTO APP." + MVCC_ACTIVE_TABLE + " VALUES (303, 'before')");
                activeWriter.setAutoCommit(false);
                activeStatement.executeUpdate("UPDATE APP." + MVCC_ACTIVE_TABLE + " SET name = 'active'");
                SmokeUtils.assertEquals(List.of("before"), names(readerStatement, MVCC_ACTIVE_TABLE),
                        "active SQL UPDATE must be invisible to another Derby transaction");
                activeWriter.commit();
                SmokeUtils.assertEquals(List.of("active"), names(readerStatement, MVCC_ACTIVE_TABLE),
                        "committed SQL UPDATE must become visible to another Derby transaction");
            }

            require(MvccStoreAccessTransactionRegistry.pendingCountForTesting(
                    connection.unwrap(EmbedConnection.class)
                            .getLanguageConnection()
                            .getTransactionExecute()) == 0,
                    "MODULE6H UPDATE must not leak pending MVCC store/access writers after commit/rollback");
        }
    }

    private static List<Integer> ids(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT id FROM APP." + tableName)) {
            if (!rows.next()) {
                return List.of();
            }
            int value = rows.getInt(1);
            if (rows.next()) {
                throw new AssertionError("Expected one row in " + tableName);
            }
            return List.of(value);
        }
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + tableName)) {
            if (!rows.next()) {
                return List.of();
            }
            String value = rows.getString(1);
            if (rows.next()) {
                throw new AssertionError("Expected one row in " + tableName);
            }
            return List.of(value);
        }
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
                throw new AssertionError("MODULE6H UPDATE must not rely on old native proof property: " + propertyName);
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
