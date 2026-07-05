/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.OptimizerStorageCostOptInTest

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

import org.apache.derby.iapi.sql.compile.DelosOptimizerStorageCostOptInDiagnostics;

import java.sql.Connection;

/** SQL gate for the explicit Derby optimizer storage-cost opt-in path. */
public final class OptimizerStorageCostOptInTest extends MvccSqlTestSupport {
    public void testOptimizerStorageCostConsumptionIsExplicitlyOptIn() throws Exception {
        String databaseName = databaseName("optimizer-storage-cost-optin-db");
        String oldMode = System.getProperty(DelosOptimizerStorageCostOptInDiagnostics.PROPERTY_NAME);

        try {
            System.clearProperty(DelosOptimizerStorageCostOptInDiagnostics.PROPERTY_NAME);
            DelosOptimizerStorageCostOptInDiagnostics.clearForTesting();

            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table optimizer_cost_heap_t "
                        + "(id int primary key, name varchar(32), payload varchar(64))");
                executeUpdate(connection, "create index optimizer_cost_heap_name_idx "
                        + "on optimizer_cost_heap_t(name)");
                executeUpdate(connection, "insert into optimizer_cost_heap_t values (1, 'alpha', 'one')");
                executeUpdate(connection, "insert into optimizer_cost_heap_t values (2, 'beta', 'two')");
                executeUpdate(connection, "insert into optimizer_cost_heap_t values (3, 'gamma', 'three')");
                connection.commit();

                assertFalse("optimizer cost provider must be disabled by default",
                        DelosOptimizerStorageCostOptInDiagnostics.optimizerCostProviderEnabledForTesting());
                assertRows(connection,
                        "select id, name from optimizer_cost_heap_t where name >= 'alpha' order by name",
                        "1|alpha",
                        "2|beta",
                        "3|gamma");
                assertEquals("default Derby optimizer path must not record provider probes",
                        0,
                        DelosOptimizerStorageCostOptInDiagnostics.probeCountForTesting());
                connection.commit();
            }

            /*
             * The compiler context caches StoreCostController instances by
             * conglomerate id.  Enable the opt-in path before opening the
             * statement-compile connection used by the proof, so the native
             * store-cost controller is wrapped when it is first opened.
             */
            System.setProperty(DelosOptimizerStorageCostOptInDiagnostics.PROPERTY_NAME, "enabled");
            DelosOptimizerStorageCostOptInDiagnostics.clearForTesting();
            assertTrue("explicit opt-in property should enable provider cost consumption",
                    DelosOptimizerStorageCostOptInDiagnostics.optimizerCostProviderEnabledForTesting());

            try (Connection connection = openDatabase(databaseName, false)) {
                connection.setAutoCommit(false);
                assertRows(connection,
                        "select id, name from optimizer_cost_heap_t where name >= 'beta' order by name",
                        "2|beta",
                        "3|gamma");

                assertTrue("enabled optimizer path should record store-cost provider probes",
                        DelosOptimizerStorageCostOptInDiagnostics.probeCountForTesting() > 0);
                assertTrue("enabled optimizer path should observe safe Derby baseline costs",
                        DelosOptimizerStorageCostOptInDiagnostics.safeDerbyBaselineProbeCountForTesting() > 0);
                assertTrue("enabled optimizer path should find safe provider estimates",
                        DelosOptimizerStorageCostOptInDiagnostics.safeProviderEstimateCountForTesting() > 0);
                assertTrue("enabled optimizer path should consume at least one provider estimate",
                        DelosOptimizerStorageCostOptInDiagnostics.consumedProbeCountForTesting() > 0);
                assertTrue("last probe should document the native store-cost controller path",
                        DelosOptimizerStorageCostOptInDiagnostics.lastDiagnosticLineForTesting()
                                .contains("path=store-cost-controller"));
                assertTrue("last probe should document a safe Derby baseline before consumption",
                        DelosOptimizerStorageCostOptInDiagnostics.lastDiagnosticLineForTesting()
                                .contains("safeDerbyBaseline=true"));
                assertTrue("last probe should document opt-in consumption",
                        DelosOptimizerStorageCostOptInDiagnostics.lastDiagnosticLineForTesting()
                                .contains("consumed=true"));
                assertRows(connection,
                        "select id, name from optimizer_cost_heap_t order by id",
                        "1|alpha",
                        "2|beta",
                        "3|gamma");
                connection.commit();
            }
        } finally {
            DelosOptimizerStorageCostOptInDiagnostics.clearForTesting();
            if (oldMode == null) {
                System.clearProperty(DelosOptimizerStorageCostOptInDiagnostics.PROPERTY_NAME);
            } else {
                System.setProperty(DelosOptimizerStorageCostOptInDiagnostics.PROPERTY_NAME, oldMode);
            }
        }
    }
}
