/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2A1BareTableTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/** First executable MVCC Gen2 physical slice: one current-row record, no history. */
public final class MvccGen2A1BareTableTest extends MvccSqlTestSupport {
    private static final String GEN2_A1_PROPERTY =
            "delosdb.experimental.mvccGen2A1.enabled";

    public void testBareInsertCommitRollbackAndReopen() throws Exception {
        String previous = System.getProperty(GEN2_A1_PROPERTY);
        String database = databaseName("mvcc-gen2-a1-bare");
        try {
            System.setProperty(GEN2_A1_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_A1_T (id int not null, payload varchar(128) not null) "
                                + "using delos_mvcc");
                connection.commit();

                MvccRawStoreMetadataInspection.Counters beforeWriter =
                        MvccRawStoreMetadataInspection.counters(connection);
                connection.commit();

                insert(connection, 1, "committed");
                connection.commit();
                MvccRawStoreMetadataInspection.Counters afterFirstWriter =
                        MvccRawStoreMetadataInspection.counters(connection);
                assertEquals("Gen2-A1 must reserve transaction identities in one 64-id block",
                        beforeWriter.nextTransactionId() + 64L,
                        afterFirstWriter.nextTransactionId());
                connection.commit();
                assertRows(connection, 1, "committed");
                assertPhysicalShape(connection, 1);
                connection.commit();

                insert(connection, 2, "rolled-back");
                assertEquals(2L, countRows(connection));
                connection.rollback();
                MvccRawStoreMetadataInspection.Counters afterSecondWriter =
                        MvccRawStoreMetadataInspection.counters(connection);
                assertEquals("Second Gen2-A1 writer must consume the in-memory transaction-id block",
                        afterFirstWriter.nextTransactionId(),
                        afterSecondWriter.nextTransactionId());
                connection.commit();
                assertRows(connection, 1, "committed");
                assertPhysicalShape(connection, 1);
                connection.commit();
            }

            shutdownDatabase(database);
            // The layout marker is persistent. Reopen must not depend on the
            // creation-time experiment flag remaining set.
            System.clearProperty(GEN2_A1_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertRows(reopened, 1, "committed");
                assertPhysicalShape(reopened, 1);
                reopened.commit();

                insert(reopened, 2, "after-reopen");
                reopened.commit();
                assertEquals(
                        "Gen2-A1 allocator reconstruction must not reuse a persisted row identity",
                        2L,
                        countRows(reopened));
                assertPhysicalShape(reopened, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_A1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    public void testA1RejectsHistoryAndUniqueExpansion() throws Exception {
        String previous = System.getProperty(GEN2_A1_PROPERTY);
        String database = databaseName("mvcc-gen2-a1-scope");
        try {
            System.setProperty(GEN2_A1_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                assertUnsupported(connection,
                        "create table G2_SCOPE_PK (id int primary key) using delos_mvcc");
                connection.rollback();
                executeUpdate(connection,
                        "create table G2_SCOPE_T (id int not null, payload varchar(64) not null) "
                                + "using delos_mvcc");
                connection.commit();
                insertScope(connection, 1, "a");
                connection.commit();

                assertUnsupported(connection,
                        "update G2_SCOPE_T set payload = 'b' where id = 1");
                connection.rollback();
                assertUnsupported(connection,
                        "delete from G2_SCOPE_T where id = 1");
                connection.rollback();
                assertUnsupported(connection,
                        "alter table G2_SCOPE_T add constraint G2_SCOPE_U unique (id)");
                connection.rollback();
            }
        } finally {
            restoreProperty(GEN2_A1_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    private static void insert(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into G2_A1_T (id, payload) values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void insertScope(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into G2_SCOPE_T (id, payload) values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertRows(Connection connection, int expectedId, String expectedPayload)
            throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select id, payload from G2_A1_T order by id")) {
            assertTrue(result.next());
            assertEquals(expectedId, result.getInt(1));
            assertEquals(expectedPayload, result.getString(2));
            assertFalse(result.next());
        }
    }

    private static long countRows(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select count(*) from G2_A1_T")) {
            assertTrue(result.next());
            long count = result.getLong(1);
            assertFalse(result.next());
            return count;
        }
    }

    private static void assertPhysicalShape(Connection connection, int expectedRows) throws Exception {
        assertEquals("Gen2-A1 current rows are stable-row records",
                expectedRows,
                MvccRawStoreMetadataInspection.directories(connection, "G2_A1_T").size());
        assertEquals("Gen2-A1 first INSERT must not create history/version records",
                0,
                MvccRawStoreMetadataInspection.versions(connection, "G2_A1_T").size());
        assertEquals("Gen2-A1 bare table must not maintain a native ordered index",
                0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "G2_A1_T").size());
    }

    private static void assertUnsupported(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
            fail("Expected MVCC Gen2-A1 scope guard for: " + sql);
        } catch (java.sql.SQLException expected) {
            assertEquals("0A000", expected.getSQLState());
            assertTrue("Expected Gen2-A1 scope rejection: " + expected,
                    expected.getMessage() != null
                            && expected.getMessage().contains("Gen2-A1"));
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
