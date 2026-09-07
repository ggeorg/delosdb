/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2BPrimaryKeyTableTest

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

/** First Gen2-B slice: Gen2 current rows plus one authoritative SQL PK B-tree. */
public final class MvccGen2BPrimaryKeyTableTest extends MvccSqlTestSupport {
    private static final String GEN2_B_PK_PROPERTY =
            "delosdb.experimental.mvccGen2B.pk.enabled";

    public void testHeapDuplicateRollbackControl() throws Exception {
        String database = databaseName("mvcc-gen2-b-pk-heap-control");
        try {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_B_HEAP_PK (id int not null primary key, "
                                + "payload varchar(128) not null)");
                connection.commit();

                executeUpdate(connection, "insert into G2_B_HEAP_PK values (1, 'one')");
                connection.commit();
                assertDuplicateKey(() -> executeUpdate(
                        connection, "insert into G2_B_HEAP_PK values (1, 'duplicate')"));
                connection.rollback();
                assertRows(connection,
                        "select id, payload from G2_B_HEAP_PK order by id",
                        "1|one");
                connection.commit();
            }
        } finally {
            shutdownDatabase(database);
        }
    }

    public void testPrimaryKeyInsertDuplicateRollbackPointReadAndReopen() throws Exception {
        String previous = System.getProperty(GEN2_B_PK_PROPERTY);
        String database = databaseName("mvcc-gen2-b-pk");
        try {
            System.setProperty(GEN2_B_PK_PROPERTY, "true");
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table G2_B_PK (id int not null primary key, "
                                + "payload varchar(128) not null) using delos_mvcc");
                connection.commit();

                insert(connection, 1, "one");
                connection.commit();
                assertPointRead(connection, 1, "one");
                assertPhysicalShape(connection, 1);
                connection.commit();

                assertDuplicate(connection, 1, "duplicate");
                connection.rollback();
                assertPointRead(connection, 1, "one");
                assertPhysicalShape(connection, 1);
                connection.commit();

                insert(connection, 2, "rolled-back");
                connection.rollback();
                assertMissing(connection, 2);
                assertPhysicalShape(connection, 1);
                connection.commit();
            }

            shutdownDatabase(database);
            System.clearProperty(GEN2_B_PK_PROPERTY);
            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                assertPointRead(reopened, 1, "one");
                insert(reopened, 2, "two");
                reopened.commit();
                assertPointRead(reopened, 2, "two");
                assertPhysicalShape(reopened, 2);
                reopened.commit();
            }
        } finally {
            restoreProperty(GEN2_B_PK_PROPERTY, previous);
            shutdownDatabase(database);
        }
    }

    private static void insert(Connection connection, int id, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into G2_B_PK (id, payload) values (?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void assertDuplicate(Connection connection, int id, String payload)
            throws Exception {
        try {
            insert(connection, id, payload);
            fail("Expected duplicate primary-key rejection");
        } catch (SQLException expected) {
            assertEquals("23505", expected.getSQLState());
        }
    }

    private static void assertPointRead(Connection connection, int id, String payload)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from G2_B_PK where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(payload, result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    private static void assertMissing(Connection connection, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select payload from G2_B_PK where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertFalse(result.next());
            }
        }
    }

    private static void assertPhysicalShape(Connection connection, int expectedRows) throws Exception {
        assertEquals("Gen2-B current rows are authoritative base records",
                expectedRows,
                MvccRawStoreMetadataInspection.directories(connection, "G2_B_PK").size());
        assertEquals("Gen2-B fresh INSERT must not create history/version records",
                0,
                MvccRawStoreMetadataInspection.versions(connection, "G2_B_PK").size());
        assertEquals("Gen2-B must not recreate the Gen1 native candidate index",
                0,
                MvccRawStoreMetadataInspection.orderedIndexEntries(connection, "G2_B_PK").size());
        assertEquals("Gen2-B PK slice must expose exactly one SQL backing index",
                1,
                sqlIndexCount(connection));
    }

    private static int sqlIndexCount(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "select count(*) from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                             + "where c.tableid = t.tableid and t.schemaid = s.schemaid "
                             + "and s.schemaname = 'APP' and t.tablename = 'G2_B_PK' and c.isindex = true")) {
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
