/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccStatisticsOptimizerCostIntegrationTest

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

import org.apache.derby.iapi.store.types.DelosMvccOptimizerCostDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for Phase K MVCC statistics through Derby's StoreCostController seam. */
public final class MvccStatisticsOptimizerCostIntegrationTest extends MvccSqlTestSupport {
    public void testMvccStatisticsFeedDerbyStoreCostControllerOnlyWhenOptedIn() throws Exception {
        String databaseName = databaseName("mvcc-statistics-optimizer-cost-db");

        try (SystemPropertyScope ignored = clearSystemProperty(DelosMvccOptimizerCostDiagnostics.PROPERTY_NAME)) {
            DelosMvccOptimizerCostDiagnostics.clearForTesting();
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table mvcc_cost_t "
                        + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
                executeUpdate(connection, "insert into mvcc_cost_t values (1, 'alpha', 'one')");
                executeUpdate(connection, "insert into mvcc_cost_t values (2, 'beta', 'two')");
                executeUpdate(connection, "insert into mvcc_cost_t values (3, 'gamma', 'three')");
                executeUpdate(connection, "update mvcc_cost_t set payload = 'two-updated' where id = 2");
                connection.commit();

                long containerId = mvccContainerId(connection, "MVCC_COST_T");
                assertEquals("expected visible logical rows before optimizer-cost opt-in",
                        3L,
                        DelosStorageDiagnosticsRegistry.statisticsForMvcc(0, containerId).logicalRowCount());

                assertRows(connection,
                        "select id, name from mvcc_cost_t where id >= 1 order by id",
                        "1|alpha",
                        "2|beta",
                        "3|gamma");
                assertEquals("default MVCC optimizer path must not consume storage-statistics estimates",
                        0L,
                        DelosMvccOptimizerCostDiagnostics.statisticsEstimateCountForTesting());
                connection.commit();
            }
        }

        try (SystemPropertyScope ignored = setSystemProperty(
                DelosMvccOptimizerCostDiagnostics.PROPERTY_NAME,
                "true")) {
            DelosMvccOptimizerCostDiagnostics.clearForTesting();
            assertTrue("explicit Phase K property should enable MVCC statistics costing",
                    DelosMvccOptimizerCostDiagnostics.enabled());

            try (Connection connection = openDatabase(databaseName, false)) {
                connection.setAutoCommit(false);
                assertRows(connection,
                        "select id, payload from mvcc_cost_t where id >= 2 order by id",
                        "2|two-updated",
                        "3|three");

                assertTrue("enabled MVCC StoreCostController path should record statistics-derived estimates",
                        DelosMvccOptimizerCostDiagnostics.statisticsEstimateCountForTesting() > 0L);
                assertEquals("statistics source should be the MVCC storage provider",
                        DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                        DelosMvccOptimizerCostDiagnostics.lastProviderIdForTesting());
                assertEquals("MVCC logical row count should feed Derby cost estimates",
                        3L,
                        DelosMvccOptimizerCostDiagnostics.lastLogicalRowCountForTesting());
                assertTrue("MVCC physical version count should be available to optimizer costing",
                        DelosMvccOptimizerCostDiagnostics.lastPhysicalVersionCountForTesting() >= 3L);
                assertTrue("statistics-derived optimizer cost should remain positive",
                        DelosMvccOptimizerCostDiagnostics.lastEstimatedCostForTesting() > 0.0d);
                assertEquals("estimated rows should come from MVCC logical rows",
                        3L,
                        DelosMvccOptimizerCostDiagnostics.lastEstimatedRowsForTesting());
                assertTrue("diagnostic summary should prove the StoreCostController seam",
                        DelosMvccOptimizerCostDiagnostics.lastSummaryForTesting()
                                .contains("path=store-cost-controller"));
                assertTrue("diagnostic summary should prove storage-statistics source",
                        DelosMvccOptimizerCostDiagnostics.lastSummaryForTesting()
                                .contains("source=mvcc-storage-statistics"));
                connection.commit();
            }
        } finally {
            DelosMvccOptimizerCostDiagnostics.clearForTesting();
        }
    }
}
