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

/** Focused persistence contract for MVCC optimizer row-count authority. */
public final class MvccCardinalityCostAuthorityTest extends MvccSqlTestSupport {
    private static final int ROW_COUNT = 120;
    private static final String AUTO_STATS_PROPERTY = "derby.storage.indexStats.auto";

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
