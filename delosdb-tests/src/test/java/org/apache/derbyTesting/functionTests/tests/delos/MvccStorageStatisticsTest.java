/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.MvccStorageStatisticsTest

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

import org.apache.derby.iapi.store.types.DelosMvccStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for MVCC-specific storage statistics. */
public final class MvccStorageStatisticsTest extends MvccSqlTestSupport {
    public void testMvccStorageStatisticsExposeModernStorageSubsystems() throws Exception {
        String databaseName = databaseName("mvcc-storage-statistics-db");
        String large = repeated('s', 24000);

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table mvcc_storage_statistics_t "
                    + "(id int primary key, name varchar(32), payload varchar(32672)) using delos_mvcc");
            insertRow(connection, 1, "alpha", "small-alpha");
            insertRow(connection, 2, "beta", large);
            insertRow(connection, 3, "gamma", "small-gamma");
            insertRow(connection, 4, "theta", "small-theta");
            executeUpdate(connection, "update mvcc_storage_statistics_t set name = 'beta2' where id = 2");
            executeUpdate(connection, "delete from mvcc_storage_statistics_t where id = 3");
            connection.commit();

            long containerId = mvccContainerId(connection, "MVCC_STORAGE_STATISTICS_T");
            assertRows(connection,
                    "select name, id from mvcc_storage_statistics_t where name = 'theta'",
                    "theta|4");
            assertRows(connection,
                    "select name, id from mvcc_storage_statistics_t "
                            + "where name >= 'alpha' and name < 'theta'",
                    "alpha|1",
                    "beta2|2");
            assertRows(connection,
                    "select id, length(payload) from mvcc_storage_statistics_t order by id",
                    "1|11",
                    "2|" + large.length(),
                    "4|11");

            DelosMvccStorageStatistics statistics = DelosStorageDiagnosticsRegistry.mvccStorageStatistics(
                    0, containerId);
            assertEquals("expected normalized MVCC provider id",
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                    statistics.providerId());
            assertTrue("MVCC statistics must be read-only", statistics.readOnly());
            assertEquals("expected three visible logical MVCC rows", 3L, statistics.logicalRowCount());
            assertTrue("expected row-page statistics", statistics.rowPageCount() > 0L);
            assertTrue("expected physical versions after insert/update/delete",
                    statistics.physicalVersionCount() >= statistics.logicalRowCount());
            assertTrue("expected ordered-index statistics", statistics.hasOrderedIndexStatistics());
            assertTrue("expected ordered-index lookups from equality/range probes",
                    statistics.orderedIndexLookupCount() > 0L);
            assertTrue("expected ordered-index row ids from equality/range probes",
                    statistics.orderedIndexRowIdCount() > 0L);
            assertTrue("candidate-index authority should remain removed even when ordered-index fallback diagnostics are non-zero",
                    statistics.candidateIndexAuthorityRemoved());
            assertTrue("expected free-space statistics", statistics.hasFreeSpaceStatistics());
            assertTrue("expected visibility/prune-map statistics", statistics.hasVisibilityStatistics());
            assertTrue("expected page-cache statistics", statistics.hasPageCacheStatistics());
            assertTrue("page-cache pins should balance after SQL work", statistics.cachePinsBalanced());
            assertTrue("write-through cache should be clean after commit", statistics.dirtyStateClean());
            assertTrue("expected attribute-overflow statistics", statistics.hasAttributeOverflowStatistics());
            assertTrue("expected attribute overflow bytes for the large value",
                    statistics.attributeOverflowValueBytes() >= large.length());
            assertTrue("expected subsystem recovery statistics", statistics.hasRecoveryStatistics());
            assertTrue("expected complete MVCC recovery metadata boundary",
                    statistics.recoveryBoundaryComplete());
            assertTrue("expected observed MVCC storage bytes", statistics.observedStorageBytes() > 0L);
            assertTrue("expected MVCC-specific authority observation",
                    statistics.observations().contains("candidate indexes are not normal SQL read authority"));

            mvccDiagnostics().assertConsistentForTesting(0, containerId);
            connection.rollback();
        }
    }

    private static void insertRow(Connection connection, int id, String name, String payload) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into mvcc_storage_statistics_t values (?, ?, ?)")) {
            statement.setInt(1, id);
            statement.setString(2, name);
            statement.setString(3, payload);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static String repeated(char value, int length) {
        return String.valueOf(value).repeat(length);
    }
}
