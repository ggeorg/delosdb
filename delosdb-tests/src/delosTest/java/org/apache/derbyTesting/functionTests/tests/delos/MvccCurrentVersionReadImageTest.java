/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCurrentVersionReadImageTest

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

/** Focused correctness and removability proof for the transient MVCC current-version image. */
public final class MvccCurrentVersionReadImageTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-current-version-read-image-db";

    public void testCurrentVersionImageWarmsThenSkipsVersionPage() throws Exception {
        String database = databaseName(DATABASE + "-current");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);

            Measurement first = measuredRead(connection, 1);
            assertEquals(10, first.quantity());
            assertEquals(1L, metric(first.statistics(), "mvccCurrentVersionReadImageChecks"));
            assertEquals(0L, metric(first.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(1L, metric(first.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(1L, metric(first.statistics(), "mvccVersionPageAcquisitions"));
            assertEquals(1L, metric(first.statistics(), "mvccVersionSlotFetches"));

            Measurement second = measuredRead(connection, 1);
            assertEquals(10, second.quantity());
            assertEquals(1L, metric(second.statistics(), "mvccCurrentVersionReadImageChecks"));
            assertEquals(1L, metric(second.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(0L, metric(second.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(0L, metric(second.statistics(), "mvccVersionPageAcquisitions"));
            assertEquals(0L, metric(second.statistics(), "mvccVersionSlotFetches"));
            assertEquals(1L, metric(second.statistics(), "mvccCurrentRowAnchorHits"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testCommittedUpdateCannotServeStaleVersionImage() throws Exception {
        String database = databaseName(DATABASE + "-update");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
            measuredRead(connection, 1);
            Measurement warm = measuredRead(connection, 1);
            assertEquals(1L, metric(warm.statistics(), "mvccCurrentVersionReadImageHits"));

            assertEquals(1, executeUpdate(connection,
                    "update image_t set quantity = 20 where id = 1"));
            connection.commit();

            Measurement firstNew = measuredRead(connection, 1);
            assertEquals(20, firstNew.quantity());
            assertEquals(0L, metric(firstNew.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(1L, metric(firstNew.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(1L, metric(firstNew.statistics(), "mvccVersionPageAcquisitions"));

            Measurement secondNew = measuredRead(connection, 1);
            assertEquals(20, secondNew.quantity());
            assertEquals(1L, metric(secondNew.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(0L, metric(secondNew.statistics(), "mvccVersionPageAcquisitions"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    public void testOldSnapshotRejectsNewAnchorBeforeVersionImageLookup() throws Exception {
        String database = databaseName(DATABASE + "-snapshot");
        try (Connection setup = openDatabase(database, true)) {
            createFixture(setup);
            measuredRead(setup, 1);
            measuredRead(setup, 1);
            setup.commit();
        }
        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertEquals(10, quantity(reader, 1));
            assertEquals(1, executeUpdate(writer,
                    "update image_t set quantity = 30 where id = 1"));
            writer.commit();

            Measurement measured = measuredRead(reader, 1);
            assertEquals(10, measured.quantity());
            assertEquals(1L, metric(measured.statistics(), "mvccCurrentRowAnchorChecks"));
            assertEquals(1L, metric(measured.statistics(), "mvccCurrentRowAnchorFallbacks"));
            assertEquals(0L, metric(measured.statistics(), "mvccCurrentVersionReadImageChecks"));
            assertTrue("old snapshot must traverse history; statistics=" + measured.statistics(),
                    metric(measured.statistics(), "mvccVersionChainSteps") >= 2L);
            reader.rollback();
        }
        shutdownDatabase(database);
    }

    public void testReopenStartsWithoutTransientVersionImage() throws Exception {
        String database = databaseName(DATABASE + "-reopen");
        try (Connection connection = openDatabase(database, true)) {
            createFixture(connection);
            measuredRead(connection, 1);
            measuredRead(connection, 1);
            connection.commit();
        }
        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            Measurement first = measuredRead(reopened, 1);
            assertEquals(10, first.quantity());
            assertEquals(0L, metric(first.statistics(), "mvccCurrentVersionReadImageChecks"));
            assertEquals(1L, metric(first.statistics(), "mvccDirectoryPageAcquisitions"));

            Measurement second = measuredRead(reopened, 1);
            assertEquals(10, second.quantity());
            assertEquals(1L, metric(second.statistics(), "mvccCurrentVersionReadImageChecks"));
            assertEquals(1L, metric(second.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(1L, metric(second.statistics(), "mvccVersionPageAcquisitions"));

            Measurement third = measuredRead(reopened, 1);
            assertEquals(10, third.quantity());
            assertEquals(1L, metric(third.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(0L, metric(third.statistics(), "mvccVersionPageAcquisitions"));
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    private static void createFixture(Connection connection) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table image_t (id int not null primary key, quantity int not null) using delos_mvcc");
        executeUpdate(connection, "insert into image_t values (1, 10)");
        connection.commit();
    }

    private static int quantity(Connection connection, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id, quantity from image_t where id = ?")) {
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

    private static Measurement measuredRead(Connection connection, int id) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        int quantity = quantity(connection, id);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return new Measurement(quantity, resultSet.getString(1));
        }
    }

    private record Measurement(int quantity, String statistics) {
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
