/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.RawStoreConcurrentMutationPreparedInsertTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements. See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to You under the Apache License, Version 2.0.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.derby.impl.store.raw.data.RawStorePreparedInsertTestSupport;

/** RCM-0/1 equivalence, transaction, and recovery proof for prepared inserts. */
public final class RawStoreConcurrentMutationPreparedInsertTest extends MvccSqlTestSupport {
    private static final String PREPARED_PROPERTY =
            "delosdb.experimental.rawStorePreparedInsert.enabled";
    private static final String DIAGNOSTIC_PROPERTY =
            "delosdb.diagnostic.rawStorePreparedInsert";
    private static final int ROWS = 1200;

    public void testPreparedWalPayloadConvertsToCanonicalPageRecord() throws Exception {
        assertEquals(128, RawStorePreparedInsertTestSupport.verifyCodec());
    }

    public void testPreparedInsertCommitRollbackDuplicateSplitAndReopen() throws Exception {
        String database = Path.of("rcm-prepared-insert-lifecycle")
                .toAbsolutePath().normalize().toString();
        try (SystemPropertyScope prepared = setSystemProperty(PREPARED_PROPERTY, "true");
             SystemPropertyScope diagnostics = setSystemProperty(DIAGNOSTIC_PROPERTY, "true")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table RCM_PREPARED ("
                                + "id int not null primary key, "
                                + "payload varchar(256) not null)");
                connection.commit();

                RawStorePreparedInsertTestSupport.reset();
                insertRows(connection, 1, ROWS);
                connection.commit();
                assertEquals(ROWS, countRows(connection));

                insertRows(connection, ROWS + 1, 40);
                connection.rollback();
                assertEquals(ROWS, countRows(connection));

                connection.setAutoCommit(false);
                assertDuplicateKey(() -> executeUpdate(connection,
                        "insert into RCM_PREPARED values (1, 'duplicate')"));
                connection.rollback();

                long[] counters = RawStorePreparedInsertTestSupport.snapshot();
                assertTrue("writer-local row preparation was not exercised",
                        counters[0] >= ROWS * 2L);
                assertEquals("simple Heap/PK rows unexpectedly used row fallback",
                        0L, counters[1]);
                assertTrue("prepared insert records were not produced", counters[2] > 0L);
                assertEquals("each prepared insert must use direct runtime apply",
                        counters[2], counters[3]);
                assertEquals("prepared mode unexpectedly fell back to legacy apply",
                        0L, counters[4]);
                assertTrue("prepared page bytes were not accounted", counters[5] > 0L);
            }
            shutdownDatabase(database);

            prepared.set("false");
            diagnostics.set("false");
            try (Connection reopened = openDatabase(database, false)) {
                assertEquals(ROWS, countRows(reopened));
                assertRows(reopened,
                        "select id, payload from RCM_PREPARED where id in (1, 600, 1200) order by id",
                        "1|payload-1",
                        "600|payload-600",
                        "1200|payload-1200");
            } finally {
                shutdownDatabase(database);
            }
        }
    }

    public void testCommittedPreparedInsertRecoversThroughLegacyWalDecoder() throws Exception {
        String database = Path.of("rcm-prepared-insert-crash")
                .toAbsolutePath().normalize().toString();
        try (Connection setup = openDatabase(database, true)) {
            setup.setAutoCommit(false);
            executeUpdate(setup,
                    "create table RCM_CRASH ("
                            + "id int not null primary key, "
                            + "payload varchar(256) not null)");
            setup.commit();
        }
        shutdownDatabase(database);

        Process worker = new ProcessBuilder(
                javaExecutable(),
                "-D" + PREPARED_PROPERTY + "=true",
                "-D" + DIAGNOSTIC_PROPERTY + "=true",
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                database)
                .redirectErrorStream(true)
                .start();
        boolean finished = worker.waitFor(
                Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            worker.destroyForcibly();
            fail("RCM prepared-insert crash worker did not terminate");
        }
        String output = new String(worker.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("worker must halt after durable commit; output=" + output,
                77, worker.exitValue());

        try (SystemPropertyScope prepared = setSystemProperty(PREPARED_PROPERTY, "false");
             Connection recovered = openDatabase(database, false)) {
            assertEquals(600, countRows(recovered, "RCM_CRASH"));
            assertRows(recovered,
                    "select id, payload from RCM_CRASH where id in (1, 300, 600) order by id",
                    "1|payload-1",
                    "300|payload-300",
                    "600|payload-600");
        } finally {
            shutdownDatabase(database);
        }
    }

    private static void insertRows(Connection connection, int firstId, int count) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into RCM_PREPARED values (?, ?)")) {
            for (int id = firstId; id < firstId + count; id++) {
                statement.setInt(1, id);
                statement.setString(2, "payload-" + id);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static int countRows(Connection connection) throws Exception {
        return countRows(connection, "RCM_PREPARED");
    }

    private static int countRows(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
            assertTrue(rs.next());
            int count = rs.getInt(1);
            assertFalse(rs.next());
            return count;
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                System.exit(70);
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + args[0])) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "insert into RCM_CRASH values (?, ?)")) {
                    for (int id = 1; id <= 600; id++) {
                        statement.setInt(1, id);
                        statement.setString(2, "payload-" + id);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
                Runtime.getRuntime().halt(77);
            }
            System.exit(78);
        }
    }
}
