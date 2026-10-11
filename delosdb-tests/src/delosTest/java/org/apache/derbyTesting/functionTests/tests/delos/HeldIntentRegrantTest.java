/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.impl.services.locks.HeldIntentRegrantTestSupport;

/**
 * Held CIX regrant: a transaction's repeated intent-exclusive container lock
 * requests are granted from its own lock space instead of the lock-table
 * entry shared by every writer. Lock counts, group release at commit and
 * rollback, waiters and lock escalation must behave as through the lock table.
 */
public final class HeldIntentRegrantTest extends MvccSqlTestSupport {

    public void testRegrantKeepsReferenceCounts() throws Exception {
        HeldIntentRegrantTestSupport.verifyReferenceCounting();
    }

    public void testRegrantAcrossGroupsAndTransfer() throws Exception {
        HeldIntentRegrantTestSupport.verifyGroupUnlockAndTransfer();
    }

    public void testRegrantWithQueuedIncompatibleWaiter() throws Exception {
        HeldIntentRegrantTestSupport.verifyRegrantWithQueuedWaiter();
    }

    public void testIndexedInsertsHoldAndReleaseTableIntentLock() throws Exception {
        String databaseName = databaseName("held-intent-regrant-db");
        try (Connection writer = openDatabase(databaseName, true);
             Connection other = openDatabase(databaseName, false)) {
            executeStatement(writer, "call syscs_util.syscs_set_database_property("
                    + "'derby.locks.waitTimeout', '1')");
            executeUpdate(writer, "create table hir_t (id int not null primary key,"
                    + " category int not null, payload varchar(100) not null)");
            executeUpdate(writer, "create index hir_category on hir_t(category)");
            writer.setAutoCommit(false);
            other.setAutoCommit(false);

            for (boolean commit : new boolean[] {true, false}) {
                int first = commit ? 1 : 1001;
                long before = HeldIntentRegrantTestSupport.regrants();
                insertRows(writer, first, 200);
                assertTrue("repeated row inserts must regrant the held table CIX",
                        HeldIntentRegrantTestSupport.regrants() > before);
                assertEquals("IX", tableLockMode(writer, "HIR_T"));
                assertExclusiveLockTimesOut(other, "hir_t");

                if (commit) {
                    writer.commit();
                } else {
                    writer.rollback();
                }
                assertNull("table lock must be released at transaction end",
                        tableLockMode(writer, "HIR_T"));
                executeStatement(other, "lock table hir_t in exclusive mode");
                other.commit();
            }
            assertEquals(200, count(writer, "hir_t"));
            writer.commit();

            // Enough row locks to escalate to a table X lock in mid-transaction.
            insertRows(writer, 10_001, 6000);
            assertEquals("X", tableLockMode(writer, "HIR_T"));
            insertRows(writer, 20_001, 100);
            writer.commit();
            assertNull(tableLockMode(writer, "HIR_T"));
            executeStatement(other, "lock table hir_t in exclusive mode");
            other.commit();
            assertEquals(6300, count(other, "hir_t"));
            try (Statement statement = other.createStatement();
                 ResultSet check = statement.executeQuery(
                         "values syscs_util.syscs_check_table('APP', 'HIR_T')")) {
                assertTrue(check.next());
                assertEquals(1, check.getInt(1));
            }
            other.commit();
            writer.commit();
        } finally {
            shutdownDatabase(databaseName);
        }
    }

    private static void insertRows(Connection connection, int first, int rows) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into hir_t values (?, ?, ?)")) {
            for (int id = first; id < first + rows; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 17);
                insert.setString(3, "row-" + id);
                insert.executeUpdate();
            }
        }
    }

    /**
     * Strongest granted table lock mode on the table, or null. Only the
     * writer holds locks on the table when this is called.
     */
    private static String tableLockMode(Connection connection, String table) throws SQLException {
        String mode = null;
        try (PreparedStatement query = connection.prepareStatement(
                "select mode from syscs_diag.lock_table"
                        + " where type = 'TABLE' and tablename = ? and state = 'GRANT'")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String granted = rows.getString(1).trim();
                    if (mode == null || "X".equals(granted)) {
                        mode = granted;
                    }
                }
            }
        }
        return mode;
    }

    private static void assertExclusiveLockTimesOut(Connection other, String table) throws SQLException {
        try {
            executeStatement(other, "lock table " + table + " in exclusive mode");
            fail("exclusive table lock granted while a writer holds the table IX lock");
        } catch (SQLException expected) {
            // LOCK TABLE reports the lock timeout (40XL1) as X0X02 with the timeout chained.
            assertEquals("X0X02", expected.getSQLState());
            assertNotNull(expected.getNextException());
            assertEquals("40XL1", expected.getNextException().getSQLState());
        }
        other.rollback();
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
