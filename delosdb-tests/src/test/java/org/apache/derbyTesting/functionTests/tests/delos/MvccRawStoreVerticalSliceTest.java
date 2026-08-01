/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccRawStoreVerticalSliceTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** SQL proof for the RawStore-owned MVCC table format. */
public final class MvccRawStoreVerticalSliceTest extends MvccSqlTestSupport {
    private static final String RETIRED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testRawStoreIsTheDefaultMvccFormat() throws Exception {
        String database = databaseName("mvcc-raw-store-default");
        List<String> evidence = runComparableWorkload(database);
        assertEquals(
                List.of(
                        "own=[10|alpha]",
                        "afterRollback=[10|alpha]",
                        "reopen=[10|alpha, 30|omega]"),
                evidence);
        assertEquals(0L, inheritedMvccStateFileCount(database));
    }

    public void testFileDatabaseCreateInsertVisibilityRollbackCommitAndReopen() throws Exception {
        String database = databaseName("mvcc-raw-store-vertical-slice-file");
        try (Connection writer = openDatabase(database, true)) {
            writer.setAutoCommit(false);
            executeUpdate(writer,
                    "create table raw_mvcc_rolled_back (id int) using delos_mvcc");
            writer.rollback();
            assertRows(writer,
                    "select count(*) from sys.systables "
                            + "where tablename = 'RAW_MVCC_ROLLED_BACK'",
                    "0");

            executeUpdate(writer,
                    "create table raw_mvcc_t (id int, name varchar(64)) using delos_mvcc");
            writer.commit();

            executeUpdate(writer, "insert into raw_mvcc_t values (1, 'committed')");
            assertRows(writer,
                    "select id, name from raw_mvcc_t where id = 1",
                    "1|committed");
            writer.commit();

            Savepoint beforeFirstMvccUse = writer.setSavepoint("before_first_mvcc_use");
            executeUpdate(writer, "insert into raw_mvcc_t values (4, 'savepoint-rollback')");
            writer.rollback(beforeFirstMvccUse);
            writer.releaseSavepoint(beforeFirstMvccUse);
            writer.commit();
            assertRows(writer, "select id, name from raw_mvcc_t where id = 4");

            executeUpdate(writer, "insert into raw_mvcc_t values (2, 'rolled-back')");
            writer.rollback();

            executeUpdate(writer, "insert into raw_mvcc_t values (3, 'after-rollback')");
            writer.commit();

            executeUpdate(writer,
                    "create table raw_mvcc_u (id int, name varchar(64)) using delos_mvcc");
            writer.commit();
            executeUpdate(writer, "insert into raw_mvcc_t values (5, 'first-table')");
            executeUpdate(writer, "insert into raw_mvcc_u values (6, 'second-table')");
            writer.rollback();
            assertRows(writer, "select id from raw_mvcc_t where id = 5");
            assertRows(writer, "select id from raw_mvcc_u where id = 6");
            writer.commit();

            try (Connection reader = openDatabase(database, false)) {
                assertRows(reader,
                        "select id, name from raw_mvcc_t order by id",
                        "1|committed",
                        "3|after-rollback");
            }
            assertEquals(0L, inheritedMvccStateFileCount(database));
        }
        shutdownDatabase(database);

        try (SystemPropertyScope ignored = setSystemProperty(RETIRED_PROPERTY, "false");
             Connection reopened = openDatabase(database, false)) {
            assertRows(reopened,
                    "select id, name from raw_mvcc_t order by id",
                    "1|committed",
                    "3|after-rollback");
        }
        shutdownDatabase(database);
    }

    public void testRetiredExternalFormatCannotBeReenabled() throws Exception {
        String database = databaseName("mvcc-retained-format-retired");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table retired_guard_t (id int) using delos_mvcc");
            executeUpdate(connection, "insert into retired_guard_t values 1");
            connection.commit();
        }
        shutdownDatabase(database);

        Path providerDirectory = databasePath(database).resolve("delos_mvcc");
        Files.createDirectories(providerDirectory);
        Path retainedMarker = providerDirectory.resolve("retained-format.marker");
        Files.writeString(retainedMarker, "retired");

        try (SystemPropertyScope ignored = setSystemProperty(RETIRED_PROPERTY, "false")) {
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + database)) {
                assertRows(connection, "select id from retired_guard_t", "1");
                fail("Retired external state must reject database boot");
            } catch (java.sql.SQLException expected) {
                assertTrue(expected.toString(),
                        containsMessage(expected, "retired external delos_mvcc format"));
            }
            shutdownDatabase(database);
        }

        Files.delete(retainedMarker);
        Files.delete(providerDirectory);
        try (Connection reopened = openDatabase(database, false)) {
            assertRows(reopened, "select id from retired_guard_t", "1");
        }
        shutdownDatabase(database);
    }

    public void testMemoryDatabaseUsesRawStoreAndLeavesNoFilesystemVolume() throws Exception {
        String database = "mvcc-raw-store-memory-" + System.nanoTime();
        String jdbcUrl = "jdbc:derby:memory:" + database;
        Path accidentalFilesystemDatabase = Path.of(database).toAbsolutePath().normalize();
        try (Connection connection = DriverManager.getConnection(jdbcUrl + ";create=true")) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table memory_raw_mvcc (id int, name varchar(64)) using delos_mvcc");
            executeUpdate(connection, "insert into memory_raw_mvcc values (1, 'memory')");
            connection.commit();
            assertRows(connection,
                    "select id, name from memory_raw_mvcc where id = 1",
                    "1|memory");
            connection.commit();
        } finally {
            try {
                DriverManager.getConnection(jdbcUrl + ";shutdown=true");
                fail("Memory database shutdown should throw the normal Derby shutdown exception");
            } catch (java.sql.SQLException expected) {
                assertEquals("08006", expected.getSQLState());
            }
        }
        assertFalse(Files.exists(accidentalFilesystemDatabase));
    }

    private static List<String> runComparableWorkload(String database) throws Exception {
        List<String> evidence = new ArrayList<>();
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table oracle_mvcc_t (id int, name varchar(64)) using delos_mvcc");
            connection.commit();

            executeUpdate(connection, "insert into oracle_mvcc_t values (10, 'alpha')");
            evidence.add("own=" + queryRows(
                    connection,
                    "select id, name from oracle_mvcc_t order by id"));
            connection.commit();

            executeUpdate(connection, "insert into oracle_mvcc_t values (20, 'rollback')");
            connection.rollback();
            evidence.add("afterRollback=" + queryRows(
                    connection,
                    "select id, name from oracle_mvcc_t order by id"));

            executeUpdate(connection, "insert into oracle_mvcc_t values (30, 'omega')");
            connection.commit();
        }
        shutdownDatabase(database);

        try (Connection reopened = openDatabase(database, false)) {
            evidence.add("reopen=" + queryRows(
                    reopened,
                    "select id, name from oracle_mvcc_t order by id"));
        }
        shutdownDatabase(database);
        return evidence;
    }

    private static List<String> queryRows(Connection connection, String sql) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int columnCount = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                StringBuilder row = new StringBuilder();
                for (int column = 1; column <= columnCount; column++) {
                    if (column > 1) {
                        row.append('|');
                    }
                    row.append(resultSet.getString(column));
                }
                rows.add(row.toString());
            }
        }
        return rows;
    }
}
