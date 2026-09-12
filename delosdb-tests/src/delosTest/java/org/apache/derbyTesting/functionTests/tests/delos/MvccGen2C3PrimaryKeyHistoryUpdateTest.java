/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2C3PrimaryKeyHistoryUpdateTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Combines the Gen2 single-authority PK slice with current/history UPDATEs. */
public final class MvccGen2C3PrimaryKeyHistoryUpdateTest extends MvccSqlTestSupport {
    private static final String GEN2_B_PK_PROPERTY =
            "delosdb.experimental.mvccGen2B.pk.enabled";
    private static final String GEN2_C1_HISTORY_PROPERTY =
            "delosdb.experimental.mvccGen2C1.history.enabled";
    private static final String GEN2_PROJECTED_CURRENT_READ_PROPERTY =
            "delosdb.experimental.mvccGen2ProjectedCurrentRead.enabled";

    public void testUnchangedPrimaryKeyUpdateHistoryRollbackAndReopen() throws Exception {
        String previousPk = System.getProperty(GEN2_B_PK_PROPERTY);
        String previousHistory = System.getProperty(GEN2_C1_HISTORY_PROPERTY);
        String database = databaseName("mvcc-gen2-c3-pk-history-update");
        try {
            System.setProperty(GEN2_B_PK_PROPERTY, "true");
            System.setProperty(GEN2_C1_HISTORY_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table G2_C3_PK (id int not null primary key, "
                                + "payload varchar(256) not null) using delos_mvcc");
                executeUpdate(setup, "insert into G2_C3_PK values (1, 'v1')");
                executeUpdate(setup, "insert into G2_C3_PK values (2, 'two')");
                setup.commit();
                assertPhysicalShape(setup, 2, 0);
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection observer = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertPayload(historical, 1, "v1");
                assertEquals(1, updatePayload(writer, 1, "v2"));
                assertPayload(writer, 1, "v2");
                assertPayload(observer, 1, "v1");
                observer.commit();
                writer.commit();
                assertPayload(historical, 1, "v1");
                assertPayload(observer, 1, "v2");
                observer.commit();
                assertPhysicalShape(observer, 2, 1);
                observer.commit();

                assertEquals(1, updatePayload(writer, 1, "rolled-back"));
                writer.rollback();
                assertPayload(writer, 1, "v2");
                assertPhysicalShape(writer, 2, 1);
                writer.commit();

                assertKeyChangeRejected(writer);
                writer.rollback();
                assertPayload(writer, 1, "v2");
                assertMissing(writer, 3);
                assertPhysicalShape(writer, 2, 1);
                writer.commit();

                try {
                    executeUpdate(writer, "insert into G2_C3_PK values (1, 'duplicate')");
                    fail("Expected duplicate primary-key rejection");
                } catch (SQLException expected) {
                    assertEquals("23505", expected.getSQLState());
                }
                writer.rollback();
                assertPayload(writer, 1, "v2");
                writer.commit();

                historical.commit();
                assertPayload(historical, 1, "v2");
                historical.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_B_PK_PROPERTY);
            System.clearProperty(GEN2_C1_HISTORY_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertPayload(reopened, 1, "v2");
                assertEquals(1, updatePayload(reopened, 1, "v3"));
                reopened.commit();
                assertPayload(reopened, 1, "v3");
                assertPhysicalShape(reopened, 2, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_B_PK_PROPERTY, previousPk);
            restoreProperty(GEN2_C1_HISTORY_PROPERTY, previousHistory);
            shutdownDatabase(database);
        }
    }

    public void testPrimaryKeyDeleteRemainsDeferredUntilHistoricalIndexReachability()
            throws Exception {
        String previousPk = System.getProperty(GEN2_B_PK_PROPERTY);
        String previousHistory = System.getProperty(GEN2_C1_HISTORY_PROPERTY);
        String database = databaseName("mvcc-gen2-c3-pk-delete-deferred");
        try {
            System.setProperty(GEN2_B_PK_PROPERTY, "true");
            System.setProperty(GEN2_C1_HISTORY_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_C3_DELETE_GUARD "
                                + "(id int not null primary key, payload varchar(128) not null) "
                                + "using delos_mvcc");
                executeUpdate(connection,
                        "insert into G2_C3_DELETE_GUARD values (1, 'original')");
                connection.commit();

                try {
                    executeUpdate(connection,
                            "delete from G2_C3_DELETE_GUARD where id = 1");
                    fail("Expected indexed Gen2 DELETE to remain deferred");
                } catch (SQLException expected) {
                    assertEquals("0A000", expected.getSQLState());
                    assertTrue(expected.getMessage() != null
                            && expected.getMessage().contains("historical SQL-index reachability"));
                }
                connection.rollback();
                try (PreparedStatement statement = connection.prepareStatement(
                        "select payload from G2_C3_DELETE_GUARD where id = ?")) {
                    statement.setInt(1, 1);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("original", result.getString(1));
                        assertFalse(result.next());
                    }
                }
                connection.commit();
            }
        } finally {
            restoreProperty(GEN2_B_PK_PROPERTY, previousPk);
            restoreProperty(GEN2_C1_HISTORY_PROPERTY, previousHistory);
            shutdownDatabase(database);
        }
    }

    public void testProjectedCurrentReadPreservesCurrentAndHistoricalValues() throws Exception {
        String previousPk = System.getProperty(GEN2_B_PK_PROPERTY);
        String previousHistory = System.getProperty(GEN2_C1_HISTORY_PROPERTY);
        String previousProjectedRead = System.getProperty(GEN2_PROJECTED_CURRENT_READ_PROPERTY);
        String database = databaseName("mvcc-gen2-c3-projected-current-read");
        String oldPayload = "x".repeat(2048);
        String newPayload = "y".repeat(2048);
        try {
            System.setProperty(GEN2_B_PK_PROPERTY, "true");
            System.setProperty(GEN2_C1_HISTORY_PROPERTY, "true");
            System.setProperty(GEN2_PROJECTED_CURRENT_READ_PROPERTY, "true");
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup,
                        "create table G2_C3_PROJ (id int not null primary key, "
                                + "quantity int not null, payload varchar(4096) not null) "
                                + "using delos_mvcc");
                try (PreparedStatement insert = setup.prepareStatement(
                        "insert into G2_C3_PROJ values (?, ?, ?)")) {
                    insert.setInt(1, 1);
                    insert.setInt(2, 7);
                    insert.setString(3, oldPayload);
                    assertEquals(1, insert.executeUpdate());
                }
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection observer = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                observer.setAutoCommit(false);
                writer.setAutoCommit(false);

                assertQuantity(historical, "G2_C3_PROJ", 1, 7);
                try (PreparedStatement update = writer.prepareStatement(
                        "update G2_C3_PROJ set quantity = ?, payload = ? where id = ?")) {
                    update.setInt(1, 8);
                    update.setString(2, newPayload);
                    update.setInt(3, 1);
                    assertEquals(1, update.executeUpdate());
                }
                writer.commit();

                assertQuantity(observer, "G2_C3_PROJ", 1, 8);
                assertPayloadValue(observer, "G2_C3_PROJ", 1, newPayload);
                observer.commit();

                assertQuantity(historical, "G2_C3_PROJ", 1, 7);
                assertPayloadValue(historical, "G2_C3_PROJ", 1, oldPayload);
                historical.commit();
                assertQuantity(historical, "G2_C3_PROJ", 1, 8);
                historical.commit();
            }
        } finally {
            restoreProperty(GEN2_PROJECTED_CURRENT_READ_PROPERTY, previousProjectedRead);
            restoreProperty(GEN2_B_PK_PROPERTY, previousPk);
            restoreProperty(GEN2_C1_HISTORY_PROPERTY, previousHistory);
            shutdownDatabase(database);
        }
    }

    private static void assertQuantity(
            Connection connection, String table, int id, int expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(expected, result.getInt(1));
                assertFalse(result.next());
            }
        }
    }

    private static void assertPayloadValue(
            Connection connection, String table, int id, String expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from " + table + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(expected, result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    private static int updatePayload(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "update G2_C3_PK set payload = ? where id = ?")) {
            statement.setString(1, payload);
            statement.setInt(2, id);
            return statement.executeUpdate();
        }
    }

    private static void assertKeyChangeRejected(Connection connection) throws Exception {
        try {
            executeUpdate(connection, "update G2_C3_PK set id = 3 where id = 1");
            fail("Expected Gen2 PK/history key-changing UPDATE rejection");
        } catch (SQLException expected) {
            assertEquals("0A000", expected.getSQLState());
            assertTrue(expected.getMessage() != null
                    && expected.getMessage().contains("key-changing UPDATE"));
        }
    }

    private static void assertPayload(Connection connection, int id, String expected) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from G2_C3_PK where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(expected, result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    private static void assertMissing(Connection connection, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from G2_C3_PK where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertFalse(result.next());
            }
        }
    }

    private static void assertPhysicalShape(
            Connection connection, int currentRows, int historyRows) throws Exception {
        assertEquals(currentRows,
                MvccRawStoreMetadataInspection.directories(connection, "G2_C3_PK").size());
        assertEquals(historyRows,
                MvccRawStoreMetadataInspection.versions(connection, "G2_C3_PK").size());
        assertEquals("Gen2 PK/history must not recreate the native candidate index",
                0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "G2_C3_PK").size());
        assertEquals("Gen2 PK/history must retain exactly one SQL backing index",
                1,
                sqlIndexCount(connection));
    }

    private static int sqlIndexCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*) from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                             + "where c.tableid = t.tableid and t.schemaid = s.schemaid "
                             + "and s.schemaname = 'APP' and t.tablename = 'G2_C3_PK' "
                             + "and c.isindex = true")) {
            assertTrue(result.next());
            int count = result.getInt(1);
            assertFalse(result.next());
            return count;
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
