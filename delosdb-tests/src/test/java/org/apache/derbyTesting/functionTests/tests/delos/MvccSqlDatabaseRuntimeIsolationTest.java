/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlDatabaseRuntimeIsolationTest

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
import java.nio.file.Path;
import java.sql.Connection;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL proof that each open Derby database owns an independent MVCC runtime. */
public final class MvccSqlDatabaseRuntimeIsolationTest extends MvccSqlTestSupport {
    public void testAlternatingTwoDatabaseLifecycleKeepsMvccStateIsolated() throws Exception {
        String databaseA = databaseName("mvcc-runtime-isolation-a");
        String databaseB = databaseName("mvcc-runtime-isolation-b");
        Path rootA = new File(databaseA).toPath().toAbsolutePath().normalize();
        Path rootB = new File(databaseB).toPath().toAbsolutePath().normalize();
        DelosStorageDiagnostics diagnosticsA = mvccDiagnostics(databaseA);
        DelosStorageDiagnostics diagnosticsB = mvccDiagnostics(databaseB);

        long aFirstContainer;
        long aSecondContainer;
        long bFirstContainer;

        Connection connectionA = openDatabase(databaseA, true);
        Connection connectionB = null;
        try {
            connectionA.setAutoCommit(false);
            executeUpdate(connectionA,
                    "create table runtime_a_first (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionA, "insert into runtime_a_first values (1, 'a-first')");
            connectionA.commit();
            aFirstContainer = mvccContainerId(connectionA, "RUNTIME_A_FIRST");
            connectionA.rollback();

            connectionB = openDatabase(databaseB, true);
            connectionB.setAutoCommit(false);
            executeUpdate(connectionB,
                    "create table runtime_b_first (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionB, "insert into runtime_b_first values (1, 'b-first')");
            connectionB.commit();
            bFirstContainer = mvccContainerId(connectionB, "RUNTIME_B_FIRST");
            connectionB.rollback();

            // This creation happens after database B has booted. It is the
            // sequence which previously resolved through the mutable global
            // database directory and could attach A's table to B's store.
            executeUpdate(connectionA,
                    "create table runtime_a_second (id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connectionA, "insert into runtime_a_second values (2, 'a-second')");
            connectionA.commit();
            aSecondContainer = mvccContainerId(connectionA, "RUNTIME_A_SECOND");
            connectionA.rollback();

            assertRows(connectionA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(connectionA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            assertRows(connectionB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first");

            assertStateFileOwnedBy(rootA,
                    diagnosticsA.pageVolumeStateFileForTesting(0, aFirstContainer));
            assertStateFileOwnedBy(rootA,
                    diagnosticsA.pageVolumeStateFileForTesting(0, aSecondContainer));
            assertStateFileOwnedBy(rootB,
                    diagnosticsB.pageVolumeStateFileForTesting(0, bFirstContainer));
            assertEquals(2, diagnosticsA.runtimeStateCountForTesting());
            assertEquals(1, diagnosticsB.runtimeStateCountForTesting());

            connectionA.close();
            connectionA = null;
            shutdownDatabase(databaseA);
            assertFalse("database A runtime must be released by clean shutdown",
                    diagnosticsA.runtimeActiveForTesting());
            assertEquals(0, diagnosticsA.runtimeStateCountForTesting());

            // Shutting down A must not close B's store, maintenance service,
            // backup coordinator, table state, or diagnostics context.
            executeUpdate(connectionB, "insert into runtime_b_first values (2, 'b-after-a-shutdown')");
            connectionB.commit();
            assertRows(connectionB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first",
                    "2|b-after-a-shutdown");
            assertEquals(1, diagnosticsB.runtimeStateCountForTesting());
            assertStateFileOwnedBy(rootB,
                    diagnosticsB.pageVolumeStateFileForTesting(0, bFirstContainer));

            connectionA = openDatabase(databaseA, false);
            assertTrue("reopen must activate database A's persisted MVCC runtime before diagnostics",
                    diagnosticsA.runtimeActiveForTesting());
            assertEquals("reopen must attach both persisted database A table states",
                    2, diagnosticsA.runtimeStateCountForTesting());
            connectionA.setAutoCommit(false);
            assertRows(connectionA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(connectionA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            long reopenedAFirst = mvccContainerId(connectionA, "RUNTIME_A_FIRST");
            connectionA.rollback();
            assertEquals(aFirstContainer, reopenedAFirst);
            assertStateFileOwnedBy(rootA,
                    diagnosticsA.pageVolumeStateFileForTesting(0, reopenedAFirst));
            assertEquals(2, diagnosticsA.runtimeStateCountForTesting());
            assertEquals(1, diagnosticsB.runtimeStateCountForTesting());
        } finally {
            if (connectionA != null) {
                connectionA.close();
            }
            if (connectionB != null) {
                connectionB.close();
            }
        }

        shutdownDatabase(databaseA);
        shutdownDatabase(databaseB);

        try (Connection reopenedA = openDatabase(databaseA, false);
                Connection reopenedB = openDatabase(databaseB, false)) {
            assertTrue(diagnosticsA.runtimeActiveForTesting());
            assertTrue(diagnosticsB.runtimeActiveForTesting());
            assertEquals(2, diagnosticsA.runtimeStateCountForTesting());
            assertEquals(1, diagnosticsB.runtimeStateCountForTesting());
            assertRows(reopenedA,
                    "select id, name from runtime_a_first order by id",
                    "1|a-first");
            assertRows(reopenedA,
                    "select id, name from runtime_a_second order by id",
                    "2|a-second");
            assertRows(reopenedB,
                    "select id, name from runtime_b_first order by id",
                    "1|b-first",
                    "2|b-after-a-shutdown");
        }

        shutdownDatabase(databaseA);
        shutdownDatabase(databaseB);
        assertFalse(diagnosticsA.runtimeActiveForTesting());
        assertFalse(diagnosticsB.runtimeActiveForTesting());
        assertEquals(0, diagnosticsA.runtimeStateCountForTesting());
        assertEquals(0, diagnosticsB.runtimeStateCountForTesting());
    }

    private static void assertStateFileOwnedBy(Path databaseRoot, Path stateFile) {
        Path normalizedStateFile = stateFile.toAbsolutePath().normalize();
        assertTrue(
                "expected state file " + normalizedStateFile + " under " + databaseRoot,
                normalizedStateFile.startsWith(databaseRoot));
    }
}
