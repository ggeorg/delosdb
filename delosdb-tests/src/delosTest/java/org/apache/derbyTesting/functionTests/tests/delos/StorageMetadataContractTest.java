/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageMetadataContractTest

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

import org.apache.derby.iapi.store.types.DelosStorageCapabilities;
import org.apache.derby.iapi.store.types.DelosStorageCapabilitiesReport;
import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageCostEstimate;
import org.apache.derby.iapi.store.types.DelosStorageCostIntegration;
import org.apache.derby.iapi.store.types.DelosStorageCostReport;
import org.apache.derby.iapi.store.types.DelosStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsContext;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageInspection;
import org.apache.derby.iapi.store.types.DelosStorageMetadataContext;
import org.apache.derby.iapi.store.types.DelosStorageMetadataQuery;
import org.apache.derby.iapi.store.types.DelosStorageMetadataSnapshot;
import org.apache.derby.iapi.store.types.DelosStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageStatisticsReport;

/** Enduring provider-neutral storage metadata/reporting contract. */
public final class StorageMetadataContractTest extends MvccSqlTestSupport {
    public void testProviderChainReportsStayReadOnlyAndOptimizerNeutral() throws Exception {
        String databaseName = databaseName("storage-metadata-contract-db");
        Path databaseDirectory = new File(databaseName).toPath();
        String oldProperty = System.getProperty(DelosStorageCostIntegration.ENABLED_PROPERTY);

        try {
            System.clearProperty(DelosStorageCostIntegration.ENABLED_PROPERTY);
            try (Connection connection = openDatabase(databaseName, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection, "create table metadata_contract_heap_t "
                        + "(id int primary key, name varchar(32), payload varchar(128))");
                executeUpdate(connection, "create table metadata_contract_mvcc_t "
                        + "(id int primary key, name varchar(32), payload varchar(128)) using delos_mvcc");
                executeUpdate(connection, "insert into metadata_contract_heap_t values (1, 'heap-alpha', 'one')");
                executeUpdate(connection, "insert into metadata_contract_heap_t values (2, 'heap-beta', 'two')");
                connection.commit();
                executeUpdate(connection, "insert into metadata_contract_mvcc_t values (1, 'mvcc-alpha', 'one')");
                executeUpdate(connection, "insert into metadata_contract_mvcc_t values (2, 'mvcc-beta', 'two')");
                connection.commit();

                long heapContainerId = baseContainerId(connection, "METADATA_CONTRACT_HEAP_T", "heap");
                long mvccContainerId = mvccContainerId(connection, "METADATA_CONTRACT_MVCC_T");
                DelosStorageConsistencyTarget heapTarget = DelosStorageConsistencyTarget.heap(
                        databaseDirectory, 0, heapContainerId);
                DelosStorageConsistencyTarget mvccTarget = mvccTarget(databaseName, 0, mvccContainerId);

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
                DelosStorageMetadataSnapshot heap = snapshots.get(0);
                DelosStorageMetadataSnapshot mvcc = snapshots.get(1);
                assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, heap.providerId());
                assertEquals(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, mvcc.providerId());
                assertTrue("heap metadata must be read-only and clean", heap.readOnly() && heap.clean());
                assertTrue("MVCC metadata must be read-only and clean", mvcc.readOnly() && mvcc.clean());
                assertTrue("heap metadata should observe storage bytes",
                        heap.statistics().observedStorageBytes() > 0L);
                assertEquals("RawStore-owned MVCC metadata must not report retired external storage-file bytes",
                        0L, mvcc.statistics().observedStorageBytes());
                assertTrue("heap metadata should observe pages", heap.statistics().hasPages());
                assertTrue("MVCC metadata should observe pages", mvcc.statistics().hasPages());
                assertEquals("provider-neutral metadata must not claim heap logical-row authority",
                        0L, heap.statistics().logicalRowCount());
                assertEquals("RawStore-owned MVCC metadata must not use the retired logical-row counter",
                        0L, mvcc.statistics().logicalRowCount());

                DelosStorageStatisticsReport statistics = DelosStorageDiagnosticsRegistry.statisticsReport(
                        heapTarget, mvccTarget);
                assertEquals("statistics report should contain heap and MVCC", 2, statistics.targetCount());
                assertTrue("statistics report must be read-only", statistics.readOnly());
                assertTrue("statistics should observe pages", statistics.totalPageCount() > 0L);
                assertTrue("statistics should observe storage bytes", statistics.totalObservedStorageBytes() > 0L);

                DelosStorageCostReport disabledCost = DelosStorageDiagnosticsRegistry.costReport(
                        heapTarget, mvccTarget);
                assertFalse("report-only storage cost must be disabled by default",
                        disabledCost.storageStatisticsEnabled());
                assertTrue("cost report must remain read-only and proof-only",
                        disabledCost.readOnly() && disabledCost.proofOnly());
                assertFalse("report-only cost must not be Derby optimizer authority",
                        disabledCost.consumedByDerbyOptimizer());

                System.setProperty(DelosStorageCostIntegration.ENABLED_PROPERTY, "true");
                DelosStorageCostReport enabledCost = DelosStorageDiagnosticsRegistry.costReport(
                        heapTarget, mvccTarget);
                assertTrue("explicit property should enable report-only cost diagnostics",
                        enabledCost.storageStatisticsEnabled());
                assertTrue("enabled report remains read-only and proof-only",
                        enabledCost.readOnly() && enabledCost.proofOnly());
                assertFalse("report-only cost must remain optimizer-ineligible",
                        enabledCost.optimizerConsumptionEligible());
                assertTrue("report-only cost must fail closed for optimizer consumption",
                        enabledCost.failClosedForOptimizer());
                assertFalse("report-only cost must not be consumed by Derby optimizer",
                        enabledCost.consumedByDerbyOptimizer());
                assertEquals("cost report should contain heap and MVCC", 2, enabledCost.targetCount());
                assertTrue("cost report should expose a positive full-scan estimate",
                        enabledCost.totalEstimatedFullScanCost() > 0L);

                assertRows(connection,
                        "select id, name from metadata_contract_heap_t order by id",
                        "1|heap-alpha", "2|heap-beta");
                assertRows(connection,
                        "select id, name from metadata_contract_mvcc_t order by id",
                        "1|mvcc-alpha", "2|mvcc-beta");
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

    public void testMetadataContextAndCapabilitiesStayExplicitAndReadOnly() throws Exception {
        String databaseName = databaseName("storage-metadata-context-contract-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            try {
                executeUpdate(connection, "create table metadata_context_heap_t "
                        + "(id int primary key, name varchar(32))");
                executeUpdate(connection, "create index metadata_context_heap_name_idx "
                        + "on metadata_context_heap_t(name)");
                executeUpdate(connection, "create table metadata_context_mvcc_t "
                        + "(id int primary key, name varchar(32)) using delos_mvcc");
                executeUpdate(connection, "insert into metadata_context_heap_t values (1, 'heap-alpha')");
                connection.commit();
                executeUpdate(connection, "insert into metadata_context_mvcc_t values (1, 'mvcc-alpha')");
                connection.commit();

                long heapContainerId = baseContainerId(connection, "METADATA_CONTEXT_HEAP_T", "heap");
                long mvccContainerId = mvccContainerId(connection, "METADATA_CONTEXT_MVCC_T");
                DelosStorageConsistencyTarget heapTarget = DelosStorageConsistencyTarget.heap(
                        databaseDirectory, 0, heapContainerId);
                DelosStorageConsistencyTarget mvccTarget = mvccTarget(databaseName, 0, mvccContainerId);

                DelosStorageDiagnostics heapDiagnostics = DelosStorageDiagnosticsRegistry.heap();
                heapDiagnostics.clearRuntimeStateForTesting();
                DelosStorageDiagnostics contextualDiagnostics = heapDiagnostics.withContext(
                        DelosStorageDiagnosticsContext.databaseDirectory(databaseDirectory));
                assertEquals("explicit diagnostics context must not mutate the provider", 0,
                        heapDiagnostics.runtimeStateCountForTesting());
                DelosStorageInspection inspection = DelosStorageInspection.fromDiagnostics(
                        contextualDiagnostics, 0, heapContainerId);
                assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, inspection.providerId());
                assertTrue("contextual heap inspection should observe the container",
                        inspection.file(DelosStorageInspection.PAGE_VOLUME_FILE).toString().contains("seg0"));
                assertEquals("contextual inspection must leave no hidden provider state", 0,
                        heapDiagnostics.runtimeStateCountForTesting());

                DelosStorageMetadataQuery query = DelosStorageDiagnosticsRegistry.metadataQuery();
                assertEquals(DelosStorageMetadataContext.Purpose.METADATA_SNAPSHOT,
                        query.context().purpose());
                assertTrue("default metadata context must be optimizer-safe", query.context().optimizerSafe());
                DelosStorageMetadataQuery costQuery = query.withContext(DelosStorageMetadataContext.costReport());
                assertEquals(DelosStorageMetadataContext.Purpose.COST_REPORT, costQuery.context().purpose());
                assertEquals("withContext must not mutate the original query",
                        DelosStorageMetadataContext.Purpose.METADATA_SNAPSHOT, query.context().purpose());
                assertTrue("cost-report metadata context must remain read-only",
                        costQuery.context().readOnlyRequired());
                assertFalse("metadata context must not enable optimizer consumption",
                        costQuery.context().optimizerConsumptionAllowed());
                assertFalse("metadata context must not enable execution routing",
                        costQuery.context().executionRoutingAllowed());

                List<DelosStorageMetadataSnapshot> snapshots = DelosStorageDiagnosticsRegistry.metadataSnapshots(
                        List.of(heapTarget, mvccTarget));
                DelosStorageCapabilities heapCapabilities = snapshots.get(0).capabilities();
                DelosStorageCapabilities mvccCapabilities = snapshots.get(1).capabilities();
                assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, heapCapabilities.providerId());
                assertTrue("heap capabilities must be read-only", heapCapabilities.readOnly());
                assertTrue("heap should expose storage statistics", heapCapabilities.supportsStorageStatistics());
                assertTrue("heap should expose cost estimates", heapCapabilities.supportsCostEstimate());
                assertFalse("heap metadata does not expose Delos ordered equality",
                        heapCapabilities.supportsOrderedEqualityLookup());
                assertFalse("heap metadata does not expose Delos ordered range",
                        heapCapabilities.supportsOrderedRangeScan());
                assertFalse("heap capability metadata must be optimizer-neutral",
                        heapCapabilities.consumedByDerbyOptimizer());

                assertEquals(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, mvccCapabilities.providerId());
                assertTrue("MVCC capabilities must be read-only", mvccCapabilities.readOnly());
                assertTrue("MVCC row-id lookup should be exposed", mvccCapabilities.supportsRowIdLookup());
                assertTrue("MVCC ordered equality should be exposed",
                        mvccCapabilities.supportsOrderedEqualityLookup());
                assertTrue("MVCC ordered range should be exposed", mvccCapabilities.supportsOrderedRangeScan());
                assertTrue("MVCC stable key order should be exposed", mvccCapabilities.supportsStableKeyOrder());
                assertTrue("MVCC current-committed shortcut should be exposed",
                        mvccCapabilities.supportsCurrentCommittedShortcut());
                assertFalse("MVCC snapshot shortcut remains fail-closed",
                        mvccCapabilities.supportsSnapshotShortcut());
                assertTrue("MVCC attribute overflow should be exposed",
                        mvccCapabilities.supportsAttributeOverflow());
                assertTrue("candidate-index authority removal should be exposed",
                        mvccCapabilities.candidateIndexAuthorityRemoved());
                assertTrue("MVCC should expose storage statistics", mvccCapabilities.supportsStorageStatistics());
                assertTrue("MVCC should expose cost estimates", mvccCapabilities.supportsCostEstimate());
                assertFalse("MVCC capability metadata must be optimizer-neutral",
                        mvccCapabilities.consumedByDerbyOptimizer());

                DelosStorageCapabilitiesReport report = DelosStorageDiagnosticsRegistry.capabilitiesReport(
                        heapTarget, mvccTarget);
                assertEquals("capabilities report should contain heap and MVCC", 2, report.targetCount());
                assertTrue("capabilities report must be read-only", report.readOnly());
                assertFalse("capabilities report must be optimizer-neutral",
                        report.consumedByDerbyOptimizer());
                assertTrue("capability lookup should normalize provider ids",
                        report.capability(" delos_mvcc ", 0, mvccContainerId).supportsOrderedRangeScan());

                assertRows(connection, "select id, name from metadata_context_heap_t", "1|heap-alpha");
                assertRows(connection, "select id, name from metadata_context_mvcc_t", "1|mvcc-alpha");
            } finally {
                connection.rollback();
            }
        }

        assertInvalidMetadataContext(() -> new DelosStorageMetadataContext(
                DelosStorageMetadataContext.Purpose.METADATA_SNAPSHOT, false, false, false));
        assertInvalidMetadataContext(() -> new DelosStorageMetadataContext(
                DelosStorageMetadataContext.Purpose.COST_REPORT, true, true, false));
        assertInvalidMetadataContext(() -> new DelosStorageMetadataContext(
                DelosStorageMetadataContext.Purpose.CAPABILITIES_REPORT, true, false, true));
    }

    public void testProofOnlyCostEstimateSaturatesWithoutOverflow() {
        DelosStorageStatistics extreme = new DelosStorageStatistics(
                DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                0, 42L, true,
                Long.MAX_VALUE - 10L, Long.MAX_VALUE - 5L, Long.MAX_VALUE - 4L,
                Long.MAX_VALUE - 3L, 0L, Long.MAX_VALUE - 2L, Long.MAX_VALUE - 1L,
                Long.MAX_VALUE, Long.MAX_VALUE, 0L, 0L, Long.MAX_VALUE,
                Long.MAX_VALUE, List.of("synthetic extreme statistics"));

        DelosStorageCostEstimate estimate = DelosStorageCostEstimate.fromStatistics(extreme, true);
        assertEquals("extreme storage statistics should saturate instead of overflowing",
                Long.MAX_VALUE, estimate.estimatedFullScanCost());
        assertTrue("saturated estimate should retain row-fetch cost",
                estimate.estimatedRowFetchCost() > 0L);
        assertTrue("saturated estimate should retain index-lookup cost", estimate.hasIndexLookupCost());
        assertTrue("report cost remains proof-only", estimate.proofOnly());
        assertFalse("proof-only estimate must not be optimizer eligible",
                estimate.optimizerConsumptionEligible());
        assertTrue("proof-only estimate must fail closed", estimate.failClosedForOptimizer());
    }

    private static void assertInvalidMetadataContext(Runnable factory) {
        try {
            factory.run();
            fail("expected invalid metadata context");
        } catch (IllegalArgumentException expected) {
            assertTrue("expected context validation message", expected.getMessage().length() > 0);
        }
    }
}
