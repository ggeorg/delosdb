/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2C1HistoryUpdateTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;

import org.apache.derby.iapi.services.io.FormatableBitSet;

/** First Gen2-C history slice: update current in place and archive only the predecessor. */
public final class MvccGen2C1HistoryUpdateTest extends MvccSqlTestSupport {
    private static final String GEN2_C1_PROPERTY =
            "delosdb.experimental.mvccGen2C1.history.enabled";

    public void testBareUpdateHistoryVisibilityRollbackAndReopen() throws Exception {
        String previous = System.getProperty(GEN2_C1_PROPERTY);
        String database = databaseName("mvcc-gen2-c1-history-update");
        try {
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table G2_C1_T (id int not null, payload varchar(128) not null) "
                                + "using delos_mvcc");
                executeUpdate(setup, "insert into G2_C1_T values (1, 'v1')");
                setup.commit();
                assertPhysicalShape(setup, 1, 0);
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection observer = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertPayload(historical, "v1");
                assertPayload(observer, "v1");
                observer.commit();

                assertEquals(1, executeUpdate(
                        writer, "update G2_C1_T set payload = 'v2' where id = 1"));
                assertPayload(writer, "v2");

                // The current record is already the writer's uncommitted v2 image.
                // A foreign reader must traverse the archived v1 predecessor instead.
                assertPayload(observer, "v1");
                observer.commit();

                writer.commit();
                assertPhysicalShape(writer, 1, 1);
                writer.commit();

                assertPayload(historical, "v1");
                historical.commit();
                assertPayload(historical, "v2");
                historical.commit();

                assertPayload(observer, "v2");
                observer.commit();

                assertEquals(1, executeUpdate(
                        writer, "update G2_C1_T set payload = 'rollback' where id = 1"));
                assertPayload(writer, "rollback");
                writer.rollback();
                assertPayload(writer, "v2");
                assertPhysicalShape(writer, 1, 1);
                writer.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_C1_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertPayload(reopened, "v2");
                assertPhysicalShape(reopened, 1, 1);
                reopened.commit();

                assertEquals(1, executeUpdate(
                        reopened, "update G2_C1_T set payload = 'v3' where id = 1"));
                reopened.commit();
                assertPayload(reopened, "v3");
                assertPhysicalShape(reopened, 1, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_C1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    public void testC1DeleteVisibilityRollbackAndMultipleUpdateGuard() throws Exception {
        String previous = System.getProperty(GEN2_C1_PROPERTY);
        String database = databaseName("mvcc-gen2-c1-delete-scope");
        try {
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table G2_C1_SCOPE (id int not null, payload varchar(64) not null) "
                                + "using delos_mvcc");
                executeUpdate(setup, "insert into G2_C1_SCOPE values (1, 'v1')");
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection observer = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertRows(historical, "select id, payload from G2_C1_SCOPE", "1|v1");
                assertEquals(1, executeUpdate(writer,
                        "delete from G2_C1_SCOPE where id = 1"));
                assertRows(writer, "select id, payload from G2_C1_SCOPE");
                assertRows(observer, "select id, payload from G2_C1_SCOPE", "1|v1");
                observer.commit();
                writer.rollback();
                assertRows(writer, "select id, payload from G2_C1_SCOPE", "1|v1");
                assertPhysicalShape(writer, "G2_C1_SCOPE", 1, 0);
                writer.commit();

                assertEquals(1, executeUpdate(writer,
                        "delete from G2_C1_SCOPE where id = 1"));
                writer.commit();
                assertRows(observer, "select id, payload from G2_C1_SCOPE");
                observer.commit();
                assertRows(historical, "select id, payload from G2_C1_SCOPE", "1|v1");
                historical.commit();
                assertRows(historical, "select id, payload from G2_C1_SCOPE");
                historical.commit();
                assertPhysicalShape(writer, "G2_C1_SCOPE", 1, 1);
                writer.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_C1_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertRows(reopened, "select id, payload from G2_C1_SCOPE");
                assertPhysicalShape(reopened, "G2_C1_SCOPE", 1, 1);
                reopened.commit();
            }

            String guardDatabase = databaseName("mvcc-gen2-c1-multiple-update-guard");
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection connection = openDatabase(guardDatabase, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_C1_GUARD (id int not null, payload varchar(64) not null) "
                                + "using delos_mvcc");
                executeUpdate(connection, "insert into G2_C1_GUARD values (1, 'v1')");
                connection.commit();
                assertEquals(1, executeUpdate(connection,
                        "update G2_C1_GUARD set payload = 'first' where id = 1"));
                assertUnsupported(connection,
                        "update G2_C1_GUARD set payload = 'second' where id = 1",
                        "Gen2-C1");
                connection.rollback();
                assertRows(connection, "select id, payload from G2_C1_GUARD", "1|v1");
                connection.commit();
            } finally {
                shutdownDatabase(guardDatabase);
            }
        } finally {
            restoreProperty(GEN2_C1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    public void testCurrentUpdateMaskPreservesColumnSelection() throws Exception {
        Class<?> tableClass = Class.forName(
                "org.apache.derby.impl.store.access.mvcc.MvccRawStoreTable");
        Method updateColumns = tableClass.getDeclaredMethod(
                "gen2C1UpdateColumns", int.class, FormatableBitSet.class);
        updateColumns.setAccessible(true);

        // Exercise every payload-column subset, including noncontiguous masks.
        for (int subset = 0; subset < 32; subset++) {
            FormatableBitSet selected = new FormatableBitSet(5);
            for (int column = 0; column < 5; column++) {
                if ((subset & (1 << column)) != 0) {
                    selected.set(column);
                }
            }
            assertUpdateMask(updateColumns, selected);
        }
        assertUpdateMask(updateColumns, null);
        assertUpdateMask(updateColumns, new FormatableBitSet(0));
        FormatableBitSet shortMask = new FormatableBitSet(1);
        shortMask.set(0);
        assertUpdateMask(updateColumns, shortMask);
    }

    public void testSparseColumnsPreserveSnapshotsAndReopen() throws Exception {
        String previous = System.getProperty(GEN2_C1_PROPERTY);
        String database = databaseName("mvcc-gen2-c1-sparse-columns");
        String preserved = "m".repeat(2048);
        try {
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                createColumnTable(setup, preserved);
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection observer = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                writer.setAutoCommit(false);
                long logicalRowId = MvccRawStoreMetadataInspection.directories(
                        writer, "G2_C1_COLUMNS").get(0).rowId();
                writer.commit();
                assertColumnRow(historical, 1, "left", preserved, "right");

                // The first and last SQL columns are noncontiguous. Changing
                // this unindexed SQL id must not change the logical row id.
                assertEquals(1, executeUpdate(writer,
                        "update G2_C1_COLUMNS set id = 2, tail = null where id = 1"));
                assertColumnRow(writer, 2, "left", preserved, null);
                assertColumnRow(observer, 1, "left", preserved, "right");
                observer.commit();
                assertColumnRow(historical, 1, "left", preserved, "right");
                writer.commit();
                assertPhysicalShape(writer, "G2_C1_COLUMNS", 1, 1);
                assertEquals(logicalRowId, MvccRawStoreMetadataInspection.directories(
                        writer, "G2_C1_COLUMNS").get(0).rowId());
                writer.commit();
                assertColumnRow(observer, 2, "left", preserved, null);
                observer.commit();

                // Replace every SQL field in a separate transaction. NULL is
                // a selected value, not an instruction to skip the column.
                assertEquals(1, executeUpdate(writer,
                        "update G2_C1_COLUMNS set id = 3, payload = null, "
                                + "preserved = 'changed', tail = 'last' where id = 2"));
                assertColumnRow(writer, 3, null, "changed", "last");
                assertColumnRow(observer, 2, "left", preserved, null);
                observer.commit();
                writer.commit();
                assertPhysicalShape(writer, "G2_C1_COLUMNS", 1, 2);
                assertEquals(logicalRowId, MvccRawStoreMetadataInspection.directories(
                        writer, "G2_C1_COLUMNS").get(0).rowId());
                writer.commit();

                assertColumnRow(historical, 1, "left", preserved, "right");
                historical.commit();
                assertColumnRow(historical, 3, null, "changed", "last");
                historical.commit();
                assertColumnRow(observer, 3, null, "changed", "last");
                observer.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_C1_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertColumnRow(reopened, 3, null, "changed", "last");
                assertPhysicalShape(reopened, "G2_C1_COLUMNS", 1, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_C1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    public void testSparseColumnGrowthAndRollback() throws Exception {
        String previous = System.getProperty(GEN2_C1_PROPERTY);
        String database = databaseName("mvcc-gen2-c1-sparse-rollback");
        String preserved = "p".repeat(2048);
        String grown = "g".repeat(8192);
        try {
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                createColumnTable(connection, preserved);
                connection.commit();

                Savepoint beforeUpdate = connection.setSavepoint("before_sparse_update");
                updatePayload(connection, grown);
                assertColumnRow(connection, 1, grown, preserved, "right");
                connection.rollback(beforeUpdate);
                connection.releaseSavepoint(beforeUpdate);
                assertColumnRow(connection, 1, "left", preserved, "right");
                assertPhysicalShape(connection, "G2_C1_COLUMNS", 1, 0);

                // Rollback must discard the pending version as well as undo
                // its RawStore fields, allowing another mutation in this tx.
                updatePayload(connection, null);
                assertColumnRow(connection, 1, null, preserved, "right");
                connection.commit();
                assertPhysicalShape(connection, "G2_C1_COLUMNS", 1, 1);
                connection.commit();

                updatePayload(connection, grown);
                assertColumnRow(connection, 1, grown, preserved, "right");
                connection.rollback();
                assertColumnRow(connection, 1, null, preserved, "right");
                assertPhysicalShape(connection, "G2_C1_COLUMNS", 1, 1);
                connection.commit();

                updatePayload(connection, "committed");
                connection.commit();
                assertColumnRow(connection, 1, "committed", preserved, "right");
                assertPhysicalShape(connection, "G2_C1_COLUMNS", 1, 2);
                connection.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_C1_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertColumnRow(reopened, 1, "committed", preserved, "right");
                assertPhysicalShape(reopened, "G2_C1_COLUMNS", 1, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_C1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    private static void assertUpdateMask(
            Method updateColumns, FormatableBitSet selected) throws Exception {
        FormatableBitSet before = selected == null ? null : new FormatableBitSet(selected);
        FormatableBitSet fields = (FormatableBitSet) updateColumns.invoke(null, 5, selected);
        // C1 has ten header fields; kind, format and logical row id are immutable.
        assertEquals(15, fields.getLength());
        for (int field = 0; field < 10; field++) {
            assertEquals("C1 header field " + field, field >= 3, fields.isSet(field));
        }
        for (int column = 0; column < 5; column++) {
            boolean expected = selected == null
                    || (column < selected.getLength() && selected.isSet(column));
            assertEquals("C1 payload field " + column,
                    expected, fields.isSet(10 + column));
        }
        assertEquals("Must not mutate the caller's SQL column mask", before, selected);
    }

    private static void createColumnTable(
            Connection connection, String preserved) throws Exception {
        executeUpdate(connection,
                "create table G2_C1_COLUMNS (id int not null, payload varchar(16384), "
                        + "preserved varchar(4096), tail varchar(128)) using delos_mvcc");
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into G2_C1_COLUMNS values (1, 'left', ?, 'right')")) {
            insert.setString(1, preserved);
            assertEquals(1, insert.executeUpdate());
        }
    }

    private static void updatePayload(Connection connection, String value) throws Exception {
        try (PreparedStatement update = connection.prepareStatement(
                "update G2_C1_COLUMNS set payload = ? where id = 1")) {
            update.setString(1, value);
            assertEquals(1, update.executeUpdate());
        }
    }

    private static void assertColumnRow(
            Connection connection, int id, String payload, String preserved, String tail)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select id, payload, preserved, tail from G2_C1_COLUMNS")) {
            assertTrue("Expected one current logical row", result.next());
            assertEquals(id, result.getInt(1));
            assertEquals(payload, result.getString(2));
            assertEquals(preserved, result.getString(3));
            assertEquals(tail, result.getString(4));
            assertFalse("Must not duplicate the logical row", result.next());
        }
    }

    private static void assertPayload(Connection connection, String expected) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select payload from G2_C1_T where id = 1")) {
            assertTrue(result.next());
            assertEquals(expected, result.getString(1));
            assertFalse(result.next());
        }
    }

    private static void assertPhysicalShape(
            Connection connection, int currentRows, int historyRows) throws Exception {
        assertPhysicalShape(connection, "G2_C1_T", currentRows, historyRows);
    }

    private static void assertPhysicalShape(
            Connection connection, String table, int currentRows, int historyRows) throws Exception {
        assertEquals("Gen2-C1 must retain exactly one authoritative current record per row",
                currentRows,
                MvccRawStoreMetadataInspection.directories(connection, table).size());
        assertEquals("Gen2-C1 must create history only for replaced committed images",
                historyRows,
                MvccRawStoreMetadataInspection.versions(connection, table).size());
        assertEquals("Gen2-C1 bare tables must not create a native ordered index",
                0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, table).size());
    }

    private static void assertUnsupported(
            Connection connection, String sql, String expectedMarker) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            fail("Expected Gen2-C1 scope rejection for: " + sql);
        } catch (java.sql.SQLException expected) {
            assertEquals("0A000", expected.getSQLState());
            assertTrue("Expected scope marker in: " + expected,
                    expected.getMessage() != null
                            && expected.getMessage().contains(expectedMarker));
        }
    }

    private static void restoreProperty(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }
}
