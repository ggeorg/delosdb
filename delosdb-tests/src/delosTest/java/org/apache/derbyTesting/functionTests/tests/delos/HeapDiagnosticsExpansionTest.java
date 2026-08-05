/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapDiagnosticsExpansionTest

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

import org.apache.derby.iapi.store.types.DelosHeapStorageDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for expanded read-only Derby-compatible heap diagnostics. */
public final class HeapDiagnosticsExpansionTest extends MvccSqlTestSupport {
    public void testHeapDiagnosticsExposeReadOnlyStorageSummary() throws Exception {
        String databaseName = databaseName("heap-diagnostics-expansion-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long tableContainerId;
        long indexContainerId;
        long tableBytesBefore;
        long indexBytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_diag_t "
                    + "(id int, name varchar(32), payload varchar(128))");
            executeUpdate(connection, "create index heap_diag_name_idx on heap_diag_t(name)");
            executeUpdate(connection, "insert into heap_diag_t values (1, 'alpha', 'one')");
            executeUpdate(connection, "insert into heap_diag_t values (2, 'beta', 'two')");
            executeUpdate(connection, "insert into heap_diag_t values (3, 'gamma', 'three')");
            connection.commit();

            assertRows(connection,
                    "select id, name from heap_diag_t order by id",
                    "1|alpha",
                    "2|beta",
                    "3|gamma");
            assertRows(connection,
                    "select id, name from heap_diag_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            tableContainerId = baseContainerId(connection, "HEAP_DIAG_T", "heap");
            indexContainerId = indexContainerId(connection, "HEAP_DIAG_T", "HEAP_DIAG_NAME_IDX");

            DelosHeapStorageDiagnostics diagnostics = DelosStorageDiagnosticsRegistry.inspectHeapStorage(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertExpandedHeapDiagnostics(diagnostics, tableContainerId, indexContainerId);

            tableBytesBefore = Files.size(diagnostics.tableContainerFile());
            indexBytesBefore = Files.size(diagnostics.indexContainerFiles().get(0));
            assertEquals("diagnostics should not rewrite heap table container", tableBytesBefore,
                    diagnostics.tableContainerBytes());
            assertEquals("diagnostics should not rewrite heap index container", indexBytesBefore,
                    diagnostics.indexContainerBytes());
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name from heap_diag_t order by id",
                    "1|alpha",
                    "2|beta",
                    "3|gamma");
            assertRows(reopened,
                    "select id, name from heap_diag_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            DelosHeapStorageDiagnostics reopenedDiagnostics = DelosStorageDiagnosticsRegistry.inspectHeapStorage(
                    databaseDirectory, 0, tableContainerId, indexContainerId);
            assertExpandedHeapDiagnostics(reopenedDiagnostics, tableContainerId, indexContainerId);
            assertEquals("heap table diagnostics must remain read-only across reopen", tableBytesBefore,
                    Files.size(reopenedDiagnostics.tableContainerFile()));
            assertEquals("heap index diagnostics must remain read-only across reopen", indexBytesBefore,
                    Files.size(reopenedDiagnostics.indexContainerFiles().get(0)));
        }
    }

    private static void assertExpandedHeapDiagnostics(
            DelosHeapStorageDiagnostics diagnostics,
            long tableContainerId,
            long indexContainerId) throws Exception {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, diagnostics.providerId());
        assertEquals(0, diagnostics.segment());
        assertEquals(tableContainerId, diagnostics.containerId());
        assertTrue("heap diagnostics must be read-only", diagnostics.readOnly());
        assertTrue("expected table container file", diagnostics.tableContainerFileExists());
        assertTrue("expected table storage bytes", diagnostics.tableContainerBytes() > 0L);
        assertEquals("expected one inspected index container", 1L, diagnostics.indexContainerCount());
        assertEquals("expected index container id", indexContainerId,
                diagnostics.indexContainerIds().get(0).longValue());
        assertTrue("expected index container file", Files.isRegularFile(diagnostics.indexContainerFiles().get(0)));
        assertTrue("expected index storage bytes", diagnostics.indexContainerBytes() > 0L);
        assertEquals("total storage should equal table plus index bytes",
                diagnostics.tableContainerBytes() + diagnostics.indexContainerBytes(),
                diagnostics.totalStorageBytes());
        assertTrue("expected estimated heap page count", diagnostics.estimatedPageCount() > 0L);
        assertEquals("allocated pages should mirror estimated pages for read-only heap diagnostics",
                diagnostics.estimatedPageCount(), diagnostics.allocatedPageCount());
        assertTrue("free page estimate should not be negative", diagnostics.freePageCount() >= 0L);
        assertTrue("overflow page count should not be negative", diagnostics.overflowPageCount() >= 0L);
        assertTrue("reusable page count should not be negative", diagnostics.reusablePageCount() >= 0L);
        assertEquals("compress-before estimate should use observed table+index bytes",
                diagnostics.totalStorageBytes(), diagnostics.estimatedCompressBeforeBytes());
        assertTrue("compress-after estimate should not exceed before estimate",
                diagnostics.estimatedCompressAfterBytes() <= diagnostics.estimatedCompressBeforeBytes());
        assertTrue("expected raw-store sanity summary", diagnostics.rawStoreSanitySummary().contains("heap"));
        assertTrue("expected clean heap storage diagnostics", diagnostics.clean());
        assertFalse("expected diagnostic observations", diagnostics.observations().isEmpty());
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
