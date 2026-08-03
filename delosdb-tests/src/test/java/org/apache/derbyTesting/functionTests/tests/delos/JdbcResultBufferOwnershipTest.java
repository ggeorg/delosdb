/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Correctness gate for future shared JDBC row and descriptor-buffer reuse.
 *
 * <p>Values retained by a consumer after a scan advances must not alias a
 * mutable execution buffer that the next row repopulates. This test records
 * values from direct, sort, distinct, aggregate, join, and scrollable result
 * paths for both heap and MVCC tables and verifies them after the result set
 * has advanced and closed.</p>
 */
public final class JdbcResultBufferOwnershipTest extends MvccSqlTestSupport {
    public void testHeapAndMvccResultValuesRemainDetachedAcrossScanAdvance() throws Exception {
        assertProviderOwnership(false);
        assertProviderOwnership(true);
    }

    private void assertProviderOwnership(boolean mvcc) throws Exception {
        String database = "row-buffer-ownership-" + (mvcc ? "mvcc-" : "heap-")
                + System.nanoTime();
        try (Connection connection = openDatabase("memory:" + database, true)) {
            createFixture(connection, mvcc);
            assertDetachedRows(connection,
                    "select id, payload, bytes_value from ownership_t order by id",
                    expectedById());
            assertDetachedRows(connection,
                    "select id, payload, bytes_value from ownership_t order by payload desc",
                    List.of(expected(3), expected(2), expected(1)));
            assertDetachedRows(connection,
                    "select distinct id, payload, bytes_value from ownership_t order by id",
                    expectedById());
            assertDetachedRows(connection,
                    "select a.id, a.payload, a.bytes_value from "
                            + "--DERBY-PROPERTIES joinOrder=FIXED \n"
                            + "ownership_t a --DERBY-PROPERTIES joinStrategy=HASH \n"
                            + "join ownership_t b on a.id = b.id order by a.id",
                    expectedById());
            assertAggregateValuesRemainDetached(connection);
            assertScrollableValuesRemainDetached(connection);
        } finally {
            shutdownNamedMemoryDatabase(database);
        }
    }

    private static void createFixture(Connection connection, boolean mvcc) throws Exception {
        executeUpdate(connection,
                "create table ownership_t (id int primary key, category int not null, "
                        + "payload varchar(4096) not null, "
                        + "bytes_value varchar(4096) for bit data not null)"
                        + (mvcc ? " using delos_mvcc" : ""));
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into ownership_t values (?, ?, ?, ?)")) {
            for (int id = 1; id <= 3; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 2);
                insert.setString(3, payload(id));
                insert.setBytes(4, bytes(id));
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(3, counts.length);
        }
    }

    private static void assertDetachedRows(
            Connection connection,
            String sql,
            List<RetainedRow> expected) throws Exception {
        List<RetainedRow> retained = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                int id = resultSet.getInt(1);
                String payload = resultSet.getString(2);
                byte[] bytes = resultSet.getBytes(3);
                retained.add(new RetainedRow(id, payload, bytes));
            }
        }
        assertEquals(expected, retained);
    }

    private static void assertAggregateValuesRemainDetached(Connection connection) throws Exception {
        List<String> retainedMinimums = new ArrayList<>();
        List<Long> retainedCounts = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select category, min(payload), count(*) from ownership_t "
                             + "group by category order by category")) {
            while (resultSet.next()) {
                retainedMinimums.add(resultSet.getString(2));
                retainedCounts.add(resultSet.getLong(3));
            }
        }
        assertEquals(List.of(payload(2), payload(1)), retainedMinimums);
        assertEquals(List.of(1L, 2L), retainedCounts);
    }

    private static void assertScrollableValuesRemainDetached(Connection connection) throws Exception {
        String retainedPayload;
        byte[] retainedBytes;
        try (Statement statement = connection.createStatement(
                ResultSet.TYPE_SCROLL_INSENSITIVE,
                ResultSet.CONCUR_READ_ONLY);
             ResultSet resultSet = statement.executeQuery(
                     "select id, payload, bytes_value from ownership_t order by id")) {
            assertTrue(resultSet.next());
            retainedPayload = resultSet.getString(2);
            retainedBytes = resultSet.getBytes(3);
            assertTrue(resultSet.last());
            assertEquals(3, resultSet.getInt(1));
            assertTrue(resultSet.previous());
            assertEquals(2, resultSet.getInt(1));
            assertTrue(resultSet.first());
            assertEquals(1, resultSet.getInt(1));
        }
        assertEquals(payload(1), retainedPayload);
        assertTrue(Arrays.equals(bytes(1), retainedBytes));
    }

    private static List<RetainedRow> expectedById() {
        return List.of(expected(1), expected(2), expected(3));
    }

    private static RetainedRow expected(int id) {
        return new RetainedRow(id, payload(id), bytes(id));
    }

    private static String payload(int id) {
        return "payload-" + id + "-" + "x".repeat(id * 257);
    }

    private static byte[] bytes(int id) {
        byte[] value = new byte[1024];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (id * 31 + index);
        }
        return value;
    }

    private record RetainedRow(int id, String payload, byte[] bytes) {
        @Override
        public boolean equals(Object other) {
            return other instanceof RetainedRow row
                    && id == row.id
                    && payload.equals(row.payload)
                    && Arrays.equals(bytes, row.bytes);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * Integer.hashCode(id) + payload.hashCode())
                    + Arrays.hashCode(bytes);
        }
    }
}
