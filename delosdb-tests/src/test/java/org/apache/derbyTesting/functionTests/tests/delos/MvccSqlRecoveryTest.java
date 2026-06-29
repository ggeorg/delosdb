/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlRecoveryTest

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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** SQL integration tests for delos_mvcc recovery behavior. */
public final class MvccSqlRecoveryTest extends MvccSqlTestSupport {
    public void testCommittedMvccInsertSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-commit-db");

        runCrashBoundaryWorker("commit-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_commit_t order by id",
                    "1|committed-before-halt");
        }
    }


    public void testUncommittedMvccInsertDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mvcc-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from mvcc_crash_uncommitted_t order by id");
        }
    }


    public void testCommittedHeapAndMvccTransactionSurvivesProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-commit-db");

        runCrashBoundaryWorker("commit-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_commit_t order by id",
                    "1|heap-committed");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_commit_t order by id",
                    "1|mvcc-committed");
        }
    }


    public void testUncommittedHeapAndMvccTransactionDoesNotSurviveProcessHaltAndRecovery() throws Exception {
        String databaseName = databaseName("mvcc-sql-crash-mixed-uncommitted-db");

        runCrashBoundaryWorker("uncommitted-mixed-insert", databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_crash_uncommitted_t order by id");
            assertRows(reopened,
                    "select id, name from mvcc_crash_mixed_uncommitted_t order by id");
        }
    }


    private static void runCrashBoundaryWorker(String scenario, String databaseName) throws Exception {
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("-cp");
        command.add(classpath);
        command.add(CrashBoundaryWorker.class.getName());
        command.add(scenario);
        command.add(databaseName);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        assertEquals("Crash-boundary worker failed. Output:\n" + output, 0, exitCode);
    }

    public static final class CrashBoundaryWorker {
        public static void main(String[] args) {
            try {
                if (args.length != 2) {
                    throw new IllegalArgumentException("expected scenario and database name");
                }
                runScenario(args[0], args[1]);
                Runtime.getRuntime().halt(0);
            } catch (Throwable t) {
                t.printStackTrace(System.err);
                Runtime.getRuntime().halt(2);
            }
        }

        private static void runScenario(String scenario, String databaseName) throws Exception {
            switch (scenario) {
            case "commit-mvcc-insert":
                commitMvccInsert(databaseName);
                break;
            case "uncommitted-mvcc-insert":
                uncommittedMvccInsert(databaseName);
                break;
            case "commit-mixed-insert":
                commitMixedInsert(databaseName);
                break;
            case "uncommitted-mixed-insert":
                uncommittedMixedInsert(databaseName);
                break;
            default:
                throw new IllegalArgumentException("unknown crash-boundary scenario: " + scenario);
            }
        }

        private static void commitMvccInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_crash_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into mvcc_crash_commit_t values (1, 'committed-before-halt')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }

        private static void uncommittedMvccInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_crash_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into mvcc_crash_uncommitted_t values (1, 'uncommitted-before-halt')");
                Runtime.getRuntime().halt(0);
            }
        }

        private static void commitMixedInsert(String databaseName) throws Exception {
            Connection connection = openDatabase(databaseName, true);
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_crash_commit_t (id int primary key, name varchar(32))");
            executeUpdate(connection, "create table mvcc_crash_mixed_commit_t (id int primary key, name varchar(32)) using delos_mvcc");
            connection.commit();
            executeUpdate(connection, "insert into heap_crash_commit_t values (1, 'heap-committed')");
            executeUpdate(connection, "insert into mvcc_crash_mixed_commit_t values (1, 'mvcc-committed')");
            connection.commit();
            Runtime.getRuntime().halt(0);
        }

        private static void uncommittedMixedInsert(String databaseName) throws Exception {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table heap_crash_uncommitted_t (id int primary key, name varchar(32))");
                executeUpdate(connection, "create table mvcc_crash_mixed_uncommitted_t (id int primary key, name varchar(32)) using delos_mvcc");
                connection.commit();
                executeUpdate(connection, "insert into heap_crash_uncommitted_t values (1, 'heap-uncommitted')");
                executeUpdate(connection, "insert into mvcc_crash_mixed_uncommitted_t values (1, 'mvcc-uncommitted')");
                Runtime.getRuntime().halt(0);
            }
        }
    }

}
