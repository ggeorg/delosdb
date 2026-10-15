/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccUniqueIndexKeyReuseTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */

package org.apache.derbyTesting.functionTests.tests.delos;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stage 3 of docs/design/MVCC-SECONDARY-INDEX-VISIBILITY.md: a unique index on
 * an MVCC table keeps a deleted entry beside a new entry with the same key, so
 * an older snapshot still finds the deleted row, and uniqueness is unchanged.
 */
public final class MvccUniqueIndexKeyReuseTest extends MvccSqlTestSupport {

    public void testReusedKeyKeepsBothEntriesAcrossRestart() throws Exception {
        String database = databaseName("mvcc-unique-key-reuse-shape");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            createTable(connection);
            reuseKey(connection, 1, "one-v2");
            assertEquals(1, deleted(connection, "reuse_pk"));
            connection.commit();
        }
        shutdownDatabase(database);
        try (Connection connection = openDatabase(database, false)) {
            connection.setAutoCommit(false);
            reuseKey(connection, 1, "one-v3");
            assertEquals(2, deleted(connection, "reuse_pk"));

            // An index built over existing rows has the same shape.
            executeUpdate(connection, "create unique index reuse_payload on reuse_t(payload)");
            connection.commit();
            executeUpdate(connection, "delete from reuse_t where id = 2");
            connection.commit();
            executeUpdate(connection, "insert into reuse_t values (3, 'two', 'three@example')");
            connection.commit();
            assertEquals(1, MvccRawStoreMetadataInspection.deletedIndexEntryCount(
                    connection, "REUSE_PAYLOAD"));

            // Stage 2 purges the retained entry once no snapshot needs it.
            inPlaceCompressTable(connection, "REUSE_T");
            connection.commit();
            assertEquals(0, deleted(connection, "reuse_pk"));
            assertEquals(0, MvccRawStoreMetadataInspection.deletedIndexEntryCount(
                    connection, "REUSE_PAYLOAD"));
            assertRows(connection,
                    "select id, payload from reuse_t --DERBY-PROPERTIES constraint=reuse_pk\n"
                            + " order by id",
                    "1|one-v3", "3|two");
            connection.commit();
        }
    }

    public void testRollbackOfReuseLeavesTheDeletedRowVisible() throws Exception {
        String database = databaseName("mvcc-unique-key-reuse-rollback");
        try (Connection setup = openDatabase(database, true)) {
            setup.setAutoCommit(false);
            createTable(setup);
        }
        try (Connection reader = openDatabase(database, false);
             Connection writer = openDatabase(database, false)) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            writer.setAutoCommit(false);
            assertRows(reader, "select count(*) from reuse_t", "2");

            executeUpdate(writer, "delete from reuse_t where id = 1");
            writer.commit();
            executeUpdate(writer, "insert into reuse_t values (1, 'one-v2', 'one@example')");
            writer.rollback();

            // Undoing an index insert marks the new entry deleted too.
            assertEquals(2, deleted(writer, "reuse_pk"));
            assertEquals(2, deleted(writer, "reuse_email"));
            assertRows(reader,
                    "select payload from reuse_t --DERBY-PROPERTIES constraint=reuse_pk\n"
                            + " where id = 1",
                    "one");
            assertRows(reader,
                    "select id from reuse_t --DERBY-PROPERTIES constraint=reuse_email\n"
                            + " where email = 'one@example'",
                    "1");
            assertRows(writer,
                    "select count(*) from reuse_t --DERBY-PROPERTIES constraint=reuse_pk\n"
                            + " where id = 1",
                    "0");
            reader.commit();

            inPlaceCompressTable(writer, "REUSE_T");
            writer.commit();
            assertEquals(0, deleted(writer, "reuse_pk"));
            assertEquals(0, deleted(writer, "reuse_email"));
            writer.commit();
        }
    }

    public void testUniquenessMatchesHeapTables() throws Exception {
        String database = databaseName("mvcc-unique-key-reuse-semantics");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            List<String> heap = uniquenessOutcomes(connection, "heap_u", "");
            assertEquals(List.of(
                    "ok", "23505", "ok", "ok", "23505", "ok", "ok", "ok", "ok", "23505"), heap);
            assertEquals(heap, uniquenessOutcomes(connection, "mvcc_u", " using delos_mvcc"));
        }
    }

    /**
     * Runs one statement after another on a new table and records "ok" or the
     * SQLState of each.
     */
    private static List<String> uniquenessOutcomes(
            Connection connection, String table, String providerClause) throws SQLException {
        executeUpdate(connection, "create table " + table + " (id int not null, code int,"
                + " nullable_code int, constraint " + table + "_pk primary key (id),"
                + " constraint " + table + "_nullable unique (nullable_code))" + providerClause);
        executeUpdate(connection, "create unique index " + table + "_code on " + table + "(code)");
        connection.commit();
        String[] statements = {
            "insert into " + table + " values (1, 10, null)",
            // A live primary key.
            "insert into " + table + " values (1, 11, null)",
            // A unique constraint on a nullable column allows duplicate NULLs.
            "insert into " + table + " values (2, 20, null)",
            // A unique index treats NULLs as equal, also after a delete.
            "insert into " + table + " values (3, null, 30)",
            "insert into " + table + " values (4, null, 40)",
            "delete from " + table + " where id = 3",
            "insert into " + table + " values (4, null, 40)",
            // A key moved away and back while another row holds it.
            "update " + table + " set code = 12 where id = 1",
            "insert into " + table + " values (5, 10, 50)",
            "update " + table + " set code = 10 where id = 1",
        };
        List<String> outcomes = new ArrayList<>();
        for (String statement : statements) {
            try {
                executeUpdate(connection, statement);
                connection.commit();
                outcomes.add("ok");
            } catch (SQLException failure) {
                connection.rollback();
                outcomes.add(failure.getSQLState());
            }
        }
        return outcomes;
    }

    private static void createTable(Connection connection) throws SQLException {
        executeUpdate(connection,
                "create table reuse_t (id int not null, payload varchar(32),"
                        + " email varchar(32) not null, constraint reuse_pk primary key (id),"
                        + " constraint reuse_email unique (email)) using delos_mvcc");
        executeUpdate(connection, "insert into reuse_t values (1, 'one', 'one@example')");
        executeUpdate(connection, "insert into reuse_t values (2, 'two', 'two@example')");
        connection.commit();
    }

    private static void reuseKey(Connection connection, int id, String payload)
            throws SQLException {
        executeUpdate(connection, "delete from reuse_t where id = " + id);
        connection.commit();
        executeUpdate(connection, "insert into reuse_t values (" + id + ", '" + payload
                + "', 'key-" + payload + "')");
        connection.commit();
    }

    private static int deleted(Connection connection, String constraintName) throws Exception {
        return MvccRawStoreMetadataInspection.deletedIndexEntryCount(connection,
                MvccRawStoreMetadataInspection.backingIndexName(connection, constraintName));
    }
}
