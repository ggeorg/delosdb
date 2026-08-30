/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccM2CurrentRowFormatTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;

/** Focused lifecycle and physical-shape proof for the experimental M2 current-row format. */
public final class MvccM2CurrentRowFormatTest extends MvccSqlTestSupport {
    private static final String DATABASE = "mvcc-m2-current-row-format";

    public void testInsertUpdateSameTransactionRollbackAndReopen() throws Exception {
        String database = databaseName(DATABASE + "-mutation");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table m2_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(connection, "insert into m2_t values (1, 10)");
            connection.commit();

            assertRows(connection, "select id, value from m2_t", "1|10");
            assertPhysicalState(connection, "M2_T", 1, 1, 0, 0, 0, 0);

            executeUpdate(connection, "update m2_t set value = 20 where id = 1");
            executeUpdate(connection, "update m2_t set value = 30 where id = 1");
            connection.commit();

            assertRows(connection, "select id, value from m2_t", "1|30");
            assertPhysicalState(connection, "M2_T", 1, 1, 0, 0, 1, 0);

            executeUpdate(connection, "update m2_t set value = 40 where id = 1");
            connection.rollback();

            assertRows(connection, "select id, value from m2_t", "1|30");
            assertPhysicalState(connection, "M2_T", 1, 1, 0, 0, 1, 0);
            connection.commit();
        }
        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            reopened.setAutoCommit(false);
            assertRows(reopened, "select id, value from m2_t", "1|30");
            assertPhysicalState(reopened, "M2_T", 1, 1, 0, 0, 1, 0);
            reopened.commit();
        }
        shutdownDatabase(database);
    }

    public void testOldSnapshotVacuumDeleteAndPurge() throws Exception {
        String database = databaseName(DATABASE + "-history");
        try (Connection setup = openDatabase(database, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup,
                    "create table m2_history_t (id int primary key, value int) using delos_mvcc");
            executeUpdate(setup, "insert into m2_history_t values (1, 10)");
            setup.commit();
        }

        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false);
             Connection vacuum = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            writer.setAutoCommit(false);
            vacuum.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

            assertRows(reader, "select value from m2_history_t where id = 1", "10");

            executeUpdate(writer, "update m2_history_t set value = 20 where id = 1");
            writer.commit();

            assertRows(reader, "select value from m2_history_t where id = 1", "10");
            assertRows(vacuum, "select value from m2_history_t where id = 1", "20");
            assertPhysicalState(vacuum, "M2_HISTORY_T", 1, 1, 0, 0, 1, 0);

            inPlaceCompressTable(vacuum, "M2_HISTORY_T");
            vacuum.commit();
            assertPhysicalState(vacuum, "M2_HISTORY_T", 1, 1, 0, 0, 1, 0);
            assertRows(reader, "select value from m2_history_t where id = 1", "10");

            reader.rollback();

            inPlaceCompressTable(vacuum, "M2_HISTORY_T");
            vacuum.commit();
            assertPhysicalState(vacuum, "M2_HISTORY_T", 1, 1, 0, 0, 0, 0);
            assertRows(vacuum, "select value from m2_history_t where id = 1", "20");

            executeUpdate(writer, "delete from m2_history_t where id = 1");
            writer.commit();
            assertPhysicalState(vacuum, "M2_HISTORY_T", 1, 0, 1, 0, 1, 0);
            assertRows(vacuum, "select value from m2_history_t where id = 1");

            inPlaceCompressTable(vacuum, "M2_HISTORY_T");
            vacuum.commit();
            assertPhysicalState(vacuum, "M2_HISTORY_T", 0, 0, 0, 0, 0, 0);
            assertRows(vacuum, "select value from m2_history_t where id = 1");
            vacuum.commit();
        }
        shutdownDatabase(database);
    }

    private static void assertPhysicalState(
            Connection connection,
            String table,
            int currentRows,
            int liveCurrentRows,
            int tombstoneCurrentRows,
            int legacyDirectoryRows,
            int historyVersions,
            int duplicateCurrentVersions) throws Exception {
        MvccRawStoreMetadataInspection.M2PhysicalState state =
                MvccRawStoreMetadataInspection.m2PhysicalState(connection, table);
        assertEquals("current rows: " + state, currentRows, state.currentRows());
        assertEquals("live current rows: " + state, liveCurrentRows, state.liveCurrentRows());
        assertEquals(
                "tombstone current rows: " + state,
                tombstoneCurrentRows,
                state.tombstoneCurrentRows());
        assertEquals(
                "legacy directory rows: " + state,
                legacyDirectoryRows,
                state.legacyDirectoryRows());
        assertEquals("history versions: " + state, historyVersions, state.historyVersions());
        assertEquals(
                "current payload must never be duplicated in history: " + state,
                duplicateCurrentVersions,
                state.duplicateCurrentVersions());
    }
}
