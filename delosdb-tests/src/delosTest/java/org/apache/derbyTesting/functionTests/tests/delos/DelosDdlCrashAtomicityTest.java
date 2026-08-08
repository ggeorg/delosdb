/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.DelosDdlCrashAtomicityTest

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */
package org.apache.derbyTesting.functionTests.tests.delos;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Stage 6 proof that supported DDL recovers wholly before or after RawStore commit. */
public final class DelosDdlCrashAtomicityTest extends MvccSqlTestSupport {
    private static final String ENABLED_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.enabled";
    private static final String FAILURE_POINT_PROPERTY =
            "delosdb.mvcc.rawStoreVerticalSlice.failurePoint";

    public void testSupportedDdlIsAtomicAcrossBothRawStoreCrashBoundaries() throws Exception {
        verifyBoundary("after-stamp-before-raw-commit", 91, false);
        verifyBoundary("after-raw-commit-before-publication", 92, true);
    }

    private static void verifyBoundary(
            String failurePoint, int expectedStatus, boolean expectCommitted) throws Exception {
        String database = Path.of("stage6-ma009-ddl-crash-" + expectedStatus + '-'
                + Long.toUnsignedString(System.nanoTime()))
                .toAbsolutePath().normalize().toString();
        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true")) {
            try (Connection setup = openDatabase(database, true)) {
                setup.setAutoCommit(false);
                executeUpdate(setup, "create table ddl_heap_t (id int primary key, value int)");
                executeUpdate(setup,
                        "create table ddl_mvcc_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(setup,
                        "create table ddl_witness_t (id int primary key, value int) using delos_mvcc");
                executeUpdate(setup, "insert into ddl_heap_t values (1, 10)");
                executeUpdate(setup, "insert into ddl_mvcc_t values (1, 10)");
                executeUpdate(setup, "insert into ddl_witness_t values (1, 10)");
                setup.commit();
            }
            shutdownDatabase(database);
        }

        Process process = new ProcessBuilder(
                javaExecutable(),
                "-D" + ENABLED_PROPERTY + "=true",
                "-D" + FAILURE_POINT_PROPERTY + '=' + failurePoint,
                "-cp", System.getProperty("java.class.path"),
                CrashWorker.class.getName(), database)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(Duration.ofSeconds(45).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("DDL crash worker did not terminate at " + failurePoint);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("unexpected crash status; output=" + output, expectedStatus, process.exitValue());

        try (SystemPropertyScope ignored = setSystemProperty(ENABLED_PROPERTY, "true");
             Connection recovered = openDatabase(database, false)) {
            recovered.setAutoCommit(false);
            assertCatalogCount(recovered, "select count(*) from sys.syscolumns c, sys.systables t "
                    + "where c.referenceid=t.tableid and t.tablename='DDL_HEAP_T' "
                    + "and c.columnname='MARKER'", expectCommitted ? 1 : 0);
            assertCatalogCount(recovered, "select count(*) from sys.sysconglomerates "
                    + "where conglomeratename='DDL_HEAP_VALUE_IDX' and isindex=true",
                    expectCommitted ? 1 : 0);
            assertCatalogCount(recovered, "select count(*) from sys.sysconglomerates "
                    + "where conglomeratename='DDL_MVCC_VALUE_IDX' and isindex=true",
                    expectCommitted ? 1 : 0);

            if (expectCommitted) {
                assertRows(recovered,
                        "select id, value, marker from ddl_heap_t "
                                + "--DERBY-PROPERTIES index=ddl_heap_value_idx\n"
                                + "where value=10",
                        "1|10|7");
                assertRows(recovered,
                        "select id, value from ddl_mvcc_t "
                                + "--DERBY-PROPERTIES index=ddl_mvcc_value_idx\n"
                                + "where value=10",
                        "1|10");
                assertRows(recovered, "select id, value from ddl_witness_t", "1|30");
            } else {
                assertRows(recovered, "select id, value from ddl_heap_t", "1|10");
                assertRows(recovered, "select id, value from ddl_mvcc_t", "1|10");
                assertRows(recovered, "select id, value from ddl_witness_t", "1|10");
            }
            recovered.commit();
        }
        shutdownDatabase(database);
    }

    private static void assertCatalogCount(Connection connection, String sql, int expected)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(expected, result.getInt(1));
            assertFalse(result.next());
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** Child JVM halts on one side of the RawStore commit carrying all DDL artifacts. */
    public static final class CrashWorker {
        private CrashWorker() {
        }

        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 1) {
                throw new IllegalArgumentException("Expected database path");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:derby:" + arguments[0])) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "alter table ddl_heap_t add column marker int not null default 7");
                executeUpdate(connection,
                        "create index ddl_heap_value_idx on ddl_heap_t(value)");
                executeUpdate(connection,
                        "create index ddl_mvcc_value_idx on ddl_mvcc_t(value)");
                executeUpdate(connection, "update ddl_witness_t set value=30 where id=1");
                connection.commit();
            }
            throw new AssertionError("Configured DDL crash point did not halt the child JVM");
        }
    }
}
