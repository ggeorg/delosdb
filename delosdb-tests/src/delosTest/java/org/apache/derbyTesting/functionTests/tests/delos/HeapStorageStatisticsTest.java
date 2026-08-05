/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapStorageStatisticsTest

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.derby.iapi.store.types.DelosHeapStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for heap-specific read-only storage statistics. */
public final class HeapStorageStatisticsTest extends MvccSqlTestSupport {
    public void testHeapStorageStatisticsExposeCompatibilityStorageSummary() throws Exception {
        String databaseName = databaseName("heap-storage-statistics-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long tableContainerId;
        long indexContainerId;
        long tableBytesBefore;
        long indexBytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_storage_statistics_t "
                    + "(id int, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index heap_storage_statistics_name_idx "
                    + "on heap_storage_statistics_t(name)");
            executeUpdate(connection, "insert into heap_storage_statistics_t values (1, 'alpha', 'one')");
            executeUpdate(connection, "insert into heap_storage_statistics_t values (2, 'beta', 'two')");
            executeUpdate(connection, "insert into heap_storage_statistics_t values (3, 'gamma', 'three')");
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_storage_statistics_t order by id",
                    "1|alpha",
                    "2|beta",
                    "3|gamma");
            assertRows(connection,
                    "select id, name from heap_storage_statistics_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            tableContainerId = baseContainerId(connection, "HEAP_STORAGE_STATISTICS_T", "heap");
            indexContainerId = indexContainerId(
                    connection, "HEAP_STORAGE_STATISTICS_T", "HEAP_STORAGE_STATISTICS_NAME_IDX");

            DelosHeapStorageStatistics statistics = DelosStorageDiagnosticsRegistry.heapStorageStatistics(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertHeapStorageStatistics(statistics, tableContainerId, indexContainerId);

            tableBytesBefore = Files.size(statistics.tableContainerFile());
            indexBytesBefore = Files.size(statistics.indexContainerFiles().get(0));
            assertEquals("heap statistics should not rewrite heap table container",
                    tableBytesBefore, statistics.tableContainerBytes());
            assertEquals("heap statistics should not rewrite heap index container",
                    indexBytesBefore, statistics.indexContainerBytes());
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_storage_statistics_t order by id",
                    "1|alpha",
                    "2|beta",
                    "3|gamma");
            assertRows(reopened,
                    "select id, name from heap_storage_statistics_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            DelosHeapStorageStatistics reopenedStatistics = DelosStorageDiagnosticsRegistry.heapStorageStatistics(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertHeapStorageStatistics(reopenedStatistics, tableContainerId, indexContainerId);
            assertEquals("heap table statistics must remain read-only across reopen", tableBytesBefore,
                    Files.size(reopenedStatistics.tableContainerFile()));
            assertEquals("heap index statistics must remain read-only across reopen", indexBytesBefore,
                    Files.size(reopenedStatistics.indexContainerFiles().get(0)));
        }
    }

    private static void assertHeapStorageStatistics(
            DelosHeapStorageStatistics statistics,
            long tableContainerId,
            long indexContainerId) throws Exception {
        assertEquals("expected normalized heap provider id",
                DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, statistics.providerId());
        assertEquals(0, statistics.segment());
        assertEquals(tableContainerId, statistics.containerId());
        assertTrue("heap statistics must be read-only", statistics.readOnly());
        assertTrue("expected table container file", statistics.tableContainerFileExists());
        assertTrue("expected table storage bytes", statistics.tableContainerBytes() > 0L);
        assertEquals("expected one inspected index container", 1L, statistics.indexContainerCount());
        assertEquals("expected index container id", indexContainerId,
                statistics.indexContainerIds().get(0).longValue());
        assertTrue("expected index container file", Files.isRegularFile(statistics.indexContainerFiles().get(0)));
        assertTrue("expected index storage bytes", statistics.hasIndexStorageStatistics());
        assertEquals("total storage should equal table plus index bytes",
                statistics.tableContainerBytes() + statistics.indexContainerBytes(),
                statistics.totalStorageBytes());
        assertEquals("observed bytes should mirror table+index storage bytes",
                statistics.totalStorageBytes(), statistics.observedStorageBytes());
        assertTrue("expected estimated heap page count", statistics.hasHeapPageStatistics());
        assertTrue("expected estimated index page count", statistics.estimatedIndexPageCount() > 0L);
        assertEquals("estimated total pages should equal heap plus index pages",
                statistics.estimatedHeapPageCount() + statistics.estimatedIndexPageCount(),
                statistics.estimatedTotalPageCount());
        assertTrue("free page estimate should not be negative", statistics.freePageCount() >= 0L);
        assertTrue("overflow page count should not be negative", statistics.overflowPageCount() >= 0L);
        assertTrue("reusable page count should not be negative", statistics.reusablePageCount() >= 0L);
        assertEquals("compress-before estimate should use observed table+index bytes",
                statistics.totalStorageBytes(), statistics.estimatedCompressBeforeBytes());
        assertTrue("compress-after estimate should remain valid", statistics.compressEstimateValid());
        assertTrue("expected raw-store sanity summary", statistics.hasRawStoreSanitySummary());
        assertTrue("expected clean heap storage statistics", statistics.clean());
        assertTrue("expected heap compatibility observation",
                statistics.observations().contains("heap page format remains Derby-compatible and unchanged"));
    }

    private static long indexContainerId(Connection connection, String tableName, String indexName)
            throws SQLException {
        String sql = "select c.conglomeratenumber "
                + "from sys.sysconglomerates c, sys.systables t, sys.sysschemas s "
                + "where c.tableid = t.tableid "
                + "and t.schemaid = s.schemaid "
                + "and s.schemaname = 'APP' "
                + "and t.tablename = ? "
                + "and c.conglomeratename = ? "
                + "and c.isindex = true";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            statement.setString(2, indexName);
            try (ResultSet rs = statement.executeQuery()) {
                assertTrue("expected heap index conglomerate " + indexName + " for table " + tableName,
                        rs.next());
                long containerId = rs.getLong(1);
                assertFalse("expected one heap index conglomerate " + indexName + " for table " + tableName,
                        rs.next());
                return containerId;
            }
        }
    }
}
