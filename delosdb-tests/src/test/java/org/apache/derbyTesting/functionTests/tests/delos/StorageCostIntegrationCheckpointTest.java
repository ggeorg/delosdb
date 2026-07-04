/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageCostIntegrationCheckpointTest

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
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageCostEstimate;
import org.apache.derby.iapi.store.types.DelosStorageCostIntegration;
import org.apache.derby.iapi.store.types.DelosStorageCostReport;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageStatistics;

/** SQL gate for the opt-in storage-statistics cost checkpoint. */
public final class StorageCostIntegrationCheckpointTest extends MvccSqlTestSupport {
    public void testStorageCostIntegrationIsOptInAndProofOnly() throws Exception {
        String databaseName = databaseName("storage-cost-integration-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (SystemPropertyScope costIntegrationProperty = clearSystemProperty(
                DelosStorageCostIntegration.ENABLED_PROPERTY)) {
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table cost_heap_t "
                        + "(id int primary key, name varchar(32), payload varchar(128))");
                executeUpdate(connection, "create table cost_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
                executeUpdate(connection, "insert into cost_heap_t values (1, 'alpha', 'heap-one')");
                executeUpdate(connection, "insert into cost_heap_t values (2, 'beta', 'heap-two')");
                executeUpdate(connection, "insert into cost_mvcc_t values (1, 'alpha', 'mvcc-one')");
                executeUpdate(connection, "insert into cost_mvcc_t values (2, 'beta', 'mvcc-two')");
                executeUpdate(connection, "insert into cost_mvcc_t values (3, 'gamma', 'mvcc-three')");
                connection.commit();

                long heapContainerId = baseContainerId(connection, "COST_HEAP_T", "heap");
                long mvccContainerId = mvccContainerId(connection, "COST_MVCC_T");

                DelosStorageCostReport disabledReport = DelosStorageDiagnosticsRegistry.costReport(
                        DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId),
                        DelosStorageConsistencyTarget.mvcc(0, mvccContainerId));
                assertFalse("storage cost integration should be disabled by default",
                        disabledReport.storageStatisticsEnabled());
                assertTrue("cost report must be read-only", disabledReport.readOnly());
                assertTrue("default report must remain proof-only", disabledReport.proofOnly());
                assertFalse("default report must not be consumed by Derby optimizer",
                        disabledReport.consumedByDerbyOptimizer());

                costIntegrationProperty.set("true");
                DelosStorageCostReport enabledReport = DelosStorageDiagnosticsRegistry.costReport(
                        DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId),
                        DelosStorageConsistencyTarget.mvcc(0, mvccContainerId));
                assertTrue("explicit property should enable storage cost diagnostics",
                        enabledReport.storageStatisticsEnabled());
                assertTrue("enabled report remains read-only", enabledReport.readOnly());
                assertTrue("enabled checkpoint remains proof-only", enabledReport.proofOnly());
                assertFalse("enabled checkpoint must not change Derby optimizer consumption",
                        enabledReport.consumedByDerbyOptimizer());
                assertFalse("proof-only storage statistics must fail closed for optimizer consumption",
                        enabledReport.optimizerConsumptionEligible());
                assertTrue("proof-only storage statistics report should document fail-closed optimizer state",
                        enabledReport.failClosedForOptimizer());
                assertEquals("expected heap and MVCC cost targets", 2, enabledReport.targetCount());
                assertTrue("expected positive aggregate full-scan cost",
                        enabledReport.totalEstimatedFullScanCost() > 0L);
                assertTrue("expected heap provider in cost report",
                        enabledReport.providerIds().contains(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
                assertTrue("expected MVCC provider in cost report",
                        enabledReport.providerIds().contains(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));

                DelosStorageCostEstimate heapEstimate = enabledReport.estimate(
                        " DERBY_HEAP ", 0, heapContainerId);
                assertEquals("expected normalized heap provider id",
                        DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                        heapEstimate.providerId());
                assertTrue("expected heap page/storage-derived full scan cost",
                        heapEstimate.estimatedFullScanCost() > 0L);
                assertFalse("heap estimate must not be consumed by optimizer",
                        heapEstimate.consumedByDerbyOptimizer());
                assertEquals("heap estimate remains proof-only", "proof-only", heapEstimate.decision());

                DelosStorageCostEstimate mvccEstimate = enabledReport.estimate(
                        DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, 0, mvccContainerId);
                assertEquals("expected three MVCC logical rows", 3L, mvccEstimate.logicalRowCount());
                assertTrue("expected MVCC page/storage-derived full scan cost",
                        mvccEstimate.estimatedFullScanCost() > 0L);
                assertFalse("MVCC estimate must not be consumed by optimizer",
                        mvccEstimate.consumedByDerbyOptimizer());
                assertTrue("MVCC estimate should expose ordered-index facts to cost diagnostics",
                        mvccEstimate.observations().stream()
                                .anyMatch(value -> value.startsWith("ordered index entries:")));
                assertTrue("MVCC estimate should remain fail-closed for optimizer consumption",
                        mvccEstimate.failClosedForOptimizer());

                assertRows(connection,
                        "select id, name from cost_heap_t order by id",
                        "1|alpha",
                        "2|beta");
                assertRows(connection,
                        "select id, name from cost_mvcc_t order by id",
                        "1|alpha",
                        "2|beta",
                        "3|gamma");
                connection.commit();
            }
        }
    }

    public void testStorageCostEstimatesSaturateAndRemainFailClosedForExtremeStatistics() {
        DelosStorageStatistics extreme = new DelosStorageStatistics(
                DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                0,
                42L,
                true,
                Long.MAX_VALUE - 10L,
                Long.MAX_VALUE - 5L,
                Long.MAX_VALUE - 4L,
                Long.MAX_VALUE - 3L,
                0L,
                Long.MAX_VALUE - 2L,
                Long.MAX_VALUE - 1L,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                0L,
                0L,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                Long.MAX_VALUE,
                List.of("synthetic extreme statistics"));

        DelosStorageCostEstimate estimate = DelosStorageCostEstimate.fromStatistics(extreme, true);

        assertEquals("extreme storage statistics should saturate instead of overflowing",
                Long.MAX_VALUE,
                estimate.estimatedFullScanCost());
        assertTrue("saturated estimate should keep a positive row fetch cost",
                estimate.estimatedRowFetchCost() > 0L);
        assertTrue("saturated estimate should keep an ordered-index lookup estimate",
                estimate.hasIndexLookupCost());
        assertTrue("storage-statistics checkpoint remains proof-only",
                estimate.proofOnly());
        assertFalse("proof-only estimate must not be optimizer-consumption eligible",
                estimate.optimizerConsumptionEligible());
        assertTrue("proof-only estimate must fail closed for optimizer consumption",
                estimate.failClosedForOptimizer());
        assertTrue("estimate should document fail-closed optimizer state",
                estimate.observations().contains(
                        "optimizer consumption eligibility: fail-closed proof-only checkpoint"));
    }

}
