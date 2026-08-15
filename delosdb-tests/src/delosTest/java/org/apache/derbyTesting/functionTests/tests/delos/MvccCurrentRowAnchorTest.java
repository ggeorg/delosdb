/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCurrentRowAnchorTest

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focused correctness and physical-shape proof for the permanent MVCC current-row anchor. */
public final class MvccCurrentRowAnchorTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-current-row-anchor-db";

    public void testCommittedCurrentReadUsesOneSlotAnchorPath() throws Exception {
        String database = databaseName(DATABASE + "-current");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
            String statistics = measuredRead(connection, 1);
            assertEquals(10, quantity(connection, 1));
            assertEquals(1L, metric(statistics, "mvccCurrentRowAnchorChecks"));
            assertEquals(1L, metric(statistics, "mvccCurrentRowAnchorHits"));
            assertEquals(0L, metric(statistics, "mvccCurrentRowAnchorFallbacks"));
            assertEquals(0L, metric(statistics, "mvccDirectoryPageAcquisitions"));
            assertEquals(1L, metric(statistics, "mvccVersionPageAcquisitions"));
            assertEquals(1L, metric(statistics, "mvccVersionSlotFetches"));
            assertEquals(1L, metric(statistics, "mvccVisibilityChecks"));
            assertEquals(0L, metric(statistics, "mvccVersionChainSteps"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testOwnUncommittedUpdateBypassesAnchorAndRollbackRestoresIt() throws Exception {
        String database = databaseName(DATABASE + "-own-write");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
            assertEquals(1, executeUpdate(connection,
                    "update anchor_t set quantity = 20 where id = 1"));
            String ownWriteStatistics = measuredRead(connection, 1);
            assertEquals(20, quantity(connection, 1));
            assertEquals(0L, metric(ownWriteStatistics, "mvccCurrentRowAnchorChecks"));
            assertEquals(1L, metric(ownWriteStatistics, "mvccDirectoryPageAcquisitions"));
            connection.rollback();

            String restoredStatistics = measuredRead(connection, 1);
            assertEquals(10, quantity(connection, 1));
            assertEquals(1L, metric(restoredStatistics, "mvccCurrentRowAnchorHits"));
            assertEquals(0L, metric(restoredStatistics, "mvccDirectoryPageAcquisitions"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testOldSnapshotFallsBackToAuthoritativeHistory() throws Exception {
        String database = databaseName(DATABASE + "-snapshot");
        try (Connection setup = openDatabase(database, true)) {
            createFixture(setup);
        }
        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertEquals(10, quantity(reader, 1));
            assertEquals(1, executeUpdate(writer,
                    "update anchor_t set quantity = 30 where id = 1"));
            writer.commit();

            String statistics = measuredRead(reader, 1);
            assertEquals(10, quantity(reader, 1));
            assertEquals(1L, metric(statistics, "mvccCurrentRowAnchorChecks"));
            assertEquals(0L, metric(statistics, "mvccCurrentRowAnchorHits"));
            assertEquals(1L, metric(statistics, "mvccCurrentRowAnchorFallbacks"));
            assertEquals(1L, metric(statistics, "mvccDirectoryPageAcquisitions"));
            assertTrue("old snapshot must traverse history; statistics=" + statistics,
                    metric(statistics, "mvccVersionChainSteps") >= 2L);
            reader.rollback();
        }
        shutdownDatabase(database);
    }

    public void testReopenFallsBackOnceThenWarmsTransientAnchor() throws Exception {
        String database = databaseName(DATABASE + "-reopen");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
        }
        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            String first = measuredRead(reopened, 1);
            assertEquals(10, quantity(reopened, 1));
            assertEquals(0L, metric(first, "mvccCurrentRowAnchorHits"));
            assertEquals(1L, metric(first, "mvccDirectoryPageAcquisitions"));

            String second = measuredRead(reopened, 1);
            assertEquals(10, quantity(reopened, 1));
            assertEquals(1L, metric(second, "mvccCurrentRowAnchorHits"));
            assertEquals(1L, metric(second, "mvccCurrentVersionReadImageHits"));
            assertEquals(0L, metric(second, "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(0L, metric(second, "mvccDirectoryPageAcquisitions"));
            assertEquals(0L, metric(second, "mvccVersionSlotFetches"));
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    private static void createFixture(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table anchor_t (id int not null primary key, quantity int not null) using delos_mvcc");
        executeUpdate(connection, "insert into anchor_t values (1, 10)");
        connection.commit();
    }

    private static int quantity(Connection connection, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, quantity from anchor_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                assertEquals(id, resultSet.getInt(1));
                int quantity = resultSet.getInt(2);
                assertFalse(resultSet.next());
                return quantity;
            }
        }
    }

    private static String measuredRead(Connection connection, int id) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        quantity(connection, id);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static long metric(String statistics, String name) {
        Pattern pattern = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*(\\d+)\\s*$");
        Matcher matcher = pattern.matcher(statistics);
        assertTrue("missing MVCC scan metric " + name + "; statistics=" + statistics,
                matcher.find());
        return Long.parseLong(matcher.group(1));
    }
}
