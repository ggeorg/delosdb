/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccSqlBloatMeasurementTest

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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;

/** SQL integration tests for deterministic delos_mvcc bloat diagnostics. */
public final class MvccSqlBloatMeasurementTest extends MvccSqlTestSupport {
    public void testRepeatedIndexedUpdatesExposeBloatAndVacuumCollapsesIt() throws Exception {
        String databaseName = databaseName("mvcc-sql-bloat-measurement-db");
        DelosStorageDiagnostics diagnostics = mvccDiagnostics();
        long containerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_bloat_t "
                    + "(id int primary key, category varchar(16), payload varchar(32)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_bloat_category_idx on mvcc_bloat_t(category)");
            for (int id = 1; id <= 10; id++) {
                String category = id % 2 == 0 ? "even-r0" : "odd-r0";
                executeUpdate(connection, "insert into mvcc_bloat_t values ("
                        + id + ", '" + category + "', 'payload-0-" + id + "')");
            }
            connection.commit();

            containerId = mvccContainerId(connection, "MVCC_BLOAT_T");
            diagnostics.assertConsistentForTesting(0, containerId);
            assertEquals("initial workload should have ten visible SQL rows",
                    10, countRows(connection, "mvcc_bloat_t"));
            assertEquals("freshly inserted table should have one physical version per row",
                    10, diagnostics.physicalVersionCountForTesting(0, containerId));

            for (int round = 1; round <= 4; round++) {
                for (int id = 1; id <= 10; id++) {
                    String category = id % 2 == 0 ? "even-r" + round : "odd-r" + round;
                    executeUpdate(connection, "update mvcc_bloat_t set category = '"
                            + category + "', payload = 'payload-" + round + '-' + id + "' where id = " + id);
                }
                connection.commit();
            }
            assertEquals(2, executeUpdate(connection, "delete from mvcc_bloat_t where id in (9, 10)"));
            connection.commit();

            assertRows(connection,
                    "select id, payload from mvcc_bloat_t --DERBY-PROPERTIES index=mvcc_bloat_category_idx\n "
                            + "where category = 'odd-r4' order by id",
                    "1|payload-4-1",
                    "3|payload-4-3",
                    "5|payload-4-5",
                    "7|payload-4-7");

            int liveRowsBeforeVacuum = countRows(connection, "mvcc_bloat_t");
            int versionsBeforeVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            int obsoleteVersionEstimate = versionsBeforeVacuum - liveRowsBeforeVacuum;
            assertEquals("delete should leave eight visible SQL rows before vacuum",
                    8, liveRowsBeforeVacuum);
            assertTrue("expected repeated updates/deletes to create measurable MVCC bloat, physical="
                            + versionsBeforeVacuum + ", live=" + liveRowsBeforeVacuum,
                    obsoleteVersionEstimate >= 40);
            diagnostics.assertConsistentForTesting(0, containerId);

            inPlaceCompressTable(connection, "MVCC_BLOAT_T");
            connection.commit();

            int versionsAfterVacuum = diagnostics.physicalVersionCountForTesting(0, containerId);
            assertFalse("vacuum should run when the bloat measurement workload has no retained snapshot",
                    diagnostics.lastVacuumSkippedForTesting(0, containerId));
            assertEquals("vacuum should report the same remaining version count exposed by diagnostics",
                    versionsAfterVacuum, diagnostics.lastVacuumRemainingVersionsForTesting(0, containerId));
            assertEquals("vacuum should collapse physical versions to the visible row count",
                    liveRowsBeforeVacuum, versionsAfterVacuum);
            assertEquals("vacuum removed-version count should match the measured physical reduction",
                    versionsBeforeVacuum - versionsAfterVacuum,
                    diagnostics.lastVacuumRemovedVersionsForTesting(0, containerId));
            diagnostics.assertConsistentForTesting(0, containerId);

            assertRows(connection,
                    "select id, payload from mvcc_bloat_t --DERBY-PROPERTIES index=mvcc_bloat_category_idx\n "
                            + "where category = 'odd-r4' order by id",
                    "1|payload-4-1",
                    "3|payload-4-3",
                    "5|payload-4-5",
                    "7|payload-4-7");
            connection.rollback();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            long reopenedContainerId = mvccContainerId(reopened, "MVCC_BLOAT_T");
            assertEquals("reopened table should retain the vacuum-collapsed physical image",
                    8, diagnostics.physicalVersionCountForTesting(0, reopenedContainerId));
            diagnostics.assertConsistentForTesting(0, reopenedContainerId);
            assertRows(reopened,
                    "select id, category, payload from mvcc_bloat_t order by id",
                    "1|odd-r4|payload-4-1",
                    "2|even-r4|payload-4-2",
                    "3|odd-r4|payload-4-3",
                    "4|even-r4|payload-4-4",
                    "5|odd-r4|payload-4-5",
                    "6|even-r4|payload-4-6",
                    "7|odd-r4|payload-4-7",
                    "8|even-r4|payload-4-8");
        }
    }

    private static int countRows(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select count(*) from " + tableName)) {
            assertTrue("expected count row for " + tableName, rs.next());
            int count = rs.getInt(1);
            assertFalse("expected one count row for " + tableName, rs.next());
            return count;
        }
    }
}
