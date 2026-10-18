/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.BTreePrefixSnapshotReadTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.apache.derby.impl.store.access.btree.BTreePrefixSnapshotReadTestSupport;

/**
 * A single-row fetch on a strict prefix of a unique index key can be answered
 * from a cached leaf snapshot without latching the index. The path applies
 * only to a three-level tree and to locking policies that hold no row locks
 * across the read: READ UNCOMMITTED on heap tables and every isolation on
 * delos_mvcc tables. These tests prove it runs, returns what the latched scan
 * returns, never serves a leaf that changed, and keeps MVCC snapshot
 * visibility. MVCC scans must see deleted entries, so a prefix range holding
 * one falls back to the latched scan until vacuum purges it.
 */
public final class BTreePrefixSnapshotReadTest extends MvccSqlTestSupport {
    // Wide keys put Derby's index on 32K pages; this many keys gives the
    // root-branch-leaf shape the prefix snapshot requires.
    private static final int KEYS = 1600;
    private static final int ROWS_PER_KEY = 3;
    private static final int PROBE_KEY = KEYS / 2;
    private static final String PREFIX_READ =
            "select a, b from T --DERBY-PROPERTIES index=T_AB, bulkFetch=1\n where a = ?";
    private static final String BULK_READ =
            "select a, b from T --DERBY-PROPERTIES index=T_AB\n where a = ?";

    public void testHeapReadUncommittedPrefixReadMatchesTheLatchedScan() throws Exception {
        String database = databaseName("btree-prefix-snapshot-heap");
        try {
            try (Connection connection = openDatabase(database, true)) {
                populate(connection, "");
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
                assertPrefixReadTracksChanges(connection, connection);
            }
        } finally {
            shutdownDatabase(database);
        }
    }

    public void testMvccPrefixReadMatchesTheLatchedScanAndKeepsSnapshots() throws Exception {
        String database = databaseName("btree-prefix-snapshot-mvcc");
        try {
            try (Connection connection = openDatabase(database, true)) {
                populate(connection, " using delos_mvcc");
            }
            try (Connection reader = openDatabase(database, false);
                 Connection snapshot = openDatabase(database, false);
                 Connection writer = openDatabase(database, false)) {
                reader.setAutoCommit(false);
                writer.setAutoCommit(false);
                snapshot.setAutoCommit(false);
                snapshot.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                List<String> original = expectedRows();
                read(snapshot, PREFIX_READ);
                assertEquals(original, prefixReadHit(snapshot));
                read(reader, PREFIX_READ);
                assertEquals(original, prefixReadHit(reader));
                reader.commit();

                executeUpdate(writer,
                        "insert into T values (" + PROBE_KEY + ", '" + bValue(7) + "', 0)");
                writer.commit();
                List<String> inserted = new ArrayList<>(original);
                inserted.add(rowText(7));

                // The new entry is in the leaf the snapshot reader also reads
                // from; its visibility check must hide it from that reader.
                read(reader, PREFIX_READ);
                assertEquals(inserted, prefixReadHit(reader));
                assertEquals(inserted, read(reader, BULK_READ));
                reader.commit();
                assertEquals(original, prefixReadHit(snapshot));
                snapshot.commit();

                executeUpdate(writer,
                        "delete from T where a = " + PROBE_KEY + " and b = '" + bValue(1) + "'");
                writer.commit();
                List<String> deleted = new ArrayList<>(inserted);
                deleted.remove(rowText(1));
                assertEquals(deleted, read(reader, PREFIX_READ));
                assertEquals(deleted, read(reader, BULK_READ));
                reader.commit();

                inPlaceCompressTable(writer, "T");
                writer.commit();
                read(reader, PREFIX_READ);
                assertEquals(deleted, prefixReadHit(reader));
                reader.commit();
            }
        } finally {
            shutdownDatabase(database);
        }
    }

    private static void assertPrefixReadTracksChanges(Connection reader, Connection writer)
            throws Exception {
        List<String> expected = expectedRows();
        // The first read observes the leaf; later reads are answered from it.
        read(reader, PREFIX_READ);
        assertEquals(expected, prefixReadHit(reader));
        assertEquals(expected, read(reader, BULK_READ));

        executeUpdate(writer,
                "insert into T values (" + PROBE_KEY + ", '" + bValue(7) + "', 0)");
        executeUpdate(writer,
                "delete from T where a = " + PROBE_KEY + " and b = '" + bValue(1) + "'");
        writer.commit();
        expected.remove(rowText(1));
        expected.add(rowText(7));

        // The first read after the change may observe the leaf again; it must
        // already see the change, and the next read must hit the new snapshot.
        assertEquals(expected, read(reader, PREFIX_READ));
        assertEquals(expected, prefixReadHit(reader));
        assertEquals(expected, read(reader, BULK_READ));
        reader.commit();
    }

    private static List<String> prefixReadHit(Connection connection) throws Exception {
        long hitsBefore = BTreePrefixSnapshotReadTestSupport.hits();
        List<String> rows = read(connection, PREFIX_READ);
        assertTrue("prefix read must be answered from the leaf snapshot",
                BTreePrefixSnapshotReadTestSupport.hits() > hitsBefore);
        return rows;
    }

    private static void populate(Connection connection, String provider) throws Exception {
        connection.setAutoCommit(false);
        executeUpdate(connection,
                "create table T (a int not null, b varchar(600) not null, v int)" + provider);
        executeUpdate(connection, "create unique index T_AB on T(a, b)");
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into T values (?, ?, ?)")) {
            for (int a = 1; a <= KEYS; a++) {
                for (int j = 0; j < ROWS_PER_KEY; j++) {
                    insert.setInt(1, a);
                    insert.setString(2, bValue(j));
                    insert.setInt(3, a * 10 + j);
                    insert.addBatch();
                }
            }
            insert.executeBatch();
        }
        connection.commit();
    }

    private static List<String> read(Connection connection, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        // A holdable cursor never takes the unlatched prefix path.
        try (PreparedStatement query = connection.prepareStatement(sql,
                ResultSet.TYPE_FORWARD_ONLY,
                ResultSet.CONCUR_READ_ONLY,
                ResultSet.CLOSE_CURSORS_AT_COMMIT)) {
            query.setInt(1, PROBE_KEY);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    rows.add(result.getInt(1) + "|" + result.getString(2).substring(0, 3));
                }
            }
        }
        return rows;
    }

    private static List<String> expectedRows() {
        List<String> rows = new ArrayList<>();
        for (int j = 0; j < ROWS_PER_KEY; j++) {
            rows.add(rowText(j));
        }
        return rows;
    }

    private static String rowText(int j) {
        return PROBE_KEY + "|" + String.format("%03d", j);
    }

    private static String bValue(int j) {
        return String.format("%03d", j) + "x".repeat(580);
    }
}
