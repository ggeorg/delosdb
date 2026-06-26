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
import org.apache.derby.impl.store.access.mvcc.MvccConglomerateController;
import org.apache.derby.impl.store.access.mvcc.MvccScanController;

/**
 * MODULE6K-1 smoke: inherited SQL INSERT restart hardening for MVCC physical
 * tables.  This is deliberately INSERT-only.  UPDATE, DELETE, predicates, and
 * indexes on MVCC tables are out of scope.
 */
public final class Module6k1InheritedSqlInsertRestartSmoke {
    private static final String DATABASE_PATH = "build/module6k-1-inherited-sql-insert-restart-db";
    private static final String INSERT_COMMIT_TABLE = "MODULE6K1_INSERT_COMMIT";
    private static final String INSERT_ROLLBACK_TABLE = "MODULE6K1_INSERT_ROLLBACK";
    private static final String HEAP_TABLE = "MODULE6K1_HEAP";

    private Module6k1InheritedSqlInsertRestartSmoke() {
    }

    public static void main(String[] args) throws Exception {
        SmokeUtils.shutdownQuietly(DATABASE_PATH);
        SmokeUtils.deleteRecursively(Path.of(DATABASE_PATH));
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        clearNativeMvccProofProperties();

        try {
            assertPlanDocumentsSmallRestartProofs();
            assertSourceKeepsInheritedInsertPath();
            createAndMutateThroughInheritedSqlInsert();
            forceRealShutdownAndClearInMemoryState();
            reopenAndAssertInheritedSqlInsertDurability();
        } finally {
            clearNativeMvccProofProperties();
            DelosNativeTableRegistry.clearRegisteredTablesForTesting();
            SmokeUtils.shutdownQuietly(DATABASE_PATH);
        }
    }

    private static void assertPlanDocumentsSmallRestartProofs() throws Exception {
        String plan = Files.readString(Path.of("docs/storage/mvcc-inherited-correctness-hardening-plan.md"));
        requireContains(plan,
                "MODULE6K-1: inherited SQL `INSERT` restart proof",
                "MODULE6K-1 plan must document the INSERT restart proof");
        requireContains(plan,
                "force a Derby database shutdown",
                "MODULE6K-1 plan must require a real database shutdown, not just connection close");
        requireContains(plan,
                "Do not start indexes",
                "MODULE6K-1 plan must keep index work out of scope");
    }

    private static void assertSourceKeepsInheritedInsertPath() throws Exception {
        String factory = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/GenericResultSetFactory.java"));
        requireNotContains(factory,
                "DelosInsertResultSet.createIfEnabled",
                "MODULE6K-1 must not reintroduce a DelosInsertResultSet factory bypass");
        requireContains(factory,
                "return new InsertResultSet(params);",
                "MODULE6K-1 must keep normal INSERT on the inherited InsertResultSet path");

        String insertResultSet = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/InsertResultSet.java"));
        requireContains(insertResultSet,
                "rowChanger.insertRow(row, false)",
                "MODULE6K-1 must keep InsertResultSet on the inherited RowChanger path");

        String rowChanger = Files.readString(Path.of(
                "delosdb-engine/src/main/java/org/apache/derby/impl/sql/execute/RowChangerImpl.java"));
        requireContains(rowChanger,
                "baseCC.insertAndFetchLocation",
                "MODULE6K-1 must keep RowChangerImpl inserting through ConglomerateController");
    }

    private static void createAndMutateThroughInheritedSqlInsert() throws Exception {
        SmokeUtils.loadEmbeddedDriver();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccScanController.resetOpenCountForTesting();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, true);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE APP." + HEAP_TABLE + "(id INT PRIMARY KEY, name VARCHAR(32))");
            statement.executeUpdate("CREATE INDEX MODULE6K1_HEAP_NAME_IDX ON APP." + HEAP_TABLE + "(name) USING btree");
            statement.executeUpdate("INSERT INTO APP." + HEAP_TABLE + " VALUES (1, 'heap')");
            SmokeUtils.assertEquals(List.of("heap"), names(statement, HEAP_TABLE),
                    "MODULE6K-1 heap and btree compatibility must stay green before restart");

            statement.executeUpdate("CREATE TABLE APP." + INSERT_COMMIT_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");
            statement.executeUpdate("CREATE TABLE APP." + INSERT_ROLLBACK_TABLE
                    + "(id INT, name VARCHAR(32)) USING delos_mvcc");

            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, INSERT_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-1 committed INSERT table must have an MVCC physical conglomerate");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, INSERT_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-1 rolled-back INSERT table must have an MVCC physical conglomerate");

            connection.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO APP." + INSERT_COMMIT_TABLE + " VALUES (101, 'committed-insert')");
            connection.commit();
            require(MvccConglomerateController.insertCountForTesting() > 0,
                    "MODULE6K-1 committed SQL INSERT must reach MvccConglomerateController through inherited RowChanger");

            statement.executeUpdate("INSERT INTO APP." + INSERT_ROLLBACK_TABLE + " VALUES (202, 'rolled-back-insert')");
            connection.rollback();
            connection.setAutoCommit(true);

            SmokeUtils.assertEquals(List.of("committed-insert"), names(statement, INSERT_COMMIT_TABLE),
                    "MODULE6K-1 committed inherited SQL INSERT must be visible before restart");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-1 pre-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(), names(statement, INSERT_ROLLBACK_TABLE),
                    "MODULE6K-1 rolled-back inherited SQL INSERT must be invisible before restart");
        }
    }

    private static void forceRealShutdownAndClearInMemoryState() throws Exception {
        SmokeUtils.shutdown(DATABASE_PATH);
        DelosNativeTableRegistry.clearRegisteredTablesForTesting();
        MvccConglomerateController.resetInsertCountForTesting();
        MvccScanController.resetOpenCountForTesting();
    }

    private static void reopenAndAssertInheritedSqlInsertDurability() throws Exception {
        clearNativeMvccProofProperties();
        SmokeUtils.loadEmbeddedDriver();

        try (Connection connection = SmokeUtils.connect(DATABASE_PATH, false);
             Statement statement = connection.createStatement()) {
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, INSERT_COMMIT_TABLE) & 0x0fL,
                    "MODULE6K-1 committed INSERT table must still be an MVCC physical conglomerate after restart");
            SmokeUtils.assertEquals((long) ConglomerateFactory.MVCC_FACTORY_ID,
                    baseConglomerateNumber(statement, INSERT_ROLLBACK_TABLE) & 0x0fL,
                    "MODULE6K-1 rolled-back INSERT table must still be an MVCC physical conglomerate after restart");

            SmokeUtils.assertEquals(List.of("committed-insert"), names(statement, INSERT_COMMIT_TABLE),
                    "MODULE6K-1 committed inherited SQL INSERT must survive Derby shutdown/reopen");
            require(MvccScanController.openCountForTesting() > 0,
                    "MODULE6K-1 post-restart SELECT must reach MvccScanController through inherited TableScanResultSet");
            SmokeUtils.assertEquals(List.of(), names(statement, INSERT_ROLLBACK_TABLE),
                    "MODULE6K-1 rolled-back inherited SQL INSERT must remain invisible after restart");
            SmokeUtils.assertEquals(List.of("heap"), names(statement, HEAP_TABLE),
                    "MODULE6K-1 heap and btree compatibility must stay green after MVCC restart proof");
        }
    }

    private static List<String> names(Statement statement, String tableName) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT name FROM APP." + tableName)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            values.sort(String::compareTo);
            return List.copyOf(values);
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

    private static void clearNativeMvccProofProperties() {
        for (String propertyName : NativeMvccProofProperties.NAMES) {
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

    private static final class NativeMvccProofProperties {
        private static final String[] NAMES = new String[] {
                "delosdb.storage.probe",
                "delosdb.storage.native.insert",
                "delosdb.storage.native.select.all",
                "delosdb.storage.native.select.eq",
                "delosdb.storage.native.select.range",
                "delosdb.storage.native.select.between",
                "delosdb.storage.native.select.null",
                "delosdb.storage.native.select.or",
                "delosdb.storage.native.select.projection.variants",
                "delosdb.storage.native.select.order.residual",
                "delosdb.storage.native.select.count",
                "delosdb.storage.native.delete.eq",
                "delosdb.storage.native.update.eq"
        };
    }
}
