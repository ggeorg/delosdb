/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapStorageDiagnosticsContractTest

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

import org.apache.derby.iapi.store.types.DelosHeapSanityDiagnostics;
import org.apache.derby.iapi.store.types.DelosHeapStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosHeapStorageStatistics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** Enduring heap diagnostics, statistics, and read-only sanity contract. */
public final class HeapStorageDiagnosticsContractTest extends MvccSqlTestSupport {
    public void testHeapDiagnosticsAndStatisticsRemainReadOnlyAcrossReopen() throws Exception {
        String databaseName = databaseName("heap-storage-diagnostics-contract-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long tableContainerId;
        long indexContainerId;
        long tableBytesBefore;
        long indexBytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_diag_contract_t "
                    + "(id int, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index heap_diag_contract_name_idx "
                    + "on heap_diag_contract_t(name)");
            executeUpdate(connection, "insert into heap_diag_contract_t values (1, 'alpha', 'one')");
            executeUpdate(connection, "insert into heap_diag_contract_t values (2, 'beta', 'two')");
            executeUpdate(connection, "insert into heap_diag_contract_t values (3, 'gamma', 'three')");
            connection.commit();

            assertRows(connection, "select id, name from heap_diag_contract_t order by id",
                    "1|alpha", "2|beta", "3|gamma");
            assertRows(connection,
                    "select id, name from heap_diag_contract_t where name >= 'beta' order by name",
                    "2|beta", "3|gamma");

            tableContainerId = baseContainerId(connection, "HEAP_DIAG_CONTRACT_T", "heap");
            indexContainerId = indexContainerId(
                    connection, "HEAP_DIAG_CONTRACT_T", "HEAP_DIAG_CONTRACT_NAME_IDX");

            DelosHeapStorageDiagnostics diagnostics = DelosStorageDiagnosticsRegistry.inspectHeapStorage(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            DelosHeapStorageStatistics statistics = DelosStorageDiagnosticsRegistry.heapStorageStatistics(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertHeapDiagnostics(diagnostics, tableContainerId, indexContainerId);
            assertHeapStatistics(statistics, tableContainerId, indexContainerId);
            assertEquals("statistics bytes should mirror diagnostics bytes",
                    diagnostics.totalStorageBytes(), statistics.totalStorageBytes());
            assertEquals("statistics pages should mirror diagnostics pages",
                    diagnostics.estimatedPageCount(), statistics.estimatedHeapPageCount());

            tableBytesBefore = Files.size(diagnostics.tableContainerFile());
            indexBytesBefore = Files.size(diagnostics.indexContainerFiles().get(0));
            assertEquals("diagnostics should not rewrite heap table container",
                    tableBytesBefore, diagnostics.tableContainerBytes());
            assertEquals("diagnostics should not rewrite heap index container",
                    indexBytesBefore, diagnostics.indexContainerBytes());
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened, "select id, name from heap_diag_contract_t order by id",
                    "1|alpha", "2|beta", "3|gamma");
            assertRows(reopened,
                    "select id, name from heap_diag_contract_t where name >= 'beta' order by name",
                    "2|beta", "3|gamma");

            DelosHeapStorageDiagnostics diagnostics = DelosStorageDiagnosticsRegistry.inspectHeapStorage(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            DelosHeapStorageStatistics statistics = DelosStorageDiagnosticsRegistry.heapStorageStatistics(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertHeapDiagnostics(diagnostics, tableContainerId, indexContainerId);
            assertHeapStatistics(statistics, tableContainerId, indexContainerId);
            assertEquals("heap table diagnostics must remain read-only across reopen",
                    tableBytesBefore, Files.size(diagnostics.tableContainerFile()));
            assertEquals("heap index diagnostics must remain read-only across reopen",
                    indexBytesBefore, Files.size(diagnostics.indexContainerFiles().get(0)));
        }
    }

    public void testHeapSanityDiagnosticsStayReadOnlyAndReportMissingContainers() throws Exception {
        String databaseName = databaseName("heap-sanity-diagnostics-contract-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long containerId;
        Path containerFile;
        long bytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_sanity_contract_t "
                    + "(id int primary key, name varchar(32), quantity int)");
            executeUpdate(connection, "create index heap_sanity_contract_name_idx "
                    + "on heap_sanity_contract_t(name)");
            executeUpdate(connection, "insert into heap_sanity_contract_t values (1, 'alpha', 10)");
            executeUpdate(connection, "insert into heap_sanity_contract_t values (2, 'beta', 20)");
            executeUpdate(connection, "insert into heap_sanity_contract_t values (3, 'gamma', 30)");
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_sanity_contract_t where name >= 'beta' order by name",
                    "2|beta", "3|gamma");
            containerId = baseContainerId(connection, "HEAP_SANITY_CONTRACT_T", "heap");
            DelosHeapSanityDiagnostics sanity = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, containerId);
            assertCleanHeapSanity(sanity, containerId);
            containerFile = sanity.containerFile();
            bytesBefore = sanity.containerFileBytes();
            assertEquals("sanity checker should not rewrite heap container file",
                    bytesBefore, Files.size(containerFile));
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_sanity_contract_t where name >= 'beta' order by name",
                    "2|beta", "3|gamma");
            DelosHeapSanityDiagnostics sanity = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, containerId);
            assertCleanHeapSanity(sanity, containerId);
            assertEquals("sanity checker should remain read-only across reopen",
                    bytesBefore, Files.size(containerFile));

            long missingContainerId = containerId + 1000000L;
            DelosHeapSanityDiagnostics missing = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, missingContainerId);
            assertFalse("missing heap container should not be clean", missing.clean());
            assertTrue("missing heap container should report an error", missing.errorCount() > 0);
            assertTrue("missing heap container should report missing file",
                    missing.errors().stream().anyMatch(message -> message.contains("missing")));
            assertTrue("missing-container check must remain read-only", missing.readOnly());
            assertFalse("bogus container file must not be created", Files.exists(missing.containerFile()));
        }
    }

    private static void assertHeapDiagnostics(
            DelosHeapStorageDiagnostics diagnostics, long tableContainerId, long indexContainerId)
            throws Exception {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, diagnostics.providerId());
        assertEquals(0, diagnostics.segment());
        assertEquals(tableContainerId, diagnostics.containerId());
        assertTrue("heap diagnostics must be read-only", diagnostics.readOnly());
        assertTrue("expected table container file", diagnostics.tableContainerFileExists());
        assertTrue("expected table storage bytes", diagnostics.tableContainerBytes() > 0L);
        assertEquals("expected one inspected index container", 1L, diagnostics.indexContainerCount());
        assertEquals("expected index container id", indexContainerId,
                diagnostics.indexContainerIds().get(0).longValue());
        assertTrue("expected index container file",
                Files.isRegularFile(diagnostics.indexContainerFiles().get(0)));
        assertTrue("expected index storage bytes", diagnostics.indexContainerBytes() > 0L);
        assertEquals("total storage should equal table plus index bytes",
                diagnostics.tableContainerBytes() + diagnostics.indexContainerBytes(),
                diagnostics.totalStorageBytes());
        assertTrue("expected estimated heap page count", diagnostics.estimatedPageCount() > 0L);
        assertEquals("allocated pages should mirror estimated pages",
                diagnostics.estimatedPageCount(), diagnostics.allocatedPageCount());
        assertTrue("free page estimate should not be negative", diagnostics.freePageCount() >= 0L);
        assertTrue("overflow page count should not be negative", diagnostics.overflowPageCount() >= 0L);
        assertTrue("reusable page count should not be negative", diagnostics.reusablePageCount() >= 0L);
        assertEquals("compress-before estimate should use observed storage bytes",
                diagnostics.totalStorageBytes(), diagnostics.estimatedCompressBeforeBytes());
        assertTrue("compress-after estimate should not exceed before estimate",
                diagnostics.estimatedCompressAfterBytes() <= diagnostics.estimatedCompressBeforeBytes());
        assertTrue("expected raw-store sanity summary", diagnostics.rawStoreSanitySummary().contains("heap"));
        assertTrue("expected clean heap storage diagnostics", diagnostics.clean());
        assertFalse("expected diagnostic observations", diagnostics.observations().isEmpty());
    }

    private static void assertHeapStatistics(
            DelosHeapStorageStatistics statistics, long tableContainerId, long indexContainerId)
            throws Exception {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, statistics.providerId());
        assertEquals(0, statistics.segment());
        assertEquals(tableContainerId, statistics.containerId());
        assertTrue("heap statistics must be read-only", statistics.readOnly());
        assertTrue("expected table container file", statistics.tableContainerFileExists());
        assertTrue("expected table storage bytes", statistics.tableContainerBytes() > 0L);
        assertEquals("expected one inspected index container", 1L, statistics.indexContainerCount());
        assertEquals("expected index container id", indexContainerId,
                statistics.indexContainerIds().get(0).longValue());
        assertTrue("expected index container file",
                Files.isRegularFile(statistics.indexContainerFiles().get(0)));
        assertTrue("expected index storage statistics", statistics.hasIndexStorageStatistics());
        assertEquals("total storage should equal table plus index bytes",
                statistics.tableContainerBytes() + statistics.indexContainerBytes(),
                statistics.totalStorageBytes());
        assertEquals("observed bytes should mirror heap storage bytes",
                statistics.totalStorageBytes(), statistics.observedStorageBytes());
        assertTrue("expected heap page statistics", statistics.hasHeapPageStatistics());
        assertTrue("expected estimated index page count", statistics.estimatedIndexPageCount() > 0L);
        assertEquals("total pages should equal heap plus index pages",
                statistics.estimatedHeapPageCount() + statistics.estimatedIndexPageCount(),
                statistics.estimatedTotalPageCount());
        assertTrue("free page estimate should not be negative", statistics.freePageCount() >= 0L);
        assertTrue("overflow page count should not be negative", statistics.overflowPageCount() >= 0L);
        assertTrue("reusable page count should not be negative", statistics.reusablePageCount() >= 0L);
        assertEquals("compress-before estimate should use observed storage bytes",
                statistics.totalStorageBytes(), statistics.estimatedCompressBeforeBytes());
        assertTrue("compress-after estimate should remain valid", statistics.compressEstimateValid());
        assertTrue("expected raw-store sanity summary", statistics.hasRawStoreSanitySummary());
        assertTrue("expected clean heap storage statistics", statistics.clean());
        assertTrue("expected Derby-compatible heap observation",
                statistics.observations().contains("heap page format remains Derby-compatible and unchanged"));
    }

    private static void assertCleanHeapSanity(DelosHeapSanityDiagnostics sanity, long containerId) {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, sanity.providerId());
        assertEquals(0, sanity.segment());
        assertEquals(containerId, sanity.containerId());
        assertTrue("heap sanity checker must be read-only", sanity.readOnly());
        assertTrue("expected heap segment directory", sanity.segmentDirectoryExists());
        assertTrue("expected heap container file", sanity.containerFileExists());
        assertTrue("expected non-empty heap container file", sanity.containerFileBytes() > 0L);
        assertTrue("expected at least one estimated heap page", sanity.estimatedPageCount() > 0L);
        assertEquals("expected no heap overflow pages in small fixture", 0L, sanity.overflowPageCount());
        assertEquals("expected no reusable pages in small fixture", 0L, sanity.reusablePageCount());
        assertEquals("expected no heap sanity errors", 0, sanity.errorCount());
        assertTrue("expected clean heap sanity report", sanity.clean());
        assertFalse("expected read-only observations", sanity.observations().isEmpty());
        assertTrue("expected no sanity errors", sanity.errors().isEmpty());
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
