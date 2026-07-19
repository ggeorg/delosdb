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

/** SQL proof for the first isolated RawStore-owned MVCC table format. */
public final class MvccRawStoreVerticalSliceTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";

    public void testSupportedWorkloadMatchesEarlierFormatOracle() throws Exception {
        String legacyDatabase = databaseName("mvcc-raw-store-oracle-legacy");
        String rawStoreDatabase = databaseName("mvcc-raw-store-oracle-raw");

        List<String> legacyEvidence;
        try (SystemPropertyScope ignored = clearSystemProperty(ENABLED_PROPERTY)) {
            legacyEvidence = runComparableWorkload(legacyDatabase);
        }

        List<String> rawStoreEvidence;
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            rawStoreEvidence = runComparableWorkload(rawStoreDatabase);
            assertEquals(
                    "the RawStore comparison database must not create earlier-format sidecars",
                    0L,
                    inheritedMvccStateFileCount(rawStoreDatabase));
        }

        assertEquals(
                "the isolated RawStore format must match the earlier-format oracle for the supported workload",
                legacyEvidence,
                rawStoreEvidence);
    }

    public void testFileDatabaseCreateInsertVisibilityRollbackCommitAndReopen() throws Exception {
        String database = databaseName("mvcc-raw-store-vertical-slice-file");
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
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
                assertRows(writer,
                        "select id, name from raw_mvcc_t where id = 4");

                executeUpdate(writer, "insert into raw_mvcc_t values (2, 'rolled-back')");
                assertRows(writer,
                        "select id, name from raw_mvcc_t order by id",
                        "1|committed",
                        "2|rolled-back");
                writer.rollback();

                executeUpdate(writer, "insert into raw_mvcc_t values (3, 'after-rollback')");
                writer.commit();

                executeUpdate(writer,
                        "create table raw_mvcc_u (id int, name varchar(64)) using delos_mvcc");
                writer.commit();
                executeUpdate(writer, "insert into raw_mvcc_t values (5, 'first-table')");
                try {
                    executeUpdate(writer, "insert into raw_mvcc_u values (6, 'second-table')");
                    fail("The isolated format must reject a second RawStore-backed table before mutation");
                } catch (java.sql.SQLException expected) {
                    assertTrue(expected.toString(),
                            expected.toString().contains("multi-table"));
                }
                writer.rollback();
                assertRows(writer, "select id from raw_mvcc_t where id = 5");
                writer.commit();
                assertRows(writer, "select id from raw_mvcc_u where id = 6");
                writer.commit();

                try (Connection reader = openDatabase(database, false)) {
                    assertRows(reader,
                            "select id, name from raw_mvcc_t order by id",
                            "1|committed",
                            "3|after-rollback");
                }
                assertEquals(
                        "the RawStore format must not create Phase 8 MVCC sidecar files",
                        0L,
                        inheritedMvccStateFileCount(database));
            }

            shutdownDatabase(database);

            ignored.clear();
            try (Connection disabled = DriverManager.getConnection("jdbc:derby:" + database)) {
                try {
                    assertRows(disabled,
                            "select id, name from raw_mvcc_t where id = 1",
                            "1|committed");
                    fail("RawStore-backed table access must require the explicit opt-in");
                } catch (java.sql.SQLException expected) {
                    assertTrue(expected.toString(),
                            expected.toString().contains(ENABLED_PROPERTY));
                }
            }
            shutdownDatabase(database);
            ignored.set("true");

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, name from raw_mvcc_t where id = 1",
                        "1|committed");
                assertRows(reopened,
                        "select id, name from raw_mvcc_t where id = 2");
                assertRows(reopened,
                        "select id, name from raw_mvcc_t where id = 3",
                        "3|after-rollback");
            }
            shutdownDatabase(database);
        }
    }


    public void testOptedInBootKeepsEarlierFormatAsFallback() throws Exception {
        String database = databaseName("mvcc-raw-store-legacy-fallback");

        try (SystemPropertyScope ignored = clearSystemProperty(ENABLED_PROPERTY)) {
            try (Connection legacy = openDatabase(database, true)) {
                legacy.setAutoCommit(false);
                executeUpdate(legacy,
                        "create table legacy_mvcc_t (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(legacy, "insert into legacy_mvcc_t values (1, 'legacy')");
                legacy.commit();
            }
            shutdownDatabase(database);
        }
        assertTrue(
                "the retained oracle table must have earlier-format durable state",
                inheritedMvccStateFileCount(database) > 0L);

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, false)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table raw_after_legacy_t (id int, name varchar(64)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection,
                        "insert into raw_after_legacy_t values (3, 'raw-store')");
                connection.commit();

                assertRows(connection,
                        "select id, name from legacy_mvcc_t order by id",
                        "1|legacy");
                connection.commit();
                executeUpdate(connection, "insert into legacy_mvcc_t values (2, 'legacy-write')");
                connection.commit();

                assertRows(connection,
                        "select id, name from legacy_mvcc_t order by id",
                        "1|legacy",
                        "2|legacy-write");
                connection.commit();
                assertRows(connection,
                        "select id, name from raw_after_legacy_t order by id",
                        "3|raw-store");
                connection.commit();
            }
            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                assertRows(reopened,
                        "select id, name from legacy_mvcc_t order by id",
                        "1|legacy",
                        "2|legacy-write");
                assertRows(reopened,
                        "select id, name from raw_after_legacy_t order by id",
                        "3|raw-store");
            }
            shutdownDatabase(database);
        }
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

    public void testMemoryDatabaseUsesRawStoreAndLeavesNoFilesystemVolume() throws Exception {
        String database = "mvcc-raw-store-memory-" + System.nanoTime();
        String jdbcUrl = "jdbc:derby:memory:" + database;
        Path accidentalFilesystemDatabase = Path.of(database).toAbsolutePath().normalize();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection connection = DriverManager.getConnection(jdbcUrl + ";create=true")) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table memory_raw_mvcc (id int, name varchar(64)) using delos_mvcc");
                executeUpdate(connection, "insert into memory_raw_mvcc values (1, 'memory')");
                assertRows(connection,
                        "select id, name from memory_raw_mvcc where id = 1",
                        "1|memory");
                connection.commit();

                try (Connection reader = DriverManager.getConnection(jdbcUrl)) {
                    assertRows(reader,
                            "select id, name from memory_raw_mvcc where id = 1",
                            "1|memory");
                }

                executeUpdate(connection, "insert into memory_raw_mvcc values (2, 'rollback')");
                connection.rollback();
                assertRows(connection,
                        "select id, name from memory_raw_mvcc order by id",
                        "1|memory");

                executeUpdate(connection, "drop table memory_raw_mvcc");
                connection.commit();
                assertRows(connection,
                        "select count(*) from sys.systables where tablename = 'MEMORY_RAW_MVCC'",
                        "0");
                connection.commit();
            }
        } finally {
            try {
                DriverManager.getConnection(jdbcUrl + ";shutdown=true");
                fail("Memory database shutdown should throw the normal Derby shutdown exception");
            } catch (java.sql.SQLException expected) {
                assertEquals("08006", expected.getSQLState());
            }
        }
        assertFalse(
                "jdbc:derby:memory: RawStore MVCC must not create a filesystem database",
                Files.exists(accidentalFilesystemDatabase));
    }
}
