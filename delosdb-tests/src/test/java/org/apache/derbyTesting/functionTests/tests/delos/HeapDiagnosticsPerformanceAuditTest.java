/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapDiagnosticsPerformanceAuditTest

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

import org.apache.derby.iapi.store.types.DelosHeapDiagnosticsPerformanceReport;
import org.apache.derby.iapi.store.types.DelosHeapStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Executable audit that heap diagnostics stay read-only while exposing timing shape. */
public final class HeapDiagnosticsPerformanceAuditTest extends MvccSqlTestSupport {
    public void testHeapDiagnosticsPerformanceAuditIsReadOnlyAndBoundedByObservedShape() throws Exception {
        String databaseName = databaseName("heap-diagnostics-performance-audit-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long tableContainerId;
        long indexContainerId;
        long tableBytesBefore;
        long indexBytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_diag_perf_t "
                    + "(id int, bucket int, payload varchar(128))");
            executeUpdate(connection, "create index heap_diag_perf_bucket_idx on heap_diag_perf_t(bucket)");
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into heap_diag_perf_t values (?, ?, ?)")) {
                for (int i = 0; i < 64; i++) {
                    statement.setInt(1, i);
                    statement.setInt(2, i % 8);
                    statement.setString(3, "payload-" + i);
                    statement.executeUpdate();
                }
            }
            connection.commit();

            assertRows(connection,
                    "select count(*) from heap_diag_perf_t",
                    "64");
            assertRows(connection,
                    "select count(*) from heap_diag_perf_t where bucket = 3",
                    "8");

            tableContainerId = baseContainerId(connection, "HEAP_DIAG_PERF_T", "heap");
            indexContainerId = indexContainerId(connection, "HEAP_DIAG_PERF_T", "HEAP_DIAG_PERF_BUCKET_IDX");

            DelosHeapStorageDiagnostics baseline = DelosStorageDiagnosticsRegistry.inspectHeapStorage(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            tableBytesBefore = Files.size(baseline.tableContainerFile());
            indexBytesBefore = Files.size(baseline.indexContainerFiles().get(0));

            DelosHeapDiagnosticsPerformanceReport report =
                    DelosStorageDiagnosticsRegistry.inspectHeapStoragePerformance(
                            databaseDirectory, 0, tableContainerId, 5, indexContainerId);

            assertPerformanceReport(report, tableContainerId, indexContainerId);
            assertEquals("heap diagnostics performance audit must not rewrite table container",
                    tableBytesBefore, Files.size(report.lastSnapshot().tableContainerFile()));
            assertEquals("heap diagnostics performance audit must not rewrite index container",
                    indexBytesBefore, Files.size(report.lastSnapshot().indexContainerFiles().get(0)));
            assertTrue("expected summary to name heap provider", report.summaryLine().contains("provider=heap"));
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select count(*) from heap_diag_perf_t",
                    "64");
            assertRows(reopened,
                    "select count(*) from heap_diag_perf_t where bucket = 3",
                    "8");

            DelosHeapDiagnosticsPerformanceReport reopenedReport =
                    DelosStorageDiagnosticsRegistry.inspectHeapStoragePerformance(
                            databaseDirectory, 0, tableContainerId, 3, indexContainerId);
            assertPerformanceReport(reopenedReport, tableContainerId, indexContainerId);
            assertEquals("heap table diagnostics must remain read-only across reopen", tableBytesBefore,
                    Files.size(reopenedReport.lastSnapshot().tableContainerFile()));
            assertEquals("heap index diagnostics must remain read-only across reopen", indexBytesBefore,
                    Files.size(reopenedReport.lastSnapshot().indexContainerFiles().get(0)));
        }
    }

    private static void assertPerformanceReport(
            DelosHeapDiagnosticsPerformanceReport report,
            long tableContainerId,
            long indexContainerId) {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, report.providerId());
        assertEquals(0, report.segment());
        assertEquals(tableContainerId, report.containerId());
        assertTrue("expected at least one timing iteration", report.iterations() > 0);
        assertTrue("expected non-negative total timing", report.totalNanos() >= 0L);
        assertTrue("expected non-negative min timing", report.minNanos() >= 0L);
        assertTrue("expected max >= min", report.maxNanos() >= report.minNanos());
        assertTrue("expected non-negative average timing", report.averageNanos() >= 0L);
        assertTrue("heap diagnostics performance report must observe read-only diagnostics",
                report.readOnlyObserved());
        assertEquals("expected same first/last table container",
                report.firstSnapshot().containerId(), report.lastSnapshot().containerId());
        assertEquals("expected measured index container", indexContainerId,
                report.lastSnapshot().indexContainerIds().get(0).longValue());
        assertTrue("expected clean last heap diagnostic snapshot", report.lastSnapshot().clean());
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
