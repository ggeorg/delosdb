/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSecondaryIndexReclamationTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;

/**
 * Stage 2 of docs/design/MVCC-SECONDARY-INDEX-VISIBILITY.md: deleted-marked
 * entries of indexes on MVCC tables stay while a snapshot can need them, and
 * in-place compress purges them once none can.
 */
public final class MvccSecondaryIndexReclamationTest extends MvccSqlTestSupport {
    private static final int ROWS = 200;

    public void testCompressPurgesDeletedEntriesNoSnapshotNeeds() throws Exception {
        String database = databaseName("mvcc-index-reclamation-purge");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            createTable(connection);
            executeUpdate(connection, "delete from reclaim_t where mod(id, 2) = 0");
            connection.commit();
            assertEquals(ROWS / 2, deleted(connection, "reclaim_bucket"));
            assertEquals(ROWS / 2, deleted(connection, "reclaim_pk"));

            inPlaceCompressTable(connection, "RECLAIM_T");
            connection.commit();

            assertEquals(0, deleted(connection, "reclaim_bucket"));
            assertEquals(0, deleted(connection, "reclaim_pk"));
            connection.commit();
        }
        // The purges are committed and logged, so they survive a restart.
        shutdownDatabase(database);
        try (Connection connection = openDatabase(database, false)) {
            connection.setAutoCommit(false);
            assertEquals(0, deleted(connection, "reclaim_bucket"));
            assertEquals(0, deleted(connection, "reclaim_pk"));
            assertRows(connection, "select count(*) from reclaim_t", Integer.toString(ROWS / 2));
            assertRows(connection, "select count(*) from reclaim_t where bucket between 0 and 9",
                    Integer.toString(ROWS / 2));
            assertRows(connection, "select max(id) from reclaim_t", Integer.toString(ROWS - 1));
            connection.commit();
        }
    }

    public void testCompressKeepsDeletedEntriesAnOlderSnapshotNeeds() throws Exception {
        String database = databaseName("mvcc-index-reclamation-snapshot");
        try (Connection setup = openDatabase(database, true)) {
            setup.setAutoCommit(false);
            createTable(setup);
        }
        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            writer.setAutoCommit(false);
            assertRows(reader, "select count(*) from reclaim_t", Integer.toString(ROWS));

            executeUpdate(writer, "delete from reclaim_t where mod(id, 2) = 0");
            writer.commit();
            inPlaceCompressTable(writer, "RECLAIM_T");
            writer.commit();

            assertEquals(ROWS / 2, deleted(writer, "reclaim_bucket"));
            assertRows(reader, "select count(*) from reclaim_t where bucket between 0 and 9",
                    Integer.toString(ROWS));
            assertRows(reader, "select max(id) from reclaim_t", Integer.toString(ROWS));
            reader.commit();

            inPlaceCompressTable(writer, "RECLAIM_T");
            writer.commit();
            assertEquals(0, deleted(writer, "reclaim_bucket"));
            assertRows(reader, "select count(*) from reclaim_t where bucket between 0 and 9",
                    Integer.toString(ROWS / 2));
            assertRows(reader, "select max(id) from reclaim_t", Integer.toString(ROWS - 1));
            reader.commit();
            writer.commit();
        }
    }

    public void testCompressPurgesEntriesOfMovedKeys() throws Exception {
        String database = databaseName("mvcc-index-reclamation-moved");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            createTable(connection);
            executeUpdate(connection, "update reclaim_t set bucket = bucket + 100 where id <= 50");
            connection.commit();
            assertEquals(50, deleted(connection, "reclaim_bucket"));
            assertEquals(0, deleted(connection, "reclaim_pk"));

            inPlaceCompressTable(connection, "RECLAIM_T");
            connection.commit();

            assertEquals(0, deleted(connection, "reclaim_bucket"));
            assertRows(connection, "select count(*) from reclaim_t where bucket >= 100", "50");
            assertRows(connection, "select count(*) from reclaim_t where bucket < 100",
                    Integer.toString(ROWS - 50));
            connection.commit();
        }
    }

    public void testIndexPurgeWaitsForOtherWritersToEnd() throws Exception {
        String database = databaseName("mvcc-index-reclamation-writer");
        try (Connection setup = openDatabase(database, true)) {
            setup.setAutoCommit(false);
            createTable(setup);
            executeUpdate(setup, "delete from reclaim_t where id <= 10");
            setup.commit();
        }
        try (Connection writer = openDatabase(database, false);
             Connection maintenance = openDatabase(database, false)) {
            writer.setAutoCommit(false);
            maintenance.setAutoCommit(false);
            executeUpdate(writer, "delete from reclaim_t where id between 11 and 20");

            // The writer may have marked entries it can still roll back, so
            // no entry of the table is judged while it is active.
            MvccRawStoreMetadataInspection.purgeIndexConglomerate(maintenance, "RECLAIM_BUCKET");
            maintenance.commit();
            assertEquals(20, deleted(maintenance, "reclaim_bucket"));

            writer.rollback();
            MvccRawStoreMetadataInspection.purgeIndexConglomerate(maintenance, "RECLAIM_BUCKET");
            maintenance.commit();
            assertEquals(0, deleted(maintenance, "reclaim_bucket"));
            assertRows(maintenance, "select count(*) from reclaim_t where bucket between 0 and 9",
                    Integer.toString(ROWS - 10));
            maintenance.commit();
        }
    }

    private static void createTable(Connection connection) throws Exception {
        executeUpdate(connection,
                "create table reclaim_t (id int not null, payload varchar(32), bucket int,"
                        + " constraint reclaim_pk primary key (id)) using delos_mvcc");
        executeUpdate(connection, "create index reclaim_bucket on reclaim_t(bucket)");
        for (int id = 1; id <= ROWS; id++) {
            executeUpdate(connection, "insert into reclaim_t values (" + id + ", 'row-" + id
                    + "', " + (id % 10) + ")");
        }
        connection.commit();
    }

    private static int deleted(Connection connection, String indexName) throws Exception {
        return MvccRawStoreMetadataInspection.deletedIndexEntryCount(
                connection, MvccRawStoreMetadataInspection.backingIndexName(connection, indexName));
    }
}
