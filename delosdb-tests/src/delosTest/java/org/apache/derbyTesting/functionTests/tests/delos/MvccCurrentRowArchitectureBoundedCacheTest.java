/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCurrentRowArchitectureBoundedCacheTest

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

/** Collision/identity proof for the bounded Current-Row Anchor and version-image arrays. */
public final class MvccCurrentRowArchitectureBoundedCacheTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-current-row-architecture-bounded-cache";
    private static final String TABLE = "BOUNDED_CURRENT_ROW_T";

    public void testTinyFourSlotCacheNeverCrossesRowOrVersionIdentity() throws Exception {
        assertEquals("true", System.getProperty("delosdb.experimental.mvccCurrentRowAnchor"));
        assertEquals("4", System.getProperty("delosdb.experimental.mvccCurrentRowAnchor.slots"));
        assertEquals("true", System.getProperty("delosdb.experimental.btreePrefixLeafSnapshot"));
        assertEquals("true", System.getProperty("delosdb.experimental.btreePrefixBranchSnapshot"));
        assertEquals("true", System.getProperty("delosdb.experimental.mvccCurrentVersionReadImage"));
        String database = databaseName(DATABASE);
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table " + TABLE
                            + " (id int not null primary key, quantity int not null,"
                            + " payload varchar(64) not null) using delos_mvcc");
            writeRound(connection, 0, true);
            connection.commit();

            for (int round = 0; round <= 5; round++) {
                assertRound(connection, round, false);
                assertRound(connection, round, true);
                if (round < 5) {
                    writeRound(connection, round + 1, false);
                    connection.commit();
                }
            }

            Measurement first = measuredRead(connection, 97);
            Measurement second = measuredRead(connection, 97);
            Measurement third = measuredRead(connection, 97);
            assertEquals(expectedQuantity(5, 97), first.quantity());
            assertEquals(expectedQuantity(5, 97), second.quantity());
            assertEquals(expectedQuantity(5, 97), third.quantity());

            assertEquals(0L, metric(first.statistics(), "mvccCurrentRowAnchorChecks"));
            assertEquals(0L, metric(first.statistics(), "mvccCurrentRowAnchorHits"));
            assertEquals(0L, metric(first.statistics(), "mvccCurrentRowAnchorFallbacks"));
            assertTrue("collision sweep must evict the target anchor; statistics="
                            + first.statistics(),
                    metric(first.statistics(), "mvccDirectoryPageAcquisitions") >= 1L);
            assertEquals(0L, metric(first.statistics(), "mvccCurrentVersionReadImageChecks"));

            long secondAnchorChecks =
                    metric(second.statistics(), "mvccCurrentRowAnchorChecks");
            long secondAnchorHits =
                    metric(second.statistics(), "mvccCurrentRowAnchorHits");
            assertTrue("second read must hit the rebuilt target anchor; statistics="
                            + second.statistics(),
                    secondAnchorHits >= 1L);
            assertEquals(secondAnchorChecks, secondAnchorHits);
            assertEquals(0L, metric(second.statistics(), "mvccCurrentRowAnchorFallbacks"));
            assertEquals(0L, metric(second.statistics(), "mvccDirectoryPageAcquisitions"));
            long secondImageChecks =
                    metric(second.statistics(), "mvccCurrentVersionReadImageChecks");
            assertTrue("second read must check the empty target version image; statistics="
                            + second.statistics(),
                    secondImageChecks >= 1L);
            assertEquals(0L, metric(second.statistics(), "mvccCurrentVersionReadImageHits"));
            assertEquals(secondImageChecks,
                    metric(second.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertTrue("second read must rebuild the image from authoritative version storage; "
                            + "statistics=" + second.statistics(),
                    metric(second.statistics(), "mvccVersionPageAcquisitions") >= 1L);

            long thirdAnchorChecks =
                    metric(third.statistics(), "mvccCurrentRowAnchorChecks");
            long thirdAnchorHits =
                    metric(third.statistics(), "mvccCurrentRowAnchorHits");
            assertTrue("third read must hit the rebuilt target anchor; statistics="
                            + third.statistics(),
                    thirdAnchorHits >= 1L);
            assertEquals(thirdAnchorChecks, thirdAnchorHits);
            assertEquals(0L, metric(third.statistics(), "mvccCurrentRowAnchorFallbacks"));
            assertEquals(0L, metric(third.statistics(), "mvccDirectoryPageAcquisitions"));
            long thirdImageChecks =
                    metric(third.statistics(), "mvccCurrentVersionReadImageChecks");
            long thirdImageHits =
                    metric(third.statistics(), "mvccCurrentVersionReadImageHits");
            assertTrue("third read must hit the rebuilt target version image; statistics="
                            + third.statistics(),
                    thirdImageHits >= 1L);
            assertEquals(thirdImageChecks, thirdImageHits);
            assertEquals(0L, metric(third.statistics(), "mvccCurrentVersionReadImageFallbacks"));
            assertEquals(0L, metric(third.statistics(), "mvccVersionPageAcquisitions"));
            connection.commit();
        }
        shutdownDatabase(database);
    }

    private static void writeRound(Connection connection, int round, boolean insert)
            throws Exception {
        String sql = insert
                ? "insert into " + TABLE + " values (?, ?, ?)"
                : "update " + TABLE + " set quantity = ?, payload = ? where id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int id = 1; id <= 97; id++) {
                if (insert) {
                    statement.setInt(1, id);
                    statement.setInt(2, expectedQuantity(round, id));
                    statement.setString(3, expectedPayload(round, id));
                } else {
                    statement.setInt(1, expectedQuantity(round, id));
                    statement.setString(2, expectedPayload(round, id));
                    statement.setInt(3, id);
                }
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void assertRound(Connection connection, int round, boolean reverse)
            throws Exception {
        if (reverse) {
            for (int id = 97; id >= 1; id--) {
                assertRow(connection, round, id);
            }
        } else {
            for (int id = 1; id <= 97; id++) {
                assertRow(connection, round, id);
            }
        }
    }

    private static void assertRow(Connection connection, int round, int id) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity, payload from " + TABLE + " where id = ?")) {
            statement.setInt(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertTrue("missing row " + id + " in round " + round, resultSet.next());
                assertEquals(expectedQuantity(round, id), resultSet.getInt(1));
                assertEquals(expectedPayload(round, id), resultSet.getString(2));
                assertFalse(resultSet.next());
            }
        }
    }

    private static Measurement measuredRead(Connection connection, int id) throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        int quantity;
        try (PreparedStatement statement = connection.prepareStatement(
                "select quantity from " + TABLE + " where id = ?")) {
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

    private static int expectedQuantity(int round, int id) {
        return round * 100000 + id;
    }

    private static String expectedPayload(int round, int id) {
        return "round-" + round + "-row-" + id;
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
