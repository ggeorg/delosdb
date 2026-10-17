/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccGen2StreamingScanTest

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
import java.util.Map;
import java.util.TreeMap;

/**
 * The streaming Gen2 CURRENT scan returns exactly what the materialized scan
 * returns: the CURRENT image when it is visible, the archived predecessor
 * when it is not, and nothing for a visible tombstone. Streaming applies only
 * to non-holdable read-only scans, so every query here closes its cursor at
 * commit, and the runtime statistics confirm which scan ran.
 */
public final class MvccGen2StreamingScanTest extends MvccSqlTestSupport {
    private static final String GEN2_C1_PROPERTY =
            "delosdb.experimental.mvccGen2C1.history.enabled";
    private static final String[] STREAMING_PROPERTIES = {
            "delosdb.experimental.mvccGen2StreamingBulkScan.enabled",
            "delosdb.experimental.mvccGen2SinglePassCurrentScan.enabled",
            "delosdb.experimental.mvccGen2ReusableCurrentScanTemplate.enabled"
    };
    private static final String[] FAST_FETCH_PROPERTIES = {
            "delosdb.experimental.mvccGen2FastCurrentVisibilityFetch.enabled",
            "delosdb.experimental.mvccGen2SkipCurrentTagFetch.enabled",
            "delosdb.experimental.mvccGen2LazyCurrentCreatorFetch.enabled",
            "delosdb.experimental.mvccGen2LazyCurrentIdentityFetch.enabled"
    };
    private static final int ROWS = 400;

    private enum ScanMode {
        MATERIALIZED, STREAMING, STREAMING_FAST_FETCH
    }

    public void testStreamingScanMatchesTheMaterializedScan() throws Exception {
        String database = databaseName("mvcc-gen2-streaming-scan");
        try (SystemPropertyScope ignored = setSystemProperty(GEN2_C1_PROPERTY, "true")) {
            Map<Integer, String> original = new TreeMap<>();
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table G2_STREAM_T "
                        + "(id int not null, payload varchar(120) not null) using delos_mvcc");
                try (PreparedStatement insert = setup.prepareStatement(
                        "insert into G2_STREAM_T values (?, ?)")) {
                    for (int id = 1; id <= ROWS; id++) {
                        String payload = "v1-" + id + "-" + "x".repeat(90);
                        insert.setInt(1, id);
                        insert.setString(2, payload);
                        insert.addBatch();
                        original.put(id, payload);
                    }
                    insert.executeBatch();
                }
                setup.commit();
            }

            try (Connection historical = openDatabase(database, false);
                 Connection fresh = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                historical.setAutoCommit(false);
                historical.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                fresh.setAutoCommit(false);
                writer.setAutoCommit(false);
                assertEveryMode(historical, original);

                Map<Integer, String> committed = new TreeMap<>(original);
                executeUpdate(writer, "update G2_STREAM_T set payload = 'v2-' || "
                        + "cast(id as char(4)) where mod(id, 3) = 0");
                // Gen2-C1 allows one mutation per row per transaction.
                writer.commit();
                executeUpdate(writer, "delete from G2_STREAM_T where mod(id, 7) = 0");
                writer.commit();
                for (int id = 1; id <= ROWS; id++) {
                    if (id % 7 == 0) {
                        committed.remove(id);
                    } else if (id % 3 == 0) {
                        committed.put(id, "v2-" + String.format("%-4d", id));
                    }
                }

                // An uncommitted change makes the CURRENT image foreign to every
                // other reader, which must fall back to the committed predecessor.
                Map<Integer, String> uncommitted = new TreeMap<>(committed);
                executeUpdate(writer,
                        "update G2_STREAM_T set payload = 'v3' where mod(id, 5) = 0");
                for (Map.Entry<Integer, String> row : uncommitted.entrySet()) {
                    if (row.getKey() % 5 == 0) {
                        row.setValue("v3");
                    }
                }

                assertEveryMode(historical, original);
                assertEveryMode(fresh, committed);
                assertEveryMode(writer, uncommitted);
                writer.rollback();
                assertEveryMode(fresh, committed);
                historical.commit();
                fresh.commit();
            }
        } finally {
            shutdownDatabase(database);
        }
    }

    private static void assertEveryMode(Connection connection, Map<Integer, String> expected)
            throws Exception {
        executeUpdate(connection, "call syscs_util.syscs_set_runtimestatistics(1)");
        for (ScanMode mode : ScanMode.values()) {
            assertEquals(mode.name(), expected, scan(connection, mode));
            String statistics = runtimeStatistics(connection);
            assertEquals(mode.name() + " scan type; statistics=" + statistics,
                    mode != ScanMode.MATERIALIZED,
                    statistics.contains("delos_mvcc_streaming"));
        }
    }

    private static Map<Integer, String> scan(Connection connection, ScanMode mode)
            throws Exception {
        try (AutoCloseable streaming = scope(
                     STREAMING_PROPERTIES, mode != ScanMode.MATERIALIZED);
             AutoCloseable fastFetch = scope(
                     FAST_FETCH_PROPERTIES, mode == ScanMode.STREAMING_FAST_FETCH);
             Statement statement = connection.createStatement(
                     ResultSet.TYPE_FORWARD_ONLY,
                     ResultSet.CONCUR_READ_ONLY,
                     ResultSet.CLOSE_CURSORS_AT_COMMIT);
             ResultSet rows = statement.executeQuery("select id, payload from G2_STREAM_T")) {
            Map<Integer, String> seen = new TreeMap<>();
            while (rows.next()) {
                assertNull("row returned twice: " + rows.getInt(1),
                        seen.put(rows.getInt(1), rows.getString(2)));
            }
            return seen;
        }
    }

    private static AutoCloseable scope(String[] properties, boolean enabled) {
        SystemPropertyScope[] scopes = new SystemPropertyScope[properties.length];
        for (int index = 0; index < properties.length; index++) {
            scopes[index] = enabled
                    ? setSystemProperty(properties[index], "true")
                    : clearSystemProperty(properties[index]);
        }
        return () -> {
            for (int index = scopes.length - 1; index >= 0; index--) {
                scopes[index].close();
            }
        };
    }
}
