/*

   Derby - Class org.apache.derbyTesting.functionTests.tests.delos.HeapSanityCheckerTest

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

import org.apache.derby.iapi.store.types.DelosHeapSanityDiagnostics;
import org.apache.derby.iapi.store.types.DelosStorageDiagnosticsRegistry;

/** SQL gate for the read-only Derby-compatible heap sanity checker. */
public final class HeapSanityCheckerTest extends MvccSqlTestSupport {
    public void testHeapSanityCheckerIsReadOnlyAndPreservesHeapBehavior() throws Exception {
        String databaseName = databaseName("heap-sanity-checker-db");
        long containerId;
        Path databaseDirectory = new File(databaseName).toPath();
        Path containerFile;
        long bytesBefore;

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_sanity_t "
                    + "(id int primary key, name varchar(32), quantity int)");
            executeUpdate(connection, "create index heap_sanity_name_idx on heap_sanity_t(name)");
            executeUpdate(connection, "insert into heap_sanity_t values (1, 'alpha', 10)");
            executeUpdate(connection, "insert into heap_sanity_t values (2, 'beta', 20)");
            executeUpdate(connection, "insert into heap_sanity_t values (3, 'gamma', 30)");
            connection.commit();

            assertRows(connection,
                    "select id, name, quantity from heap_sanity_t order by id",
                    "1|alpha|10",
                    "2|beta|20",
                    "3|gamma|30");
            assertRows(connection,
                    "select id, name from heap_sanity_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            containerId = baseContainerId(connection, "HEAP_SANITY_T", "heap");
            DelosHeapSanityDiagnostics sanity = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, containerId);
            assertCleanHeapSanity(sanity, containerId);
            containerFile = sanity.containerFile();
            bytesBefore = sanity.containerFileBytes();
            assertEquals("sanity checker should not rewrite heap container file", bytesBefore,
                    Files.size(containerFile));
            connection.commit();
        }

        shutdownDatabase(databaseName);

        try (Connection reopened = openDatabase(databaseName, false)) {
            assertRows(reopened,
                    "select id, name, quantity from heap_sanity_t order by id",
                    "1|alpha|10",
                    "2|beta|20",
                    "3|gamma|30");
            assertRows(reopened,
                    "select id, name from heap_sanity_t where name >= 'beta' order by name",
                    "2|beta",
                    "3|gamma");

            DelosHeapSanityDiagnostics reopenedSanity = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, containerId);
            assertCleanHeapSanity(reopenedSanity, containerId);
            assertEquals("sanity checker should remain read-only across reopen", bytesBefore,
                    Files.size(containerFile));
        }
    }

    public void testHeapSanityCheckerReportsMissingContainerCleanly() throws Exception {
        String databaseName = databaseName("heap-sanity-missing-container-db");
        Path databaseDirectory = new File(databaseName).toPath();

        try (Connection connection = openDatabase(databaseName, true)) {
            connection.setAutoCommit(false);
            executeUpdate(connection, "create table heap_sanity_missing_t (id int primary key)");
            executeUpdate(connection, "insert into heap_sanity_missing_t values (1)");
            connection.commit();

            long realContainerId = baseContainerId(connection, "HEAP_SANITY_MISSING_T", "heap");
            long missingContainerId = realContainerId + 10_000_000L;
            DelosHeapSanityDiagnostics sanity = DelosStorageDiagnosticsRegistry.inspectHeapSanity(
                    databaseDirectory, 0, missingContainerId);
            assertFalse("missing heap container should not be clean", sanity.clean());
            assertTrue("missing heap container should report at least one error", sanity.errorCount() > 0);
            assertTrue("missing heap container should report missing file", sanity.errors().stream()
                    .anyMatch(error -> error.contains("missing")));
            assertTrue("missing-container check must still be read-only", sanity.readOnly());
            assertFalse("bogus container file should not be created", Files.exists(sanity.containerFile()));
            connection.commit();
        }
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
        assertEquals("expected clean heap sanity report", 0, sanity.errorCount());
        assertTrue("expected clean heap sanity report", sanity.clean());
        assertFalse("expected read-only observations", sanity.observations().isEmpty());
        assertTrue("expected no sanity errors", sanity.errors().isEmpty());
    }
}
