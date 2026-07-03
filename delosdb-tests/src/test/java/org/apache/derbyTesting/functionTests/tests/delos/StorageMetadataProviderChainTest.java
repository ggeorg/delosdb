/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageMetadataProviderChainTest

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
import org.apache.derby.iapi.store.types.DelosStorageCostIntegration;
import org.apache.derby.iapi.store.types.DelosStorageCostReport;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageMetadataQuery;
import org.apache.derby.iapi.store.types.DelosStorageMetadataSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageStatisticsReport;

/** SQL gate for the DelosDB storage metadata provider chain. */
public final class StorageMetadataProviderChainTest extends MvccSqlTestSupport {
    public void testMetadataProviderChainFeedsReportsWithoutOptimizerConsumption() throws Exception {
        String databaseName = databaseName("storage-metadata-provider-chain-db");
        Path databaseDirectory = new File(databaseName).toPath();
        String oldProperty = System.getProperty(DelosStorageCostIntegration.ENABLED_PROPERTY);

        try {
            System.setProperty(DelosStorageCostIntegration.ENABLED_PROPERTY, "true");
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table metadata_heap_t "
                        + "(id int primary key, name varchar(32), payload varchar(128))");
                executeUpdate(connection, "create table metadata_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
                executeUpdate(connection, "insert into metadata_heap_t values (1, 'heap-alpha', 'heap-one')");
                executeUpdate(connection, "insert into metadata_heap_t values (2, 'heap-beta', 'heap-two')");
                executeUpdate(connection, "insert into metadata_mvcc_t values (1, 'mvcc-alpha', 'mvcc-one')");
                executeUpdate(connection, "insert into metadata_mvcc_t values (2, 'mvcc-beta', 'mvcc-two')");
                executeUpdate(connection, "insert into metadata_mvcc_t values (3, 'mvcc-gamma', 'mvcc-three')");
                connection.commit();

                long heapContainerId = baseContainerId(connection, "METADATA_HEAP_T", "heap");
                long mvccContainerId = mvccContainerId(connection, "METADATA_MVCC_T");
                DelosStorageConsistencyTarget heapTarget = new DelosStorageConsistencyTarget(
                        " DERBY_HEAP ", databaseDirectory, 0, heapContainerId);
                DelosStorageConsistencyTarget mvccTarget = new DelosStorageConsistencyTarget(
                        " DELOS_MVCC ", null, 0, mvccContainerId);

                DelosStorageMetadataQuery query = DelosStorageDiagnosticsRegistry.metadataQuery();
                List<String> providerIds = query.providerIds();
                assertTrue("expected heap metadata provider", providerIds.contains(
                        DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
                assertTrue("expected MVCC metadata provider", providerIds.contains(
                        DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));
                assertTrue("provider order should be deterministic: " + providerIds,
                        providerIds.indexOf(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID)
                                < providerIds.indexOf(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));

                List<DelosStorageMetadataSnapshot> snapshots = DelosStorageDiagnosticsRegistry.metadataSnapshots(
                        List.of(heapTarget, mvccTarget));
                assertEquals("expected heap and MVCC metadata snapshots", 2, snapshots.size());

                DelosStorageMetadataSnapshot heapSnapshot = snapshots.get(0);
                assertEquals("expected normalized heap provider id",
                        DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                        heapSnapshot.providerId());
                assertTrue("heap metadata snapshot must be read-only", heapSnapshot.readOnly());
                assertTrue("heap metadata snapshot should be clean", heapSnapshot.clean());
                assertTrue("heap metadata should report observed storage bytes",
                        heapSnapshot.statistics().observedStorageBytes() > 0L);
                assertTrue("heap storage cost diagnostics should be enabled by property",
                        heapSnapshot.costEstimate().storageStatisticsEnabled());
                assertFalse("heap estimate must not be consumed by Derby optimizer",
                        heapSnapshot.costEstimate().consumedByDerbyOptimizer());

                DelosStorageMetadataSnapshot mvccSnapshot = snapshots.get(1);
                assertEquals("expected normalized MVCC provider id",
                        DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                        mvccSnapshot.providerId());
                assertTrue("MVCC metadata snapshot must be read-only", mvccSnapshot.readOnly());
                assertTrue("MVCC metadata snapshot should be clean", mvccSnapshot.clean());
                assertEquals("expected three MVCC logical rows", 3L,
                        mvccSnapshot.statistics().logicalRowCount());
                assertTrue("MVCC metadata should report observed storage bytes",
                        mvccSnapshot.statistics().observedStorageBytes() > 0L);
                assertTrue("MVCC storage cost diagnostics should be enabled by property",
                        mvccSnapshot.costEstimate().storageStatisticsEnabled());
                assertFalse("MVCC estimate must not be consumed by Derby optimizer",
                        mvccSnapshot.costEstimate().consumedByDerbyOptimizer());

                DelosStorageStatisticsReport statisticsReport = DelosStorageDiagnosticsRegistry.statisticsReport(
                        heapTarget,
                        mvccTarget);
                assertEquals("statistics report should still use provider-chain snapshots", 2,
                        statisticsReport.targetCount());
                assertTrue("statistics report must be read-only", statisticsReport.readOnly());

                DelosStorageCostReport costReport = DelosStorageDiagnosticsRegistry.costReport(
                        heapTarget,
                        mvccTarget);
                assertTrue("cost report should be enabled by explicit property",
                        costReport.storageStatisticsEnabled());
                assertTrue("cost report must remain proof-only", costReport.proofOnly());
                assertFalse("metadata provider chain must not enable optimizer consumption",
                        costReport.consumedByDerbyOptimizer());
                assertEquals("cost report should contain heap and MVCC estimates", 2, costReport.targetCount());

                assertRows(connection,
                        "select id, name from metadata_heap_t order by id",
                        "1|heap-alpha",
                        "2|heap-beta");
                assertRows(connection,
                        "select id, name from metadata_mvcc_t order by id",
                        "1|mvcc-alpha",
                        "2|mvcc-beta",
                        "3|mvcc-gamma");
                connection.commit();
            }
        } finally {
            if (oldProperty == null) {
                System.clearProperty(DelosStorageCostIntegration.ENABLED_PROPERTY);
            } else {
                System.setProperty(DelosStorageCostIntegration.ENABLED_PROPERTY, oldProperty);
            }
        }
    }
}
