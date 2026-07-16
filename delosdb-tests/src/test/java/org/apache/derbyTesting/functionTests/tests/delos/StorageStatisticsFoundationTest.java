/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageStatisticsFoundationTest

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

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageStatisticsReport;

/** SQL gate for the provider-neutral DelosDB storage-statistics foundation. */
public final class StorageStatisticsFoundationTest extends MvccSqlTestSupport {
    public void testMixedHeapAndMvccStatisticsReportIsReadOnly() throws Exception {
        String databaseName = databaseName("storage-statistics-foundation-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table stats_heap_t "
                    + "(id int primary key, name varchar(32))");
            executeUpdate(connection, "create table stats_mvcc_t "
                    + "(id int primary key, name varchar(32)) using delos_mvcc");
            executeUpdate(connection, "insert into stats_heap_t values (1, 'heap-alpha')");
            executeUpdate(connection, "insert into stats_heap_t values (2, 'heap-beta')");
            executeUpdate(connection, "insert into stats_mvcc_t values (1, 'mvcc-alpha')");
            executeUpdate(connection, "insert into stats_mvcc_t values (2, 'mvcc-beta')");
            connection.commit();

            long heapContainerId = baseContainerId(connection, "STATS_HEAP_T", "heap");
            long mvccContainerId = mvccContainerId(connection, "STATS_MVCC_T");

            DelosStorageStatisticsReport report = DelosStorageDiagnosticsRegistry.statisticsReport(
                    DelosStorageConsistencyTarget.heap(databaseDirectory, 0, heapContainerId),
                    mvccTarget(databaseName, 0, mvccContainerId));

            assertEquals("expected heap and MVCC statistics targets", 2, report.targetCount());
            assertTrue("statistics report must be read-only", report.readOnly());
            assertTrue("expected heap provider in statistics report",
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID));
            assertTrue("expected MVCC provider in statistics report",
                    report.providerIds().contains(DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID));
            assertTrue("expected observed pages in mixed statistics: " + report.summaries(),
                    report.totalPageCount() > 0L);
            assertTrue("expected observed storage bytes in mixed statistics: " + report.summaries(),
                    report.totalObservedStorageBytes() > 0L);

            DelosStorageStatistics heapStats = report.statistics(
                    "  DERBY_HEAP  ", 0, heapContainerId);
            assertEquals("expected normalized heap provider id",
                    DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID,
                    heapStats.providerId());
            assertTrue("expected heap stats to be read-only", heapStats.readOnly());
            assertTrue("expected heap page observation", heapStats.hasPages());
            assertTrue("expected heap storage-byte observation", heapStats.observedStorageBytes() > 0L);

            DelosStorageStatistics mvccStats = report.statistics(
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID, 0, mvccContainerId);
            assertEquals("expected normalized MVCC provider id",
                    DelosStorageDiagnosticsRegistry.MVCC_PROVIDER_ID,
                    mvccStats.providerId());
            assertTrue("expected MVCC stats to be read-only", mvccStats.readOnly());
            assertEquals("expected two MVCC logical rows", 2L, mvccStats.logicalRowCount());
            assertTrue("expected MVCC page observation", mvccStats.hasPages());
            assertTrue("expected MVCC storage-byte observation", mvccStats.observedStorageBytes() > 0L);

            assertRows(connection,
                    "select id, name from stats_heap_t order by id",
                    "1|heap-alpha",
                    "2|heap-beta");
            assertRows(connection,
                    "select id, name from stats_mvcc_t order by id",
                    "1|mvcc-alpha",
                    "2|mvcc-beta");
            connection.commit();
        }
    }
}
