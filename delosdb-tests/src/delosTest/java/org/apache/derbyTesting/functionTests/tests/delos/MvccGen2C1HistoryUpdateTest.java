/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2C1HistoryUpdateTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

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

    public void testC1KeepsDeleteAndMultipleSameRowMutationDeferred() throws Exception {
        String previous = System.getProperty(GEN2_C1_PROPERTY);
        String database = databaseName("mvcc-gen2-c1-scope");
        try {
            System.setProperty(GEN2_C1_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_C1_SCOPE (id int not null, payload varchar(64) not null) "
                                + "using delos_mvcc");
                executeUpdate(connection, "insert into G2_C1_SCOPE values (1, 'v1')");
                connection.commit();

                assertUnsupported(connection,
                        "delete from G2_C1_SCOPE where id = 1",
                        "Gen2-C1");
                connection.rollback();

                assertEquals(1, executeUpdate(
                        connection,
                        "update G2_C1_SCOPE set payload = 'first' where id = 1"));
                assertUnsupported(connection,
                        "update G2_C1_SCOPE set payload = 'second' where id = 1",
                        "Gen2-C1");
                connection.rollback();
                assertRows(connection,
                        "select id, payload from G2_C1_SCOPE",
                        "1|v1");
                assertPhysicalShape(connection, "G2_C1_SCOPE", 1, 0);
                connection.commit();
            }
        } finally {
            restoreProperty(GEN2_C1_PROPERTY, previous);
            shutdownDatabase(database);
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
