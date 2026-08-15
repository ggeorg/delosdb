/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCurrentRowArchitecturePermanentTest

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

/** Proves the accepted MVCC current-row architecture is permanent default behavior. */
public final class MvccCurrentRowArchitecturePermanentTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-current-row-architecture-permanent";

    public void testRetiredExperimentalFlagsCannotDisablePermanentCurrentRowPath()
            throws Exception {
        assertEquals("false", System.getProperty("delosdb.experimental.mvccCurrentRowAnchor"));
        assertEquals("false", System.getProperty("delosdb.experimental.btreePrefixLeafSnapshot"));
        assertEquals("false", System.getProperty("delosdb.experimental.btreePrefixBranchSnapshot"));
        assertEquals("false", System.getProperty("delosdb.experimental.mvccCurrentVersionReadImage"));
        assertNull(System.getProperty("delosdb.mvcc.currentRowReadCache.slots"));

        String database = databaseName(DATABASE);
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table permanent_t (id int not null primary key, quantity int not null) using delos_mvcc");
            executeUpdate(connection, "insert into permanent_t values (1, 10)");
            connection.commit();

            Measurement first = measuredRead(connection, 1);
            assertEquals(10, first.quantity());
            assertTrue(metric(first.statistics(), "mvccCurrentRowAnchorHits") >= 1L);
            assertEquals(0L, metric(first.statistics(), "mvccDirectoryPageAcquisitions"));
            assertTrue(metric(first.statistics(), "mvccCurrentVersionReadImageFallbacks") >= 1L);
            assertTrue(metric(first.statistics(), "mvccVersionPageAcquisitions") >= 1L);

            Measurement second = measuredRead(connection, 1);
            assertEquals(10, second.quantity());
            assertTrue(metric(second.statistics(), "mvccCurrentRowAnchorHits") >= 1L);
            assertTrue(metric(second.statistics(), "mvccCurrentVersionReadImageHits") >= 1L);
            assertEquals(0L, metric(second.statistics(), "mvccDirectoryPageAcquisitions"));
            assertEquals(0L, metric(second.statistics(), "mvccVersionPageAcquisitions"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    private static Measurement measuredRead(Connection connection, int id) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        int quantity;
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from permanent_t where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue(resultSet.next());
                quantity = resultSet.getInt(1);
                assertFalse(resultSet.next());
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "values syscs_util.syscs_get_runtimestatistics()")) {
            assertTrue(resultSet.next());
            return new Measurement(quantity, resultSet.getString(1));
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

    private record Measurement(int quantity, String statistics) {
    }
}
