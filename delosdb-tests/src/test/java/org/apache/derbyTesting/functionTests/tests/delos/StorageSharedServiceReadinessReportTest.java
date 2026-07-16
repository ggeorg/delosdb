/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.StorageSharedServiceReadinessReportTest

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

import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

import org.apache.derby.iapi.store.types.DelosStorageConsistencyTarget;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;
import org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessItem;
import org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessLevel;
import org.apache.derby.iapi.store.types.DelosStorageSharedServiceReadinessReport;

/** SQL proof for the conservative heap/MVCC shared-service readiness report. */
public final class StorageSharedServiceReadinessReportTest extends MvccSqlTestSupport {
    public void testSharedServiceReadinessReportKeepsExtractionConservative() throws Exception {
        String databaseName = databaseName("storage-shared-service-readiness-db");
        long heapContainerId;
        long mvccContainerId;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_shared_service_t "
                    + "(id int primary key, payload varchar(64))");
            executeUpdate(connection, "insert into heap_shared_service_t values (1, 'heap-one')");
            executeUpdate(connection, "insert into heap_shared_service_t values (2, 'heap-two')");

            executeUpdate(connection, "create table mvcc_shared_service_t "
                    + "(id int primary key, category varchar(16), payload varchar(256)) using delos_mvcc");
            executeUpdate(connection, "create index mvcc_shared_service_category_idx "
                    + "on mvcc_shared_service_t(category)");
            executeUpdate(connection, "insert into mvcc_shared_service_t values (1, 'alpha', 'mvcc-one')");
            executeUpdate(connection, "insert into mvcc_shared_service_t values (2, 'beta', 'mvcc-two')");
            connection.commit();

            executeUpdate(connection, "update mvcc_shared_service_t "
                    + "set payload = 'mvcc-one-updated' where id = 1");
            connection.commit();
            inPlaceCompressTable(connection, "MVCC_SHARED_SERVICE_T");
            connection.commit();

            heapContainerId = baseContainerId(connection, "HEAP_SHARED_SERVICE_T", "heap");
            mvccContainerId = mvccContainerId(connection, "MVCC_SHARED_SERVICE_T");

            DelosStorageSharedServiceReadinessReport report = DelosStorageDiagnosticsRegistry
                    .sharedServiceReadinessReport(List.of(
                            DelosStorageConsistencyTarget.heap(Path.of(databaseName), 0, heapContainerId),
                            mvccTarget(databaseName, 0, mvccContainerId)));

            assertEquals("readiness report should classify the current shared-service candidates",
                    8, report.itemCount());
            assertTrue("allowed extraction must stay read-only only: " + report.summaries(),
                    report.extractionLimitedToReadOnlyServices());
            assertEquals("only diagnostics and lifecycle read-models are currently extraction-ready",
                    2L, report.extractionAllowedCount());
            assertTrue("report should preserve report-only candidates",
                    report.reportOnlyCount() >= 2L);
            assertTrue("report should preserve provider-owned candidates",
                    report.providerOwnedCount() >= 4L);

            DelosStorageSharedServiceReadinessItem diagnostics = report.item(
                    DelosStorageSharedServiceReadinessReport.DIAGNOSTICS_READ_MODEL);
            assertEquals(DelosStorageSharedServiceReadinessLevel.READY_FOR_READ_ONLY_SHARED_SERVICE,
                    diagnostics.readinessLevel());
            assertTrue("diagnostics read-model may be extracted only as read-only",
                    diagnostics.readyForExtraction() && diagnostics.readOnlyOnly());

            DelosStorageSharedServiceReadinessItem lifecycle = report.item(
                    DelosStorageSharedServiceReadinessReport.LIFECYCLE_READ_MODEL);
            assertEquals(DelosStorageSharedServiceReadinessLevel.READY_FOR_READ_ONLY_SHARED_SERVICE,
                    lifecycle.readinessLevel());
            assertTrue("lifecycle read-model extraction must remain read-only",
                    lifecycle.readyForExtraction() && lifecycle.readOnlyOnly());

            DelosStorageSharedServiceReadinessItem statisticsCost = report.item(
                    DelosStorageSharedServiceReadinessReport.STATISTICS_COST_READ_MODEL);
            assertEquals("cost/statistics are shared reports, not optimizer authority",
                    DelosStorageSharedServiceReadinessLevel.READY_FOR_REPORT_ONLY,
                    statisticsCost.readinessLevel());
            assertFalse("cost/statistics report must not be treated as extraction-ready execution authority",
                    statisticsCost.readyForExtraction());
            assertTrue("cost/statistics report must record why optimizer consumption stays blocked",
                    !statisticsCost.blockers().isEmpty());

            DelosStorageSharedServiceReadinessItem backup = report.item(
                    DelosStorageSharedServiceReadinessReport.BACKUP_RESTORE_ORCHESTRATION);
            assertEquals(DelosStorageSharedServiceReadinessLevel.READY_FOR_REPORT_ONLY,
                    backup.readinessLevel());
            assertFalse("backup/restore execution must remain provider-owned", backup.readyForExtraction());

            DelosStorageSharedServiceReadinessItem buffer = report.item(
                    DelosStorageSharedServiceReadinessReport.BUFFER_MANAGEMENT);
            assertEquals(DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF,
                    buffer.readinessLevel());
            assertTrue("buffer readiness must keep heap cache compatibility as a blocker: " + buffer.summary(),
                    buffer.blocked());

            DelosStorageSharedServiceReadinessItem pageCodec = report.item(
                    DelosStorageSharedServiceReadinessReport.PAGE_CODEC);
            assertEquals(DelosStorageSharedServiceReadinessLevel.HEAP_COMPATIBILITY_BOUNDARY,
                    pageCodec.readinessLevel());
            assertFalse("page codec must not be extracted across heap/MVCC", pageCodec.readyForExtraction());

            DelosStorageSharedServiceReadinessItem orderedIndex = report.item(
                    DelosStorageSharedServiceReadinessReport.ORDERED_INDEX_AUTHORITY);
            assertEquals(DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF,
                    orderedIndex.readinessLevel());
            assertTrue("ordered-index authority must stay provider-local", orderedIndex.blocked());

            DelosStorageSharedServiceReadinessItem purge = report.item(
                    DelosStorageSharedServiceReadinessReport.PURGE_VACUUM);
            assertEquals(DelosStorageSharedServiceReadinessLevel.MVCC_ONLY_PROOF,
                    purge.readinessLevel());
            assertTrue("purge/vacuum must stay provider-local", purge.blocked());

            assertRows(connection,
                    "select id, payload from heap_shared_service_t order by id",
                    "1|heap-one",
                    "2|heap-two");
            assertRows(connection,
                    "select id, payload from mvcc_shared_service_t order by id",
                    "1|mvcc-one-updated",
                    "2|mvcc-two");
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, payload from heap_shared_service_t order by id",
                    "1|heap-one",
                    "2|heap-two");
            assertRows(reopened,
                    "select id, payload from mvcc_shared_service_t order by id",
                    "1|mvcc-one-updated",
                    "2|mvcc-two");
        }
    }
}
