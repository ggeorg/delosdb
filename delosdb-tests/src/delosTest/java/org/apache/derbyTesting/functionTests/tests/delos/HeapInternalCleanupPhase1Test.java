/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapInternalCleanupPhase1Test

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

import org.apache.derby.iapi.store.types.DelosHeapRawStoreBoundaryDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for heap internal cleanup phase 1. */
public final class HeapInternalCleanupPhase1Test extends MvccSqlTestSupport {
    public void testHeapRawStoreBoundaryDiagnosticsPreserveCompatibilityBehavior() throws Exception {
        String databaseName = databaseName("heap-internal-cleanup-phase1-db");
        Path databaseDirectory = new File(databaseName).toPath();
        long containerId;
        Path containerFile;
        long bytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_cleanup_t "
                    + "(id int primary key, name varchar(32), quantity int)");
            executeUpdate(connection, "create index heap_cleanup_name_idx on heap_cleanup_t(name)");
            executeUpdate(connection, "insert into heap_cleanup_t values (1, 'alpha', 10)");
            executeUpdate(connection, "insert into heap_cleanup_t values (2, 'beta', 20)");
            executeUpdate(connection, "insert into heap_cleanup_t values (3, 'gamma', 30)");
            connection.commit();

            assertRows(connection,
                    "select id, name, quantity from heap_cleanup_t order by id",
                    "1|alpha|10",
                    "2|beta|20",
                    "3|gamma|30");
            assertRows(connection,
                    "select id, name from heap_cleanup_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            containerId = baseContainerId(connection, "HEAP_CLEANUP_T", "heap");
            DelosHeapRawStoreBoundaryDiagnostics diagnostics = DelosStorageDiagnosticsRegistry
                    .inspectHeapRawStoreBoundary(databaseDirectory, 0, containerId);
            assertCleanBoundaryDiagnostics(diagnostics, containerId);
            containerFile = diagnostics.containerFile();
            bytesBefore = Files.size(containerFile);
            assertEquals("heap boundary diagnostics must not rewrite the heap container", bytesBefore,
                    diagnostics.containerBytes());
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name, quantity from heap_cleanup_t order by id",
                    "1|alpha|10",
                    "2|beta|20",
                    "3|gamma|30");
            assertRows(reopened,
                    "select id, name from heap_cleanup_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            DelosHeapRawStoreBoundaryDiagnostics reopenedDiagnostics = DelosStorageDiagnosticsRegistry
                    .inspectHeapRawStoreBoundary(databaseDirectory, 0, containerId);
            assertCleanBoundaryDiagnostics(reopenedDiagnostics, containerId);
            assertEquals("heap boundary diagnostics must stay read-only across reopen", bytesBefore,
                    Files.size(containerFile));
        }
    }

    private static void assertCleanBoundaryDiagnostics(
            DelosHeapRawStoreBoundaryDiagnostics diagnostics,
            long containerId) {
        assertEquals(DelosStorageDiagnosticsRegistry.HEAP_PROVIDER_ID, diagnostics.providerId());
        assertEquals(0, diagnostics.segment());
        assertEquals(containerId, diagnostics.containerId());
        assertTrue("heap boundary diagnostics must be read-only", diagnostics.readOnly());
        assertTrue("expected heap container file", diagnostics.containerFileExists());
        assertTrue("expected observed heap page size", diagnostics.pageSizeBytes() > 0L);
        assertTrue("expected heap container bytes", diagnostics.containerBytes() > 0L);
        assertTrue("expected estimated heap pages", diagnostics.estimatedPageCount() > 0L);
        assertFalse("heap page format rewrite must remain disallowed",
                diagnostics.heapPageFormatMutationAllowed());
        assertFalse("raw log format rewrite must remain disallowed",
                diagnostics.rawLogFormatMutationAllowed());
        assertFalse("catalog mutation must remain disallowed", diagnostics.catalogMutationAllowed());
        assertTrue("expected clean heap raw-store boundary diagnostics", diagnostics.clean());
        assertFalse("expected boundary observations", diagnostics.observations().isEmpty());
        assertTrue("expected Derby-compatible page-format observation", diagnostics.observations().stream()
                .anyMatch(observation -> observation.contains("page format")));
        assertTrue("expected Derby-compatible log-format observation", diagnostics.observations().stream()
                .anyMatch(observation -> observation.contains("log format")));
    }
}
