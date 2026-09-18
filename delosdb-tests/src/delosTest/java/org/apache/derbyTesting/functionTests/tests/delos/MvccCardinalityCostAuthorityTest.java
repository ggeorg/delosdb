/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccCardinalityCostAuthorityTest

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
import java.sql.PreparedStatement;

import org.apache.derby.iapi.store.access.StoreCostController;

/** Focused persistence contract for MVCC optimizer row-count authority. */
public final class MvccCardinalityCostAuthorityTest extends MvccSqlTestSupport {
    private static final int ROW_COUNT = 120;
    private static final String AUTO_STATS_PROPERTY = "derby.storage.indexStats.auto";
    private static final String PHYSICAL_SCAN_COST_PROPERTY =
            "delosdb.experimental.mvccPhysicalScanCost.enabled";
    private static final String PHYSICAL_ROW_LOCATION_COST_PROPERTY =
            "delosdb.experimental.mvccPhysicalRowLocationCost.enabled";

    public void testMvccStatisticsEstimateSurvivesFreshCostControllerAndRestart()
            throws Exception {
        String database = databaseName("mvcc-cardinality-cost-authority");
        try (SystemPropertyScope ignored = setSystemProperty(AUTO_STATS_PROPERTY, "false")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table mvcc_cardinality_t ("
                                + "id int primary key, group_id int, payload varchar(32)) "
                                + "using delos_mvcc");
                executeUpdate(connection,
                        "create index mvcc_cardinality_group_idx "
                                + "on mvcc_cardinality_t(group_id)");
                insertRows(connection, "MVCC_CARDINALITY_T");
                connection.commit();

                MvccRawStoreMetadataInspection.setBaseScanEstimatedRowCount(
                        connection, "MVCC_CARDINALITY_T", 7L);
                assertEquals(
                        7L,
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                connection, "MVCC_CARDINALITY_T"));

                executeUpdate(connection,
                        "call syscs_util.syscs_update_statistics("
                                + "'APP', 'MVCC_CARDINALITY_T', null)");
                connection.commit();

                // RowCountable deliberately exposes a rough, unlogged estimate.
                // The contract here is that ANALYZE replaces the poisoned value
                // and later cost controllers no longer fall back to one row.
                long estimateAfterStatistics =
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                connection, "MVCC_CARDINALITY_T");
                assertTrue(
                        "MVCC statistics must replace the poisoned one-row-scale estimate",
                        estimateAfterStatistics > 7L);
                assertRows(connection,
                        "select count(*) from mvcc_cardinality_t",
                        Integer.toString(ROW_COUNT));
                connection.commit();
            }

            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                long estimateAfterRestart =
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                reopened, "MVCC_CARDINALITY_T");
                assertTrue(
                        "MVCC RawStore estimate must survive restart instead of returning to 1",
                        estimateAfterRestart > 7L);
                assertRows(reopened,
                        "select count(*) from mvcc_cardinality_t",
                        Integer.toString(ROW_COUNT));
                reopened.commit();
            }
        } finally {
            shutdownIfBooted(database);
        }
    }

    public void testMvccPhysicalScanCostAccountsForCurrentContainerGeometry()
            throws Exception {
        String database = databaseName("mvcc-physical-scan-cost");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table mvcc_scan_cost_t ("
                            + "id int primary key, group_id int, payload varchar(32)) "
                            + "using delos_mvcc");
            insertRows(connection, "MVCC_SCAN_COST_T");
            connection.commit();

            double defaultCost;
            try (SystemPropertyScope ignored = clearSystemProperty(
                    PHYSICAL_SCAN_COST_PROPERTY)) {
                defaultCost = MvccRawStoreMetadataInspection.storeCostScanCost(
                        connection,
                        "MVCC_SCAN_COST_T",
                        StoreCostController.STORECOST_SCAN_SET);
            }

            double legacyCost;
            try (SystemPropertyScope ignored = setSystemProperty(
                    PHYSICAL_SCAN_COST_PROPERTY, "false")) {
                legacyCost = MvccRawStoreMetadataInspection.storeCostScanCost(
                        connection,
                        "MVCC_SCAN_COST_T",
                        StoreCostController.STORECOST_SCAN_SET);
            }

            double physicalCost;
            try (SystemPropertyScope ignored = setSystemProperty(
                    PHYSICAL_SCAN_COST_PROPERTY, "true")) {
                physicalCost = MvccRawStoreMetadataInspection.storeCostScanCost(
                        connection,
                        "MVCC_SCAN_COST_T",
                        StoreCostController.STORECOST_SCAN_SET);
            }

            assertTrue(
                    "Physical MVCC scan costing must account for page and row geometry: "
                            + "legacy=" + legacyCost + ", physical=" + physicalCost,
                    physicalCost > legacyCost);
            assertEquals(
                    "Physical MVCC scan costing must be the default when no override is present",
                    physicalCost,
                    defaultCost,
                    0.0d);
            connection.rollback();
        } finally {
            shutdownIfBooted(database);
        }
    }

    public void testMvccPhysicalRowLocationCostAccountsForCurrentRecordGeometry()
            throws Exception {
        String database = databaseName("mvcc-physical-row-location-cost");
        try (Connection connection = openDatabase(database, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection,
                    "create table mvcc_row_location_cost_t ("
                            + "id int primary key, group_id int, payload varchar(32)) "
                            + "using delos_mvcc");
            insertRows(connection, "MVCC_ROW_LOCATION_COST_T");
            connection.commit();

            double defaultCost;
            try (SystemPropertyScope ignored = clearSystemProperty(
                    PHYSICAL_ROW_LOCATION_COST_PROPERTY)) {
                defaultCost = MvccRawStoreMetadataInspection.storeCostRowLocationFetchCost(
                        connection, "MVCC_ROW_LOCATION_COST_T", 0);
            }

            double legacyCost;
            try (SystemPropertyScope ignored = setSystemProperty(
                    PHYSICAL_ROW_LOCATION_COST_PROPERTY, "false")) {
                legacyCost = MvccRawStoreMetadataInspection.storeCostRowLocationFetchCost(
                        connection, "MVCC_ROW_LOCATION_COST_T", 0);
            }

            double randomPhysicalCost;
            double clusteredPhysicalCost;
            try (SystemPropertyScope ignored = setSystemProperty(
                    PHYSICAL_ROW_LOCATION_COST_PROPERTY, "true")) {
                randomPhysicalCost =
                        MvccRawStoreMetadataInspection.storeCostRowLocationFetchCost(
                                connection, "MVCC_ROW_LOCATION_COST_T", 0);
                clusteredPhysicalCost =
                        MvccRawStoreMetadataInspection.storeCostRowLocationFetchCost(
                                connection,
                                "MVCC_ROW_LOCATION_COST_T",
                                StoreCostController.STORECOST_CLUSTERED);
            }

            assertEquals(StoreCostController.BASE_CACHED_ROW_FETCH_COST, legacyCost, 0.0d);
            assertEquals(
                    "Physical MVCC RowLocation costing must be the default when no override is present",
                    randomPhysicalCost, defaultCost, 0.0d);
            assertTrue(
                    "Physical MVCC RowLocation costing must include CURRENT record geometry: "
                            + "legacy=" + legacyCost + ", physical=" + randomPhysicalCost,
                    randomPhysicalCost > legacyCost);
            assertTrue(
                    "Random MVCC RowLocation fetches must cost more than clustered fetches: "
                            + "random=" + randomPhysicalCost
                            + ", clustered=" + clusteredPhysicalCost,
                    randomPhysicalCost > clusteredPhysicalCost);
            assertTrue(
                    "Clustered MVCC RowLocation fetches must still include row-byte cost: "
                            + "legacy=" + legacyCost
                            + ", clustered=" + clusteredPhysicalCost,
                    clusteredPhysicalCost > legacyCost);
            connection.rollback();
        } finally {
            shutdownIfBooted(database);
        }
    }

    public void testHeapRowCountAuthorityAndCatalogShapeRemainUnchanged()
            throws Exception {
        String database = databaseName("heap-cardinality-cost-regression");
        try (SystemPropertyScope ignored = setSystemProperty(AUTO_STATS_PROPERTY, "false")) {
            try (Connection connection = openDatabase(database, true)) {
                connection.setAutoCommit(false);
                executeUpdate(connection,
                        "create table heap_cardinality_t ("
                                + "id int primary key, group_id int, payload varchar(32))");
                executeUpdate(connection,
                        "create index heap_cardinality_group_idx "
                                + "on heap_cardinality_t(group_id)");
                insertRows(connection, "HEAP_CARDINALITY_T");
                connection.commit();

                MvccRawStoreMetadataInspection.setBaseScanEstimatedRowCount(
                        connection, "HEAP_CARDINALITY_T", 9L);
                assertEquals(
                        9L,
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                connection, "HEAP_CARDINALITY_T"));

                executeUpdate(connection,
                        "call syscs_util.syscs_update_statistics("
                                + "'APP', 'HEAP_CARDINALITY_T', null)");
                connection.commit();

                long estimateAfterStatistics =
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                connection, "HEAP_CARDINALITY_T");
                assertTrue(
                        "Heap statistics must replace the deliberately poisoned estimate",
                        estimateAfterStatistics > 9L);
                assertRows(connection,
                        "select count(*) "
                                + "from sys.syscolumns c, sys.systables t "
                                + "where c.referenceid = t.tableid "
                                + "and t.tablename = 'SYSTABLES' "
                                + "and c.columnname = 'STORAGEPROVIDER'",
                        "0");
                connection.commit();
            }

            shutdownDatabase(database);

            try (Connection reopened = openDatabase(database, false)) {
                reopened.setAutoCommit(false);
                long estimateAfterRestart =
                        MvccRawStoreMetadataInspection.storeCostEstimatedRowCount(
                                reopened, "HEAP_CARDINALITY_T");
                assertTrue(
                        "Heap RawStore estimate must remain available after restart",
                        estimateAfterRestart > 9L);
                reopened.commit();
            }
        } finally {
            shutdownIfBooted(database);
        }
    }

    private static void insertRows(Connection connection, String table) throws Exception {
        try (PreparedStatement insert = connection.prepareStatement(
                "insert into " + table + " values (?, ?, ?)")) {
            for (int id = 1; id <= ROW_COUNT; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % 12);
                insert.setString(3, "payload-" + id);
                insert.addBatch();
            }
            int[] counts = insert.executeBatch();
            assertEquals(ROW_COUNT, counts.length);
        }
    }

    private static void shutdownIfBooted(String database) throws Exception {
        try {
            shutdownDatabase(database);
        } catch (java.sql.SQLException exception) {
            if (!"XJ004".equals(exception.getSQLState())) {
                throw exception;
            }
        }
    }
}
